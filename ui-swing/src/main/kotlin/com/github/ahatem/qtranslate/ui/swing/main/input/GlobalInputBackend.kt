package com.github.ahatem.qtranslate.ui.swing.main.input

import java.awt.Point
import java.io.Closeable

/**
 * Sole global-input backend contract for QTranslate (spike/qinput-native-v2).
 *
 * Implementations translate platform global input into [GlobalInputEvent]s. Production uses
 * [QInputBackend] (QInput native runtime); tests substitute a fake. LOCAL Swing shortcuts are
 * unrelated to this interface and stay in Swing InputMaps.
 */
interface GlobalInputBackend : Closeable {

    /**
     * One registered global shortcut.
     *
     * [id] is an opaque token identifying this exact registration (see
     * [HotkeyRegistrationToken]); the backend stores it and echoes it back on every fire. It must
     * identify the registration the caller accepted, so a superseded registration that the
     * platform refused to release can be told apart from its replacement even when both represent
     * the same action.
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
     * Returns whether every obsolete native registration was retired. A degraded result is data,
     * not an exception: the requested set is active either way, so callers must accept the new
     * registrations and only record the leftovers for diagnostics and retry. Throws on transport
     * failure; callers keep the last working set for rollback.
     */
    fun applyHotkeys(registrations: List<HotkeyRegistration>): ApplyResult

    /** Subscribes to raw event classes. Mouse motion is typically tied to opt-in features. */
    fun setRawMask(keyboard: Boolean, mouseButtons: Boolean, mouseMotion: Boolean)

    /** Whether [sendChord] is available on the running backend. */
    val supportsInjection: Boolean

    /**
     * Sends a modifier chord via platform injection. [modifiers] and [key] are portable QInput
     * usages (`QInputKey.MOD_*` / `QInputKey.*`), never platform virtual keys: each backend
     * translates them, so the same constant means the same physical key everywhere. Returns true
     * when the batch was accepted. Throws on transport failure; unsupported backends report false
     * via [supportsInjection] instead of throwing.
     */
    fun sendChord(modifiers: List<Int>, key: Int): Boolean

    /**
     * Reads physical down-state for a portable QInput key usage, independent of hooks or focus.
     * Throws where the backend cannot answer (see [InputCapabilities.keyState]); callers treat
     * failure as down (fail closed).
     */
    fun isKeyDown(key: Int): Boolean

    /**
     * Whether any of [usages] is physically down, read as one backend query where the backend can
     * do so.
     *
     * Preferred by neutralization: on X11 each single query costs a server round-trip, so a
     * watched set would otherwise pay one round-trip per key per poll. Backends without a batch
     * query fall back to the loop, which is what the default does.
     */
    fun anyKeyDown(usages: List<Int>): Boolean = usages.any { isKeyDown(it) }

    /** Receives backend events. Invoked on the backend dispatcher thread, never on the EDT. */
    fun setListener(listener: (GlobalInputEvent) -> Unit)
}

/**
 * What a [GlobalInputBackend.applyHotkeys] call left behind.
 *
 * The requested set is active in both cases. Clean means every obsolete native registration was
 * retired; Degraded means some remain installed — each named so diagnostics can tell cleanup is
 * incomplete and a later apply can retry. The stale ones are non-dispatchable either way, because
 * their registration tokens are no longer accepted.
 */
sealed interface ApplyResult {

    /** Everything retired; nothing outstanding. */
    data object Clean : ApplyResult

    /**
     * The requested set is active, but these obsolete accelerators are still installed. Retried
     * automatically on the next apply.
     */
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

/** Backend-agnostic global input events. Coordinates are screen pixels. */
sealed interface GlobalInputEvent {

    /**
     * A registered shortcut fired (edge-triggered on press).
     *
     * [id] is the registration token echoed from the [HotkeyRegistration] that was applied, not an
     * action identifier: the receiver resolves it against the registrations it currently accepts
     * and ignores anything else.
     */
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
        /**
         * Event time in milliseconds from a monotonic source (never wall-clock; safe to
         * subtract for durations such as the Double Ctrl window). [Long.MIN_VALUE] when the
         * backend provides none, in which case callers fall back to their own monotonic clock.
         */
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
