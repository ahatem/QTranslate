package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.CloseButtonBehavior
import com.github.ahatem.qtranslate.core.settings.data.MinimizeButtonBehavior
import kotlin.test.Test
import kotlin.test.assertEquals

/** Close and Minimize decisions, resolved without a real window or dialog. */
class WindowButtonPolicyTest {

    @Test
    fun `close falls back to the taskbar only when there is no tray`() {
        data class Case(val behavior: CloseButtonBehavior, val tray: Boolean, val expected: CloseButtonAction)
        listOf(
            Case(CloseButtonBehavior.MINIMIZE_TO_TRAY, true, CloseButtonAction.HIDE_TO_TRAY),
            Case(CloseButtonBehavior.MINIMIZE_TO_TRAY, false, CloseButtonAction.MINIMIZE_TO_TASKBAR),
            Case(CloseButtonBehavior.EXIT, false, CloseButtonAction.EXIT),
            Case(CloseButtonBehavior.ASK, false, CloseButtonAction.ASK),
        ).forEach {
            assertEquals(it.expected, CloseButtonPolicy.decide(it.behavior, trayAvailable = it.tray), it.toString())
        }
    }

    @Test
    fun `a close dialog answer applies that choice and carries the remember flag`() {
        listOf(
            CloseButtonBehavior.MINIMIZE_TO_TRAY to true,
            CloseButtonBehavior.MINIMIZE_TO_TRAY to false,
            CloseButtonBehavior.EXIT to true,
            CloseButtonBehavior.EXIT to false,
        ).forEach { (answer, remember) ->
            assertEquals(
                CloseDialogOutcome.Apply(answer, remember),
                CloseDialogPolicy.outcome(answer, rememberChoice = remember),
                "$answer remember=$remember",
            )
        }
    }

    @Test
    fun `canceling the dialog, or answering with the ask setting, dismisses without choosing`() {
        assertEquals(CloseDialogOutcome.Dismiss, CloseDialogPolicy.outcome(null, rememberChoice = true))
        assertEquals(CloseDialogOutcome.Dismiss, CloseDialogPolicy.outcome(CloseButtonBehavior.ASK, rememberChoice = false))
    }

    @Test
    fun `minimize while the window is visible follows the setting and the tray`() {
        data class Case(val behavior: MinimizeButtonBehavior, val tray: Boolean, val expected: MinimizeButtonAction)
        listOf(
            Case(MinimizeButtonBehavior.HIDE_TO_TRAY, true, MinimizeButtonAction.HIDE_TO_TRAY),
            Case(MinimizeButtonBehavior.HIDE_TO_TRAY, false, MinimizeButtonAction.MINIMIZE_TO_TASKBAR),
            Case(MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR, true, MinimizeButtonAction.MINIMIZE_TO_TASKBAR),
        ).forEach {
            assertEquals(
                it.expected,
                MinimizeButtonPolicy.decide(it.behavior, windowCurrentlyVisible = true, trayAvailable = it.tray),
                it.toString(),
            )
        }
    }

    @Test
    fun `iconify while already hidden is an intentional hide path, not a user minimize`() {
        // Snipping, Escape, close-to-tray and startup-hidden all iconify a hidden window.
        MinimizeButtonBehavior.entries.forEach { behavior ->
            assertEquals(
                MinimizeButtonAction.IGNORE,
                MinimizeButtonPolicy.decide(behavior, windowCurrentlyVisible = false),
                "$behavior",
            )
        }
    }
}
