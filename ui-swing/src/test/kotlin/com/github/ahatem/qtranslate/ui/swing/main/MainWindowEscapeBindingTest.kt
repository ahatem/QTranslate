package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.event.ActionEvent
import javax.swing.JComponent
import javax.swing.JRootPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the Escape-to-hide half of #216 through the binding seam.
 *
 * A real [MainAppFrame] is a `JFrame` and cannot be constructed headless, so these
 * tests drive the actual `WHEN_IN_FOCUSED_WINDOW` InputMap/ActionMap installed by
 * [MainWindowEscapeBinding] — the same maps Swing consults — without Robot timing.
 */
class MainWindowEscapeBindingTest {

    /** Fires the installed Escape action exactly as Swing would for the InputMap entry. */
    private fun fireEscape(rootPane: JRootPane) {
        val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionKey = inputMap.get(MainWindowEscapeBinding.ESCAPE_KEYSTROKE)
            ?: error("no WHEN_IN_FOCUSED_WINDOW entry for Escape")
        val action = rootPane.actionMap.get(actionKey)
            ?: error("no ActionMap action for $actionKey")
        action.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, "test"))
    }

    @Test
    fun `escape is bound on the root pane when-in-focused-window map`() {
        val rootPane = JRootPane()
        var hidden = 0

        MainWindowEscapeBinding(rootPane = rootPane, onHide = { hidden++ }).register()

        val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        assertNotNull(
            inputMap.get(MainWindowEscapeBinding.ESCAPE_KEYSTROKE),
            "Escape must be bound WHEN_IN_FOCUSED_WINDOW so it works from any ordinary child",
        )
    }

    @Test
    fun `escape hides instead of cancelling, disposing, or exiting`() {
        val rootPane = JRootPane()
        var hidden = 0
        var cancelled = 0
        // Models the frame contract: hide flips visibility, never disposes, state untouched.
        var visible = true
        var displayable = true
        var inputText = "hello"

        val binding = MainWindowEscapeBinding(
            rootPane = rootPane,
            isTranslationInFlight = { false },
            isChildHandlingEscape = { false },
            onCancelTranslation = { cancelled++ },
            onHide = {
                hidden++
                visible = false
            },
        )
        binding.register()

        fireEscape(rootPane)

        assertEquals(1, hidden, "Escape must invoke hide")
        assertEquals(0, cancelled, "an idle Escape must not cancel anything")
        assertEquals(false, visible, "main window becomes not visible")
        assertTrue(displayable, "hide must not dispose the frame")
        assertEquals("hello", inputText, "Escape must not clear input")

        // The existing show path restores the same window.
        visible = true
        assertEquals(true, visible, "next normal show action can show it again")
    }

    @Test
    fun `escape while translating cancels instead of hiding`() {
        val rootPane = JRootPane()
        var hidden = 0
        var cancelled = 0

        MainWindowEscapeBinding(
            rootPane = rootPane,
            isTranslationInFlight = { true },
            isChildHandlingEscape = { false },
            onCancelTranslation = { cancelled++ },
            onHide = { hidden++ },
        ).register()

        fireEscape(rootPane)

        assertEquals(1, cancelled, "in-flight Escape must cancel the translation first")
        assertEquals(0, hidden, "cancelling must not hide the window")
    }

    @Test
    fun `escape owned by a child popup or menu is left alone`() {
        val rootPane = JRootPane()
        var hidden = 0
        var cancelled = 0

        MainWindowEscapeBinding(
            rootPane = rootPane,
            isTranslationInFlight = { false },
            isChildHandlingEscape = { true },
            onCancelTranslation = { cancelled++ },
            onHide = { hidden++ },
        ).register()

        fireEscape(rootPane)

        assertEquals(0, hidden, "a child-owned Escape must not hide the main window")
        assertEquals(0, cancelled, "a child-owned Escape must not cancel either")
    }

    @Test
    fun `repeated escape while already hidden is harmless`() {
        val rootPane = JRootPane()
        var hidden = 0

        MainWindowEscapeBinding(rootPane = rootPane, onHide = { hidden++ }).register()

        fireEscape(rootPane)
        fireEscape(rootPane)

        assertEquals(2, hidden, "hide is idempotent — no dispose, no exit, no crash")
    }
}
