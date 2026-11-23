package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real MouseEvents on a headless component: only the first completed drag commits, with its Shift state. */
class ScreenCaptureControllerTest {

    @Test
    fun `the first valid selection commits once with its shift state`() {
        val harness = Harness(state())
        val component = JPanel()

        harness.controller.mousePressed(mouse(component, MouseEvent.MOUSE_PRESSED, 10, 10))
        harness.controller.mouseDragged(mouse(component, MouseEvent.MOUSE_DRAGGED, 60, 40))
        harness.controller.mouseReleased(mouse(component, MouseEvent.MOUSE_RELEASED, 60, 40, shift = true))

        assertEquals(listOf(true), harness.commits)
        assertEquals(Rectangle(10, 10, 50, 30), harness.state.selection)
        assertEquals(CaptureMode.SELECTED, harness.state.mode)
        assertTrue(harness.state.showButtons)
    }

    @Test
    fun `adjusting an existing selection does not commit the workflow again`() {
        val harness = Harness(
            state(selection = Rectangle(10, 10, 50, 30), mode = CaptureMode.SELECTED, showButtons = true)
        )
        val component = JPanel()

        harness.controller.mousePressed(mouse(component, MouseEvent.MOUSE_PRESSED, 30, 25))
        harness.controller.mouseDragged(mouse(component, MouseEvent.MOUSE_DRAGGED, 35, 30))
        harness.controller.mouseReleased(mouse(component, MouseEvent.MOUSE_RELEASED, 35, 30))

        assertTrue(harness.commits.isEmpty(), "move/resize is not a new capture workflow")
    }

    @Test
    fun `a zero-size drag never commits`() {
        val harness = Harness(state())
        val component = JPanel()

        harness.controller.mousePressed(mouse(component, MouseEvent.MOUSE_PRESSED, 10, 10))
        harness.controller.mouseReleased(mouse(component, MouseEvent.MOUSE_RELEASED, 10, 10))

        assertTrue(harness.commits.isEmpty())
        assertEquals(CaptureMode.IDLE, harness.state.mode)
        assertEquals(null, harness.state.selection?.takeIf { it.width > 0 && it.height > 0 })
    }

    @Test
    fun `dragging outside the panel is clamped to the capture bounds`() {
        var state = state()
        val controller = ScreenCaptureController(
            currentState = state,
            onStateChanged = { state = it },
            selectionBounds = { Dimension(200, 100) },
            onSelectionCommitted = {},
        )
        val component = JPanel()

        controller.mousePressed(mouse(component, MouseEvent.MOUSE_PRESSED, 10, 10))
        controller.mouseDragged(mouse(component, MouseEvent.MOUSE_DRAGGED, 300, 200))

        assertEquals(Rectangle(10, 10, 190, 90), state.selection)
        assertFalse(state.showButtons)
    }

    private class Harness(initial: ScreenCaptureState) {
        var state = initial
        val commits = mutableListOf<Boolean>()
        val controller: ScreenCaptureController = ScreenCaptureController(
            currentState = initial,
            onStateChanged = {
                state = it
                controller.updateState(it)
            },
            selectionBounds = { Dimension(200, 100) },
            onSelectionCommitted = { commits += it },
        )
    }

    private fun state(
        selection: Rectangle? = null,
        mode: CaptureMode = CaptureMode.IDLE,
        showButtons: Boolean = false,
    ) = ScreenCaptureState(
        screenshot = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB),
        selection = selection,
        mode = mode,
        showButtons = showButtons,
    )

    private fun mouse(
        component: JPanel,
        id: Int,
        x: Int,
        y: Int,
        shift: Boolean = false,
    ) = MouseEvent(
        component,
        id,
        System.currentTimeMillis(),
        if (shift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0,
        x,
        y,
        1,
        false,
        MouseEvent.BUTTON1,
    )
}
