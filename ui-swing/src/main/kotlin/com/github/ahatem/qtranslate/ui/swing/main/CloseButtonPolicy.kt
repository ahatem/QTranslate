package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.CloseButtonBehavior

/**
 * What the main window's close (X) button does, resolved for one close event.
 *
 * Mirrors [MinimizeButtonAction] for the minimize button: the policy is the pure decision,
 * the frame owns the Swing application of it.
 */
internal enum class CloseButtonAction {
    /** Hide the window silently; tray, global hotkeys and window state survive. */
    HIDE_TO_TRAY,

    /** Nothing to hide into, so keep the window reachable on the taskbar instead. */
    MINIMIZE_TO_TASKBAR,

    /** Dispose the frame — the single convergence point for every exit route. */
    EXIT,

    /** Ask the user what to do, remembering the choice if they request it. */
    ASK,
}

/** Pure decision behind MainAppFrame's close-button handling. */
internal object CloseButtonPolicy {
    /**
     * @param trayAvailable whether a tray icon is actually installed. Closing into a tray that is
     *   not there leaves the window unreachable except by relaunching, so the choice degrades to
     *   minimizing to the taskbar rather than hiding.
     */
    fun decide(behavior: CloseButtonBehavior, trayAvailable: Boolean = true): CloseButtonAction =
        when (behavior) {
            CloseButtonBehavior.MINIMIZE_TO_TRAY ->
                if (trayAvailable) CloseButtonAction.HIDE_TO_TRAY
                else CloseButtonAction.MINIMIZE_TO_TASKBAR
            CloseButtonBehavior.EXIT -> CloseButtonAction.EXIT
            CloseButtonBehavior.ASK -> CloseButtonAction.ASK
        }
}

/** What the close-confirmation dialog's answer means. */
internal sealed interface CloseDialogOutcome {
    /** Dismissed without a choice — Escape, Cancel, or closing the dialog itself. */
    data object Dismiss : CloseDialogOutcome

    /**
     * A concrete exit choice.
     *
     * @param rememberChoice whether "Remember my choice" was checked, so the answer is
     *   persisted instead of asked again.
     */
    data class Apply(val behavior: CloseButtonBehavior, val rememberChoice: Boolean) : CloseDialogOutcome
}

/**
 * Maps the close dialog's result to an outcome.
 *
 * [CloseButtonBehavior.ASK] is a configuration value, never a dialog answer — the dialog
 * only offers minimize-to-tray and exit — so it lands on the same dismissal as a null
 * result.
 */
internal object CloseDialogPolicy {
    fun outcome(result: CloseButtonBehavior?, rememberChoice: Boolean): CloseDialogOutcome =
        when (result) {
            null, CloseButtonBehavior.ASK -> CloseDialogOutcome.Dismiss
            CloseButtonBehavior.MINIMIZE_TO_TRAY,
            CloseButtonBehavior.EXIT -> CloseDialogOutcome.Apply(result, rememberChoice)
        }
}
