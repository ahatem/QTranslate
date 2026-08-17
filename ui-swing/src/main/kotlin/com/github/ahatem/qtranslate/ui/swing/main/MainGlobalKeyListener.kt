package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener
import com.tulskiy.keymaster.common.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/**
 * Manages global and local hotkey registration.
 *
 * ### Scopes
 * - [HotkeyScope.GLOBAL] — registered with jKeymaster, fires system-wide
 *   even when QTranslate is not focused.
 * - [HotkeyScope.LOCAL] — the caller (MainAppFrame) registers these via
 *   Swing InputMap/ActionMap; this listener only provides the binding list
 *   via [getLocalBindings]. Local bindings never intercept keys from other apps.
 *
 * ### Per-action scope
 * Users can choose per action whether it should be global or local. This prevents
 * shortcuts like Ctrl+L from being stolen from browsers when set to LOCAL.
 *
 * ### Actions
 * - [HotkeyAction.SHOW_MAIN_WINDOW] — special: uses double-Ctrl via JNativeHook,
 *   not a regular KeyStroke. Always GLOBAL. Respects [isEnabled] on the binding.
 * - [HotkeyAction.REPLACE_WITH_TRANSLATION] — copies selected text, translates,
 *   pastes result back via [onReplaceWithTranslation].
 * - [HotkeyAction.CYCLE_TARGET_LANGUAGE] — default LOCAL, cycles the target language.
 */
class MainGlobalKeyListener(
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val onShowApp: (String) -> Unit,
    private val onShowQuickTranslate: (String) -> Unit,
    private val onListenToText: (String) -> Unit,
    private val onOpenSnippingTool: () -> Unit,
    private val onReplaceWithTranslation: (String) -> Unit,
    private val onCycleTargetLanguage: () -> Unit,
    private val onShowDictionary: (String) -> Unit = {},
    private val onShowImages: (String) -> Unit = {},
    private val onTranslate: () -> Unit = {},
    private val onSelectionDetected: (String, Point) -> Unit = { _, _ -> },
    private val onPointerPressed: (Point) -> Unit = {}
) {

    private var provider: Provider? = null
    private var nativeHookRegistered = false
    private val sequenceListener = CustomSequenceListener()
    private val selectionMouseListener = SelectionMouseListener()
    private val clipboardLock = AtomicBoolean(false)
    private val hotkeysEnabled = AtomicBoolean(true)
    // AtomicBoolean.compareAndSet prevents double-initialization if initialize()
    // is called concurrently (e.g. from two rapid lifecycle events).
    private val initialized = AtomicBoolean(false)
    // Off unless the user opts in — the selection listener observes every mouse
    // drag system-wide, so it stays inert until explicitly enabled.
    private val selectionIconEnabled = AtomicBoolean(false)

    @Volatile private var bindings: List<HotkeyBinding> = HotkeyBinding.DEFAULTS

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            initJKeyMaster()
            initJNativeHook()
        } catch (e: Exception) {
            initialized.set(false)   // allow retry if initialization itself failed
            logger.error("Hotkey initialization failed", e)
        }
    }

    fun updateBindings(newBindings: List<HotkeyBinding>) {
        bindings = newBindings
        if (!initialized.get()) return
        try {
            provider?.reset()
            registerGlobalHotkeys()
        } catch (e: Exception) {
            logger.error("Failed to update hotkey bindings", e)
        }
    }

    fun setHotkeysEnabled(enabled: Boolean) {
        if (!initialized.get()) return
        if (hotkeysEnabled.getAndSet(enabled) == enabled) return
        if (enabled) enableHotkeys() else disableHotkeys()
    }

    /** Enables or disables the floating translate button shown after a text selection. */
    fun setSelectionIconEnabled(enabled: Boolean) {
        selectionIconEnabled.set(enabled)
    }

    /**
     * Returns bindings with [HotkeyScope.LOCAL] scope that are enabled and have a key.
     * The caller (MainAppFrame) registers these via Swing InputMap.
     */
    fun getLocalBindings(): List<HotkeyBinding> =
        bindings.filter { it.scope == HotkeyScope.LOCAL && it.isEnabled && it.hasBinding }

    fun shutdown() {
        if (!initialized.get()) return
        try {
            provider?.reset()
            provider?.stop()
            provider = null
            if (nativeHookRegistered) {
                GlobalScreen.removeNativeKeyListener(sequenceListener)
                GlobalScreen.removeNativeMouseListener(selectionMouseListener)
                GlobalScreen.removeNativeMouseMotionListener(selectionMouseListener)
                GlobalScreen.unregisterNativeHook()
                nativeHookRegistered = false
            }
        } catch (e: Exception) {
            logger.error("Hotkey manager shutdown error", e)
        } finally {
            initialized.set(false)
        }
    }

    private fun initJKeyMaster() {
        provider = Provider.getCurrentProvider(false)
            ?: throw Exception("Hotkey provider unavailable")
        registerGlobalHotkeys()
    }

    /**
     * Registers only [HotkeyScope.GLOBAL] bindings with jKeymaster.
     * LOCAL bindings are handled by MainAppFrame via Swing InputMap.
     *
     * Each binding is wrapped in its own try-catch so a single failure
     * (e.g. OS refuses to grant a reserved key combination) does not
     * silently abort registration of the remaining bindings.
     */
    private fun registerGlobalHotkeys() {
        val p = provider ?: return

        bindings
            .filter { it.scope == HotkeyScope.GLOBAL && it.isEnabled && it.hasBinding }
            .forEach { binding ->
                val keyStroke = binding.toKeyStroke() ?: return@forEach
                val action = binding.action
                runCatching {
                    p.register(keyStroke) {
                        if (!hotkeysEnabled.get()) return@register
                        dispatchAction(action)
                    }
                    logger.debug("Registered global hotkey ${action.name}: $keyStroke")
                }.onFailure { ex ->
                    logger.warn(
                        "Failed to register global hotkey ${action.name} ($keyStroke): ${ex.message}"
                    )
                }
            }
    }

    /**
     * Dispatches an action from either a global hotkey or a local InputMap trigger.
     * Called from both [registerGlobalHotkeys] and MainAppFrame's local key handler.
     */
    fun dispatchAction(action: HotkeyAction) {
        when (action) {
            HotkeyAction.SHOW_QUICK_TRANSLATE ->
                scope.launch { handleSelectedText(onShowQuickTranslate) }
            HotkeyAction.LISTEN_TO_TEXT ->
                scope.launch { handleSelectedText(onListenToText) }
            HotkeyAction.OPEN_OCR ->
                onOpenSnippingTool()
            HotkeyAction.SHOW_MAIN_WINDOW ->
                scope.launch { handleSelectedText(onShowApp) }
            HotkeyAction.REPLACE_WITH_TRANSLATION ->
                scope.launch { handleSelectedText(onReplaceWithTranslation) }
            HotkeyAction.CYCLE_TARGET_LANGUAGE ->
                onCycleTargetLanguage()
            HotkeyAction.SHOW_DICTIONARY ->
                scope.launch { handleSelectedText(onShowDictionary) }
            HotkeyAction.SHOW_IMAGES ->
                scope.launch { handleSelectedText(onShowImages) }
            HotkeyAction.TRANSLATE ->
                onTranslate()
            // Focus actions are LOCAL-scope only — handled by MainContentView's InputMap.
            // Nothing to do here; the branch is required for exhaustive when.
            HotkeyAction.FOCUS_INPUT,
            HotkeyAction.FOCUS_OUTPUT,
            HotkeyAction.FOCUS_EXTRA_OUTPUT,
            // Likewise LOCAL-only; MainAppFrame handles these because each needs a dialog,
            // the clipboard, or the content view.
            HotkeyAction.COPY_TRANSLATION,
            HotkeyAction.CLEAR_INPUT,
            HotkeyAction.SWAP_LANGUAGES,
            HotkeyAction.OPEN_SETTINGS,
            HotkeyAction.SHOW_HISTORY,
            HotkeyAction.TRANSLATE_DOCUMENT -> Unit
        }
    }

    private fun enableHotkeys() {
        try { provider?.reset(); registerGlobalHotkeys() }
        catch (e: Exception) { logger.error("Enable hotkeys failed", e) }
    }

    private fun disableHotkeys() {
        try { provider?.reset() }
        catch (e: Exception) { logger.error("Disable hotkeys failed", e) }
    }

    private fun initJNativeHook() {
        try {
            if (!nativeHookRegistered) {
                GlobalScreen.registerNativeHook()
                nativeHookRegistered = true
            }
            GlobalScreen.addNativeKeyListener(sequenceListener)
            GlobalScreen.addNativeMouseListener(selectionMouseListener)
            GlobalScreen.addNativeMouseMotionListener(selectionMouseListener)
        } catch (ex: NativeHookException) {
            throw Exception("Native hook registration failed", ex)
        }
    }

    /**
     * Handles the double-Ctrl sequence for [HotkeyAction.SHOW_MAIN_WINDOW].
     *
     * This cannot be expressed as a single KeyStroke so it uses JNativeHook's
     * raw key events. It only fires if:
     * 1. Global hotkeys are enabled
     * 2. The SHOW_MAIN_WINDOW binding exists AND isEnabled = true
     *    (Hoyeun's request — user can disable it from the keyboard panel)
     */
    private inner class CustomSequenceListener : NativeKeyListener {
        private var lastCtrlTime = 0L
        private val threshold = 400

        /** True once a key other than Ctrl is pressed while Ctrl is held. */
        private var ctrlWasPartOfCombination = false
        private var ctrlIsDown = false

        override fun nativeKeyPressed(e: NativeKeyEvent) {
            if (e.keyCode == NativeKeyEvent.VC_CONTROL) {
                ctrlIsDown = true
                ctrlWasPartOfCombination = false
            } else if (ctrlIsDown) {
                ctrlWasPartOfCombination = true
            }
        }

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            if (e.keyCode != NativeKeyEvent.VC_CONTROL) return
            ctrlIsDown = false

            // The Ctrl that ends a shortcut is not a tap. Every hotkey in this application is a
            // Ctrl combination, so counting those releases meant two shortcuts pressed within the
            // threshold — Ctrl+Q twice, or Ctrl+Q then Ctrl+D — read as a double-Ctrl and summoned
            // the main window on top of the popup the user actually asked for.
            //
            // The time is cleared as well as ignored, so the release that ends a shortcut cannot
            // pair with a genuine tap that follows it either.
            if (ctrlWasPartOfCombination) {
                ctrlWasPartOfCombination = false
                lastCtrlTime = 0L
                return
            }

            if (!hotkeysEnabled.get()) return

            // Only fire if the binding exists, is enabled, AND the user
            // has not opted out of the double-Ctrl mechanism specifically.
            val binding = bindings.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            if (binding == null || !binding.isEnabled || !binding.isDoubleCtrlEnabled) return

            val now = System.currentTimeMillis()
            if (now - lastCtrlTime < threshold) {
                scope.launch { handleSelectedText(onShowApp) }
                lastCtrlTime = 0L
                return
            }
            lastCtrlTime = now
        }
    }

    /**
     * Detects a drag-to-select gesture in any application and reports the selected
     * text via [onSelectionDetected] so the caller can offer a floating translate button.
     *
     * A click alone is not a selection, so the gesture only counts once the pointer has
     * travelled [MIN_SELECTION_DRAG_DISTANCE] while the primary button is held. After
     * release the capture waits [SELECTION_SETTLE_DELAY_MS] so the source application
     * has finished updating its own selection before Copy is synthesized.
     */
    private inner class SelectionMouseListener : NativeMouseInputListener {
        private var pressedAt: Point? = null
        private var dragged = false

        override fun nativeMousePressed(event: NativeMouseEvent) {
            // Reported before the selection-icon check, not after. This is the only notice the
            // application gets of a press that lands in another program, and the floating popups
            // rely on it to close when the user clicks away. Tying it to the selection button
            // meant turning that button off also stopped popups noticing clicks outside them.
            onPointerPressed(event.point)

            if (!selectionIconEnabled.get()) return
            if (event.button != NativeMouseEvent.BUTTON1) return
            pressedAt = event.point
            dragged = false
        }

        override fun nativeMouseDragged(event: NativeMouseEvent) {
            val start = pressedAt ?: return
            if (start.distance(event.point) >= MIN_SELECTION_DRAG_DISTANCE) dragged = true
        }

        override fun nativeMouseReleased(event: NativeMouseEvent) {
            val wasSelectionDrag = selectionIconEnabled.get() && dragged
            pressedAt = null
            dragged = false
            if (!wasSelectionDrag) return

            val pointer = event.point
            scope.launch {
                delay(SELECTION_SETTLE_DELAY_MS)
                handleSelectedText { text ->
                    // Re-check the flag: the user may have disabled the option while
                    // the capture was in flight.
                    if (text.isNotBlank() && selectionIconEnabled.get()) {
                        onSelectionDetected(text, pointer)
                    }
                }
            }
        }
    }

    private suspend fun handleSelectedText(callback: (String) -> Unit) {
        if (!clipboardLock.compareAndSet(false, true)) return
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val original  = runCatching { clipboard.getContents(null) }.getOrNull()

            try {
                // Global hotkey callbacks can arrive while Ctrl/Cmd is still physically held.
                // Give the originating key sequence time to finish before synthesizing Copy.
                delay(80)

                var text: String? = null
                for (backoffMs in longArrayOf(50, 90, 140)) {
                    val sentinel = "qtranslate-copy-${UUID.randomUUID()}"
                    runCatching { clipboard.setContents(StringSelection(sentinel), null) }

                    simulateCopy()
                    delay(backoffMs)

                    val candidate = runCatching {
                        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                            clipboard.getData(DataFlavor.stringFlavor).toString().trim()
                        } else {
                            null
                        }
                    }.getOrNull()

                    if (!candidate.isNullOrEmpty() && candidate != sentinel) {
                        text = candidate
                        break
                    }
                }

                callback(text.orEmpty())
            } finally {
                original?.let { runCatching { clipboard.setContents(it, null) } }
            }
        } finally {
            clipboardLock.set(false)
        }
    }

    private fun simulateCopy() {
        runCatching {
            val robot = Robot()
            robot.autoDelay = 20
            val copyModifier = if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
                KeyEvent.VK_META
            } else {
                KeyEvent.VK_CONTROL
            }
            robot.keyPress(copyModifier)
            robot.keyPress(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_C)
            robot.keyRelease(copyModifier)
            robot.waitForIdle()
        }.onFailure { logger.warn("Copy simulation failed: ${it.message}") }
    }

    private companion object {
        /** Pointer travel, in pixels, before a press-and-drag counts as a selection. */
        const val MIN_SELECTION_DRAG_DISTANCE = 5.0

        /** Grace period after mouse release so the source app can settle its selection. */
        const val SELECTION_SETTLE_DELAY_MS = 80L
    }
}
