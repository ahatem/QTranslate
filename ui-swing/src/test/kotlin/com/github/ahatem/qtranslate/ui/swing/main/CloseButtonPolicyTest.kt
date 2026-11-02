package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.CloseButtonBehavior
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The close (X) decision and the close-dialog answer mapping, without a real window or dialog.
 *
 * [CloseButtonPolicy] resolves the configured behavior; [CloseDialogPolicy] resolves what the
 * confirmation dialog's answer (including "no answer") means. Kept apart from the frame so the
 * mapping cannot drift from the behavior enum it interprets.
 */
class CloseButtonPolicyTest {

    @Test
    fun `the configured behavior decides the close action`() {
        assertEquals(CloseButtonAction.HIDE_TO_TRAY, CloseButtonPolicy.decide(CloseButtonBehavior.MINIMIZE_TO_TRAY))
        assertEquals(CloseButtonAction.EXIT, CloseButtonPolicy.decide(CloseButtonBehavior.EXIT))
        assertEquals(CloseButtonAction.ASK, CloseButtonPolicy.decide(CloseButtonBehavior.ASK))
    }

    @Test
    fun `an answer of minimize to tray applies that choice and can remember it`() {
        assertEquals(
            CloseDialogOutcome.Apply(CloseButtonBehavior.MINIMIZE_TO_TRAY, true),
            CloseDialogPolicy.outcome(CloseButtonBehavior.MINIMIZE_TO_TRAY, rememberChoice = true),
        )
        assertEquals(
            CloseDialogOutcome.Apply(CloseButtonBehavior.MINIMIZE_TO_TRAY, false),
            CloseDialogPolicy.outcome(CloseButtonBehavior.MINIMIZE_TO_TRAY, rememberChoice = false),
        )
    }

    @Test
    fun `an answer of exit applies that choice whether or not it is remembered`() {
        assertEquals(
            CloseDialogOutcome.Apply(CloseButtonBehavior.EXIT, false),
            CloseDialogPolicy.outcome(CloseButtonBehavior.EXIT, rememberChoice = false),
        )
        assertEquals(
            CloseDialogOutcome.Apply(CloseButtonBehavior.EXIT, true),
            CloseDialogPolicy.outcome(CloseButtonBehavior.EXIT, rememberChoice = true),
        )
    }

    @Test
    fun `canceling the dialog dismisses it without choosing`() {
        assertEquals(
            CloseDialogOutcome.Dismiss,
            CloseDialogPolicy.outcome(null, rememberChoice = true),
        )
    }

    @Test
    fun `ask is a configuration value, never a dialog answer`() {
        assertEquals(
            CloseDialogOutcome.Dismiss,
            CloseDialogPolicy.outcome(CloseButtonBehavior.ASK, rememberChoice = false),
        )
    }
}
