package com.github.ahatem.qtranslate.ui.swing.main.input

import java.awt.Point
import java.io.Closeable

/**
 * Sole global-input backend contract for QTranslate.
 *
 * Implementations translate platform global input into [GlobalInputEvent]s. Production uses
 * [QInputBackend] (QInput native runtime); tests substitute a fake. LOCAL Swing shortcuts are
 * unrelated to this interface and stay in Swing InputMaps.
 */
interface GlobalInputBackend : Closeable {

    /**
     * [id] is an opaque registration token (see [HotkeyRegistrationToken]): it must identify
     * the exact accepted registration, so a superseded one can be told apart from its
     * replacement.
     */
    data class HotkeyRegistration(
        val id: Long,
        val accelerator: String,
        val description: String,
    )

    /** Native backend name, e.g. "win32". For logging only. */
    val backendName: String

    /** What the running backend can do. Queried once at startup; see [InputCapabilities]. */
    val capabilities: InputCapabilities

    /**
     * Replaces the whole registered-hotkey set. An empty list unregisters everything.
     *
     * Returns whether every obsolete registration was retired; a degraded result is not an
     * error — the new set is active either way, and leftovers are only for diagnostics/retry.
     * Throws on transport failure.
     */
    fun applyHotkeys(registrations: List<HotkeyRegistration>): ApplyResult

    /** Subscribes to raw event classes. Mouse motion is typically tied to opt-in features. */
    fun setRawMask(keyboard: Boolean, mouseButtons: Boolean, mouseMotion: Boolean)

    /** Whether [sendChord] is available on the running backend. */
    val supportsInjection: Boolean

    /**
     * Sends a modifier chord via platform injection. [modifiers]/[key] are portable QInput
     * usages, never platform virtual keys — each backend translates them. Returns true when
     * accepted. Unsupported backends report false via [supportsInjection] rather than throwing.
     */
    fun sendChord(modifiers: List<Int>, key: Int): Boolean

    /** Physical down-state for a key usage. Throws where unsupported; callers treat failure as down. */
    fun isKeyDown(key: Int): Boolean

    /**
     * Whether any of [usages] is down, as one backend query where supported.
     *
     * Preferred by neutralization: on X11 each query is a server round-trip, so a watched set
     * would otherwise cost one round-trip per key per poll.
     */
    fun anyKeyDown(usages: List<Int>): Boolean = usages.any { isKeyDown(it) }

    /** Receives backend events. Invoked on the backend dispatcher thread, never on the EDT. */
    fun setListener(listener: (GlobalInputEvent) -> Unit)
}

/** What a [GlobalInputBackend.applyHotkeys] call left behind. The requested set is active either way. */
sealed interface ApplyResult {

    /** Everything retired; nothing outstanding. */
    data object Clean : ApplyResult

    /** These obsolete accelerators are still installed, but not dispatchable; retried on the next apply. */
    data class Degraded(val leftovers: List<String>) : ApplyResult
}

/** Capability snapshot of a running backend. Absent capabilities degrade, never crash. */
data class InputCapabilities(
    val hotkeys: Boolean,
    val rawKeyboard: Boolean,
    val rawMouseButtons: Boolean,
    val rawMouseMotion: Boolean,
    val portalHotkeys: Boolean,
    val injectedFlag: Boolean,
    val keyboardInjection: Boolean,
    /** Synchronous physical key-state query for trigger neutralization. */
    val keyState: Boolean,
)

/**
 * Backend-agnostic global input events.
 *
 * Coordinates are Swing user-space (logical) screen coordinates: [QInputBackend] converts the
 * native physical pixels the platform reports (Windows low-level hooks) exactly once at the
 * entry boundary. On platforms where native and Swing coordinates already agree the mapping
 * is identity, so consumers must never scale these points again.
 */
sealed interface GlobalInputEvent {

    /** [id] is the registration token from the applied [HotkeyRegistration], not an action identifier. */
    data class Hotkey(val id: Long) : GlobalInputEvent

    /** Raw keyboard press/release for modifier tracking such as Double Ctrl. */
    data class Key(
        val keyClass: KeyClass,
        val nativeCode: Int,
        val pressed: Boolean,
        val injected: Boolean,
        val repeat: Boolean,
        /** True when QInput itself injected this event (tagged, never genuine user input). */
        val selfInjected: Boolean = false,
        /** Monotonic time in ms, never wall-clock. [Long.MIN_VALUE] when the backend provides none. */
        val timestampMs: Long,
    ) : GlobalInputEvent

    /** Global mouse button press/release. */
    data class MouseButton(
        val button: MouseButtonId,
        val pressed: Boolean,
        val location: Point,
    ) : GlobalInputEvent

    /** Global mouse motion (only delivered while the primary button is held). */
    data class MouseMove(val location: Point) : GlobalInputEvent
}

enum class KeyClass { CONTROL, SHIFT, ALT, META, OTHER }

enum class MouseButtonId { LEFT, RIGHT, MIDDLE, OTHER }
