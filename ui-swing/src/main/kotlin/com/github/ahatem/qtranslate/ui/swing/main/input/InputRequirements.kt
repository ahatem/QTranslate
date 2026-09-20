package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding

/**
 * Pure derivation of required native input state from QTranslate state.
 *
 * There is exactly one reconciliation path ([MainGlobalKeyListener] calls it after every
 * relevant change), so the native mask and the registration set can never drift apart:
 *
 * - registered shortcuts exist only while global hotkeys are enabled;
 * - raw keyboard exists only for Double Ctrl while it can fire (enabled globally and for
 *   SHOW_MAIN_WINDOW), unless another raw-keyboard consumer appears;
 * - raw mouse buttons exist while the selection icon or outside-click dismissal can need
 *   them; unlike the old always-on hook, neither feature leaves a system-wide mouse hook
 *   running while switched off;
 * - raw mouse motion exists only while selection-drag tracking is enabled.
 */
data class ResolvedInput(
    /** Whether the current registration set should be applied (else cleared). */
    val registerHotkeys: Boolean,
    val keyboard: Boolean,
    val mouseButtons: Boolean,
    val mouseMotion: Boolean,
)

object InputRequirements {

    fun resolve(state: InputRuntimeState): ResolvedInput = ResolvedInput(
        registerHotkeys = state.effectiveHotkeysEnabled,
        // Pause suppresses registered shortcuts only; raw keyboard observation (and therefore
        // Double Ctrl itself) follows the configured switch, matching the historical behavior
        // where pausing only reset the shortcut provider while the raw hook kept running.
        keyboard = state.globalHotkeysEnabled && state.doubleCtrlEnabled,
        mouseButtons = state.selectionIconEnabled || state.dismissOnOutsideClickEnabled,
        mouseMotion = state.selectionIconEnabled
    )

    fun resolve(
        hotkeysEnabled: Boolean,
        doubleCtrlEnabled: Boolean,
        selectionIconEnabled: Boolean,
        dismissOnOutsideClickEnabled: Boolean,
    ): ResolvedInput = resolve(
        InputRuntimeState(
            bindings = emptyList(),
            globalHotkeysEnabled = hotkeysEnabled,
            paused = false,
            doubleCtrlEnabled = doubleCtrlEnabled,
            selectionIconEnabled = selectionIconEnabled,
            dismissOnOutsideClickEnabled = dismissOnOutsideClickEnabled
        )
    )
}

/**
 * The complete runtime input state in one explicit value.
 *
 * [globalHotkeysEnabled] is the configured (saved) switch; [paused] is the temporary
 * recording-pause overlay. They are separate concepts: pausing suppresses registered
 * shortcuts without touching the configured switch, and resuming restores exactly what
 * was configured. [doubleCtrlEnabled] carries the effective SHOW_MAIN_WINDOW opt-in;
 * `updateRuntimeState` always (re)derives it from [bindings], so callers cannot desync it.
 */
data class InputRuntimeState(
    val bindings: List<HotkeyBinding> = emptyList(),
    val globalHotkeysEnabled: Boolean = true,
    val paused: Boolean = false,
    val doubleCtrlEnabled: Boolean = true,
    val selectionIconEnabled: Boolean = false,
    val dismissOnOutsideClickEnabled: Boolean = true,
) {
    /** Registrations (and therefore Double Ctrl firing) honor pause; raw tracking does not. */
    val effectiveHotkeysEnabled: Boolean get() = globalHotkeysEnabled && !paused
}
