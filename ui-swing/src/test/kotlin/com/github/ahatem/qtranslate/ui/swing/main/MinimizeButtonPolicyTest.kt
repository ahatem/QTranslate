package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.MinimizeButtonBehavior
import kotlin.test.Test
import kotlin.test.assertEquals

/** The decision the native Minimize button makes, exercised without Swing event timing. */
class MinimizeButtonPolicyTest {

    @Test
    fun `native iconify with HIDE_TO_TRAY hides`() {
        assertEquals(
            MinimizeButtonAction.HIDE_TO_TRAY,
            MinimizeButtonPolicy.decide(MinimizeButtonBehavior.HIDE_TO_TRAY, windowCurrentlyVisible = true),
        )
    }

    @Test
    fun `native iconify with MINIMIZE_TO_TASKBAR keeps the frame visible and iconified`() {
        assertEquals(
            MinimizeButtonAction.MINIMIZE_TO_TASKBAR,
            MinimizeButtonPolicy.decide(MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR, windowCurrentlyVisible = true),
        )
    }

    @Test
    fun `iconify while already hidden is an intentional hide path, not a user minimize`() {
        // Covers snipping, Escape, close-to-tray and startup-hidden.
        assertEquals(
            MinimizeButtonAction.IGNORE,
            MinimizeButtonPolicy.decide(MinimizeButtonBehavior.HIDE_TO_TRAY, windowCurrentlyVisible = false),
        )
        assertEquals(
            MinimizeButtonAction.IGNORE,
            MinimizeButtonPolicy.decide(MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR, windowCurrentlyVisible = false),
        )
    }
}
