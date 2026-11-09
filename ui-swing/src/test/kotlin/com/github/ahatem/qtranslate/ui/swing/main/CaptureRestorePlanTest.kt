package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The OCR capture restore contract: dismissing a capture returns the windows to
 * their prior state — visibility and extended state exactly, focus back only to
 * the window that had it, never to a window popped up uninvited.
 */
class CaptureRestorePlanTest {

    @Test
    fun `frame-focused capture restores visibility extended state and input focus`() {
        val plan = captureRestorePlan(
            PriorCaptureState(frameVisible = true, frameExtendedState = Frame.MAXIMIZED_BOTH, focusOwner = CaptureFocusOwner.FRAME)
        )

        assertTrue(plan.frameVisible)
        assertEquals(Frame.MAXIMIZED_BOTH, plan.frameExtendedState)
        assertTrue(plan.raiseFrame)
        assertTrue(plan.focusFrameInput)
        assertFalse(plan.raiseWorkspace)
    }

    @Test
    fun `hidden tray frame stays hidden and is never raised on dismiss`() {
        val plan = captureRestorePlan(
            PriorCaptureState(frameVisible = false, frameExtendedState = Frame.NORMAL, focusOwner = CaptureFocusOwner.OTHER)
        )

        assertFalse(plan.frameVisible, "a tray-hidden frame must stay hidden after a dismissed capture")
        assertFalse(plan.raiseFrame)
        assertFalse(plan.focusFrameInput)
        assertFalse(plan.raiseWorkspace)
    }

    @Test
    fun `iconified frame restores iconified`() {
        val plan = captureRestorePlan(
            PriorCaptureState(frameVisible = true, frameExtendedState = Frame.ICONIFIED, focusOwner = CaptureFocusOwner.OTHER)
        )

        assertTrue(plan.frameVisible)
        assertEquals(Frame.ICONIFIED, plan.frameExtendedState)
        assertFalse(plan.raiseFrame, "the user was elsewhere: no window is raised")
        assertFalse(plan.focusFrameInput)
    }

    @Test
    fun `workspace-focused capture raises only the workspace`() {
        val plan = captureRestorePlan(
            PriorCaptureState(frameVisible = true, frameExtendedState = Frame.NORMAL, focusOwner = CaptureFocusOwner.WORKSPACE)
        )

        assertTrue(plan.frameVisible)
        assertTrue(plan.raiseWorkspace)
        assertFalse(plan.raiseFrame, "the main frame must not steal the workspace's focus")
        assertFalse(plan.focusFrameInput)
    }

    @Test
    fun `unfocused capture raises nothing`() {
        val plan = captureRestorePlan(
            PriorCaptureState(frameVisible = true, frameExtendedState = Frame.NORMAL, focusOwner = CaptureFocusOwner.OTHER)
        )

        assertTrue(plan.frameVisible, "visibility is still restored")
        assertFalse(plan.raiseFrame)
        assertFalse(plan.raiseWorkspace)
        assertFalse(plan.focusFrameInput)
    }
}
