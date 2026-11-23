package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.BorderLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Enter on the capture dialog's root pane fires the Translate default button. Runs headless by
 * mounting the panel into a bare [JRootPane] the way the dialog does.
 */
class ScreenCaptureDefaultButtonTest {

    private fun renderedPanel(
        selection: Rectangle?,
        onTranslate: (BufferedImage) -> Unit = {},
    ): ScreenCapturePanel {
        val panel = ScreenCapturePanel(
            onTranslate = onTranslate,
            onCopyText = {},
            onCopyImage = {},
            onSaveImage = {},
            onRecrop = {},
            onCancel = {},
        )
        panel.render(
            ScreenCaptureState(
                screenshot = BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB),
                selection = selection,
                mode = if (selection == null) CaptureMode.IDLE else CaptureMode.SELECTED,
                showButtons = selection != null,
            )
        )
        return panel
    }

    /** Mirrors SnippingToolDialog: panel in the content pane, Translate as default button. */
    private fun dialogSeam(panel: ScreenCapturePanel): JRootPane {
        val rootPane = JRootPane()
        rootPane.updateUI()
        rootPane.contentPane.add(panel, BorderLayout.CENTER)
        rootPane.defaultButton = panel.translateButton
        return rootPane
    }

    private fun fireEnter(rootPane: JRootPane) {
        val keystroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionKey = inputMap.get(keystroke)
            ?: error("LAF installed no WHEN_IN_FOCUSED_WINDOW entry for Enter")
        val action = rootPane.actionMap.get(actionKey)
            ?: error("LAF installed no action for the Enter input-map key")
        action.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, "press"))
    }

    @Test
    fun `translate enables only for a valid selection and disables again on reset`() {
        val panel = ScreenCapturePanel(
            onTranslate = {}, onCopyText = {}, onCopyImage = {}, onSaveImage = {},
            onRecrop = {}, onCancel = {},
        )
        val screenshot = BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB)

        panel.render(ScreenCaptureState(screenshot = screenshot))
        assertFalse(panel.translateButton.isEnabled, "no selection")

        panel.render(
            ScreenCaptureState(
                screenshot = screenshot,
                selection = Rectangle(10, 10, 0, 0),
                mode = CaptureMode.SELECTING,
            )
        )
        assertFalse(panel.translateButton.isEnabled, "zero-size drag")

        panel.render(
            ScreenCaptureState(
                screenshot = screenshot,
                selection = Rectangle(10, 10, 40, 30),
                mode = CaptureMode.SELECTED,
                showButtons = true,
            )
        )
        assertTrue(panel.translateButton.isEnabled, "real selection")

        panel.render(ScreenCaptureState(screenshot = screenshot, mode = CaptureMode.IDLE))
        assertFalse(panel.translateButton.isEnabled, "after re-crop")
    }

    @Test
    fun `enter on the root pane invokes translate exactly once when enabled`() {
        val translations = mutableListOf<BufferedImage>()
        val panel = renderedPanel(Rectangle(10, 10, 40, 30)) { translations.add(it) }
        val rootPane = dialogSeam(panel)

        fireEnter(rootPane)

        assertEquals(1, translations.size, "Enter fires Translate once")
        assertEquals(40, translations[0].width, "selected region width")
        assertEquals(30, translations[0].height)
    }

    @Test
    fun `enter does nothing while translate is disabled`() {
        val translations = mutableListOf<BufferedImage>()
        val panel = renderedPanel(selection = null) { translations.add(it) }
        val rootPane = dialogSeam(panel)
        assertFalse(panel.translateButton.isEnabled)

        fireEnter(rootPane)

        assertEquals(0, translations.size, "disabled default button fired")
    }
}
