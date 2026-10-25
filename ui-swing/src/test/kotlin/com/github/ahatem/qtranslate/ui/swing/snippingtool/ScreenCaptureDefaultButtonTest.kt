package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.BorderLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers #364: the capture dialog's primary Translate action must be reachable
 * with Enter, without disturbing Escape, Space-on-focused-button, or the
 * button's disabled state while no valid selection exists.
 *
 * [ScreenCapturePanel] is a plain `JLayeredPane` and the default-button seam is
 * the root pane's standard Swing mechanism, so everything here runs headless —
 * the dialog itself (a modal `JDialog` that grabs a screen capture) is never
 * constructed. Enter is fired exactly as Swing would: the root pane UI (Basic,
 * which FlatLaf's extends) binds Enter in the WHEN_IN_FOCUSED_WINDOW input map
 * to an action that clicks the default button, provided the button actually
 * belongs to that root pane — so the panel is mounted into a bare [JRootPane]
 * the same way the dialog mounts it.
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
    fun `translate button is exposed as the panel's primary action`() {
        val panel = renderedPanel(Rectangle(10, 10, 40, 30))
        assertEquals("Translate", panel.translateButton.text)
    }

    @Test
    fun `default button is the panel's translate button`() {
        val panel = renderedPanel(Rectangle(10, 10, 40, 30))
        val rootPane = dialogSeam(panel)
        assertSame(panel.translateButton, rootPane.defaultButton, "Translate must be the default button")
    }

    @Test
    fun `translate enables only for a valid selection and disables again on reset`() {
        val panel = ScreenCapturePanel(
            onTranslate = {}, onCopyText = {}, onCopyImage = {}, onSaveImage = {},
            onRecrop = {}, onCancel = {},
        )
        val screenshot = BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB)

        panel.render(ScreenCaptureState(screenshot = screenshot))
        assertFalse(panel.translateButton.isEnabled, "no selection yet — Translate must stay disabled")

        // Mid-drag zero-size selection is not a valid region either.
        panel.render(
            ScreenCaptureState(
                screenshot = screenshot,
                selection = Rectangle(10, 10, 0, 0),
                mode = CaptureMode.SELECTING,
            )
        )
        assertFalse(panel.translateButton.isEnabled, "a zero-size drag must not enable Translate")

        panel.render(
            ScreenCaptureState(
                screenshot = screenshot,
                selection = Rectangle(10, 10, 40, 30),
                mode = CaptureMode.SELECTED,
                showButtons = true,
            )
        )
        assertTrue(panel.translateButton.isEnabled, "a real selection enables the primary action")

        // Re-crop resets to idle: the controller clears the selection.
        panel.render(ScreenCaptureState(screenshot = screenshot, mode = CaptureMode.IDLE))
        assertFalse(panel.translateButton.isEnabled, "re-crop disables the primary action again")
    }

    @Test
    fun `enter on the root pane invokes translate exactly once when enabled`() {
        val translations = mutableListOf<BufferedImage>()
        val panel = renderedPanel(Rectangle(10, 10, 40, 30)) { translations.add(it) }
        val rootPane = dialogSeam(panel)

        fireEnter(rootPane)

        assertEquals(1, translations.size, "Enter must fire the primary action exactly once")
        assertEquals(40, translations[0].width, "the fired action carries the selected region")
        assertEquals(30, translations[0].height)
    }

    @Test
    fun `repeated enter fires translate once per press`() {
        val translations = mutableListOf<BufferedImage>()
        val panel = renderedPanel(Rectangle(10, 10, 40, 30)) { translations.add(it) }
        val rootPane = dialogSeam(panel)

        fireEnter(rootPane)
        fireEnter(rootPane)

        assertEquals(2, translations.size, "each Enter press fires exactly one action")
    }

    @Test
    fun `enter does nothing while translate is disabled`() {
        val translations = mutableListOf<BufferedImage>()
        val panel = renderedPanel(selection = null) { translations.add(it) }
        val rootPane = dialogSeam(panel)
        assertFalse(panel.translateButton.isEnabled)

        fireEnter(rootPane)

        assertEquals(0, translations.size, "a disabled default button must not fire")
    }

    @Test
    fun `space still activates a focused secondary button`() {
        // Simulates the toolbar's secondary buttons (Copy Text, Save Image, …):
        // their behavior is the plain Swing focused-button contract, which the
        // default-button wiring must not touch. Swing fires a button's action on
        // the Space release, driven by the button's own WHEN_FOCUSED bindings.
        val clicks = mutableListOf<String>()
        val secondary = JButton("Copy Text").apply {
            updateUI()
            addActionListener { clicks.add("copy-text") }
        }

        val inputMap = secondary.getInputMap(JComponent.WHEN_FOCUSED)
        val pressKey = inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false))
            ?: error("a focused button answers Space press through its own binding")
        val releaseKey = inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true))
            ?: error("a focused button answers Space release through its own binding")

        secondary.actionMap.get(pressKey)!!
            .actionPerformed(ActionEvent(secondary, ActionEvent.ACTION_PERFORMED, "pressed"))
        secondary.actionMap.get(releaseKey)!!
            .actionPerformed(ActionEvent(secondary, ActionEvent.ACTION_PERFORMED, "released"))

        assertEquals(1, clicks.size, "Space activates the focused secondary button exactly once")
        assertEquals("copy-text", clicks[0])
    }

    @Test
    fun `the capture panel installs no competing escape binding`() {
        // The Escape binding lives on the dialog's root pane
        // (SnippingToolDialog.setupListeners) and #364 must not touch it; this
        // pins that the panel itself installs no WHEN_IN_FOCUSED_WINDOW Escape
        // entry that could shadow the dialog's binding.
        val panel = renderedPanel(Rectangle(10, 10, 40, 30))
        val escapeInPanel = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))
        assertEquals(null, escapeInPanel, "the panel must not shadow the dialog's Escape binding")
    }
}
