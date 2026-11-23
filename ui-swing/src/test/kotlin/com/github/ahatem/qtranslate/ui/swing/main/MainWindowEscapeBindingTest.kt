package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.event.ActionEvent
import javax.swing.JComponent
import javax.swing.JRootPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Drives the real `WHEN_IN_FOCUSED_WINDOW` maps installed by [MainWindowEscapeBinding], because a
 * [MainAppFrame] cannot be constructed headless.
 */
class MainWindowEscapeBindingTest {

    private fun fireEscape(rootPane: JRootPane) {
        val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionKey = inputMap.get(MainWindowEscapeBinding.ESCAPE_KEYSTROKE)
            ?: error("no WHEN_IN_FOCUSED_WINDOW entry for Escape")
        val action = rootPane.actionMap.get(actionKey)
            ?: error("no ActionMap action for $actionKey")
        action.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, "test"))
    }

    @Test
    fun `escape hides instead of cancelling`() {
        val rootPane = JRootPane()
        var hidden = 0
        var cancelled = 0

        MainWindowEscapeBinding(
            rootPane = rootPane,
            isTranslationInFlight = { false },
            isChildHandlingEscape = { false },
            onCancelTranslation = { cancelled++ },
            onHide = { hidden++ },
        ).register()

        fireEscape(rootPane)

        assertEquals(1, hidden, "Escape must hide")
        assertEquals(0, cancelled, "an idle Escape must not cancel anything")
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

        assertEquals(1, cancelled)
        assertEquals(0, hidden)
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

        assertEquals(0, hidden)
        assertEquals(0, cancelled)
    }

    @Test
    fun `the shared text pane does not shadow the window Escape binding`() {
        // A WHEN_FOCUSED Escape on a pane would win over the root pane and need a second press.
        val pane = onEdt { AdvancedTextPane(onTextChanged = {}, onTranslateRequest = {}, onListenRequest = {}) }

        assertNull(pane.getInputMap(JComponent.WHEN_FOCUSED).get(MainWindowEscapeBinding.ESCAPE_KEYSTROKE))
        assertNull(pane.actionMap.get("escape-to-input"))
    }
}
