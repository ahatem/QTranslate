package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding

/**
 * Pure derivation of required native input state from QTranslate state.
 *
 * There is exactly one reconciliation path ([MainGlobalKeyListener] calls this after every
 * relevant change), so the native mask and the registration set can never drift apart.
 */
data class ResolvedInput(
    val registerHotkeys: Boolean,
    val keyboard: Boolean,
    val mouseButtons: Boolean,
    val mouseMotion: Boolean,
)

object InputRequirements {

    fun resolve(state: InputRuntimeState): ResolvedInput = ResolvedInput(
        registerHotkeys = state.effectiveHotkeysEnabled,
        // Raw keyboard tracking follows the configured switch, not the paused one.
        keyboard = state.globalHotkeysEnabled && state.doubleCtrlEnabled,
        mouseButtons = state.selectionCaptureEnabled || state.dismissOnOutsideClickEnabled,
        mouseMotion = state.selectionCaptureEnabled
    )
}

/**
 * [globalHotkeysEnabled] is the configured switch; [paused] is a temporary overlay that
 * suppresses registrations without touching it. [doubleCtrlEnabled] is always rederived from
 * [bindings], so callers cannot desync it.
 */
data class InputRuntimeState(
    val bindings: List<HotkeyBinding> = emptyList(),
    val globalHotkeysEnabled: Boolean = true,
    val paused: Boolean = false,
    val doubleCtrlEnabled: Boolean = true,
    /** Whether raw mouse selection capture is needed by any automatic selection behavior. */
    val selectionCaptureEnabled: Boolean = false,
    val dismissOnOutsideClickEnabled: Boolean = true,
) {
    /** Registered shortcut firing honors pause; raw tracking does not. */
    val effectiveHotkeysEnabled: Boolean get() = globalHotkeysEnabled && !paused
}
