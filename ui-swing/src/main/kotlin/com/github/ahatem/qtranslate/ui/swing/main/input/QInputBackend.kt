package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.api.core.Logger
import io.github.ahatem.qinput.QInput
import io.github.ahatem.qinput.QInputEvent
import java.awt.Point
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [GlobalInputBackend] over the QInput native runtime (QInput Java/JNA binding).
 *
 * Events are delivered on the QInput dispatcher thread via a direct executor, matching the
 * threading posture the old JNativeHook listeners had: no Swing work happens here, callers
 * hop to their coroutine scope. [close] is idempotent and ordered (stop, join dispatcher,
 * then free the handle) per the native lifecycle contract; no callback runs after shutdown.
 */
internal class QInputBackend(
    private val logger: Logger,
    initialRawMask: RawMask = RawMask(keyboard = true, mouseButtons = true, mouseMotion = false),
    private val inputFactory: (QInput.Listener, Executor, QInput.Config) -> QInput = { listener, executor, config ->
        QInput(listener, executor, config)
    },
) : GlobalInputBackend {

    data class RawMask(val keyboard: Boolean, val mouseButtons: Boolean, val mouseMotion: Boolean)

    private val closed = AtomicBoolean(false)
    @Volatile private var events: (GlobalInputEvent) -> Unit = {}

    private val runtime: QInput = run {
        val config = QInput.Config().rawMask(toBits(initialRawMask))
        inputFactory(
            object : QInput.Listener {
                override fun onEvent(event: QInputEvent) {
                    if (closed.get()) return
                    toGlobalEvent(event)?.let { events(it) }
                }

                override fun onError(error: Throwable) {
                    if (!closed.get()) logger.warn("QInput backend error: ${error.message}")
                }
            },
            Executor { it.run() },
            config
        )
    }

    override val backendName: String = runCatching { runtime.backend() }.getOrDefault("unknown")

    override val capabilities: InputCapabilities = runCatching { runtime.capabilities() }
        .map { caps ->
            InputCapabilities(
                hotkeys = caps.has(io.github.ahatem.qinput.QInputCapabilities.HOTKEYS),
                rawKeyboard = caps.has(io.github.ahatem.qinput.QInputCapabilities.RAW_KEYBOARD),
                rawMouseButtons = caps.has(io.github.ahatem.qinput.QInputCapabilities.RAW_MOUSE_BUTTONS),
                rawMouseMotion = caps.has(io.github.ahatem.qinput.QInputCapabilities.RAW_MOUSE_MOTION),
                portalHotkeys = caps.has(io.github.ahatem.qinput.QInputCapabilities.PORTAL_HOTKEYS),
                injectedFlag = caps.has(io.github.ahatem.qinput.QInputCapabilities.INJECTED_FLAG),
                keyboardInjection = caps.has(io.github.ahatem.qinput.QInputCapabilities.KEYBOARD_INJECTION),
                keyState = caps.has(io.github.ahatem.qinput.QInputCapabilities.KEY_STATE)
            )
        }
        .getOrDefault(InputCapabilities(false, false, false, false, false, false, false, false))

    override val supportsInjection: Boolean get() = capabilities.keyboardInjection

    override fun sendChord(modifiers: List<Int>, key: Int): Boolean {
        runtime.sendChord(modifiers.toIntArray(), key)
        return true
    }

    override fun isKeyDown(key: Int): Boolean = runtime.isKeyDown(key)

    /**
     * One native query for the whole watched set. The fallback would call [isKeyDown] once per
     * key, which on X11 is one server round-trip each.
     */
    override fun anyKeyDown(usages: List<Int>): Boolean {
        if (usages.isEmpty()) return false
        return runtime.areKeysDown(usages.toIntArray()).any { it }
    }

    override fun applyHotkeys(registrations: List<GlobalInputBackend.HotkeyRegistration>): ApplyResult {
        val result = runtime.apply(registrations.map { QInput.Shortcut(it.id, it.accelerator, it.description) })
        return if (result.isDegraded) ApplyResult.Degraded(result.getLeftovers()) else ApplyResult.Clean
    }

    override fun setRawMask(keyboard: Boolean, mouseButtons: Boolean, mouseMotion: Boolean) {
        runtime.setRawMask(toBits(RawMask(keyboard, mouseButtons, mouseMotion)))
    }

    override fun setListener(listener: (GlobalInputEvent) -> Unit) {
        events = listener
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { runtime.close() }
            .onFailure { logger.warn("QInput shutdown failed: ${it.message}") }
    }

    private fun toBits(mask: RawMask): Int {
        var bits = 0
        if (mask.keyboard) bits = bits or QInput.RAW_KEYBOARD
        if (mask.mouseButtons) bits = bits or QInput.RAW_MOUSE_BUTTONS
        if (mask.mouseMotion) bits = bits or QInput.RAW_MOUSE_MOTION
        return bits
    }

    companion object {
        fun toGlobalEvent(event: QInputEvent): GlobalInputEvent? = when (event.kind()) {
            // QTranslate actions are edge-triggered on press; releases carry no action.
            QInputEvent.Kind.HOTKEY -> if (event.isPressed) GlobalInputEvent.Hotkey(event.id()) else null
            QInputEvent.Kind.KEY -> GlobalInputEvent.Key(
                keyClass = when (event.keyClass()) {
                    QInputEvent.KeyClass.CONTROL -> KeyClass.CONTROL
                    QInputEvent.KeyClass.SHIFT -> KeyClass.SHIFT
                    QInputEvent.KeyClass.ALT -> KeyClass.ALT
                    QInputEvent.KeyClass.META -> KeyClass.META
                    QInputEvent.KeyClass.OTHER -> KeyClass.OTHER
                },
                nativeCode = event.nativeCode(),
                pressed = event.isPressed,
                injected = event.isInjected,
                repeat = event.isRepeat,
                selfInjected = event.isSelfInjected,
                timestampMs = if (event.timestampMicros() > 0) event.timestampMicros() / 1000 else Long.MIN_VALUE
            )
            QInputEvent.Kind.MOUSE_BUTTON -> GlobalInputEvent.MouseButton(
                button = when (event.button()) {
                    QInputEvent.MouseButton.LEFT -> MouseButtonId.LEFT
                    QInputEvent.MouseButton.RIGHT -> MouseButtonId.RIGHT
                    QInputEvent.MouseButton.MIDDLE -> MouseButtonId.MIDDLE
                    QInputEvent.MouseButton.NONE,
                    QInputEvent.MouseButton.OTHER -> MouseButtonId.OTHER
                },
                pressed = event.isPressed,
                location = Point(event.x().toInt(), event.y().toInt())
            )
            QInputEvent.Kind.MOUSE_MOVE -> GlobalInputEvent.MouseMove(
                location = Point(event.x().toInt(), event.y().toInt())
            )
            QInputEvent.Kind.BACKEND,
            QInputEvent.Kind.UNKNOWN -> null
        }
    }
}
