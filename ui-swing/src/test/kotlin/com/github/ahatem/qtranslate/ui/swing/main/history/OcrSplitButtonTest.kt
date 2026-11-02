package com.github.ahatem.qtranslate.ui.swing.main.history

import com.github.ahatem.qtranslate.core.ocr.OcrWorkflowCommand
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.UIManager
import javax.swing.Icon
import java.awt.Component
import java.awt.Graphics
import javax.swing.JMenuItem
import javax.swing.SwingUtilities

class OcrSplitButtonTest {
    @Test
    fun `arrow adopts the current look and feel icon after a theme update`() {
        SwingUtilities.invokeAndWait {
            val previous = UIManager.get("Table.descendingSortIcon")
            fun icon(width: Int) = object : Icon {
                override fun getIconWidth() = width
                override fun getIconHeight() = 8
                override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) = Unit
            }
            try {
                val first = icon(8)
                UIManager.put("Table.descendingSortIcon", first)
                val control = OcrSplitButton({}, {})
                assertEquals(8, control.arrow.icon.iconWidth)
                val second = icon(13)
                UIManager.put("Table.descendingSortIcon", second)
                control.arrow.updateUI()
                assertEquals(13, control.arrow.icon.iconWidth)
            } finally {
                UIManager.put("Table.descendingSortIcon", previous)
            }
        }
    }

    @Test
    fun `primary and overrides dispatch canonical commands without changing the default`() {
        SwingUtilities.invokeAndWait {
            val commands = mutableListOf<OcrWorkflowCommand>()
            var settingsOpened = 0
            val control = OcrSplitButton(commands::add) { settingsOpened++ }
            val labels = OcrSplitLabels("Default: Copy text", "Choose action", "Actions", "Translate", "Capture and translate", "Text", "Image", "Workspace", "Settings")
            control.render(labels, true)
            val key = control.primary.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
            assertNotNull(key)
            control.primary.actionMap.get(key).actionPerformed(ActionEvent(control.primary, ActionEvent.ACTION_PERFORMED, "enter"))
            control.arrow.doClick() // Merely opening a menu must not start capture.
            assertEquals(listOf(OcrWorkflowCommand.DEFAULT), commands)
            val menu = control.menu()
            (0..4).forEach { (menu.getComponent(it) as JMenuItem).doClick() }
            assertEquals(listOf(OcrWorkflowCommand.DEFAULT, OcrWorkflowCommand.SHOW_ACTIONS,
                OcrWorkflowCommand.TRANSLATE, OcrWorkflowCommand.COPY_TEXT, OcrWorkflowCommand.COPY_IMAGE,
                OcrWorkflowCommand.OPEN_WORKSPACE), commands)
            (menu.getComponent(6) as JMenuItem).doClick()
            assertEquals(1, settingsOpened)
            assertEquals("Default: Copy text", control.primary.toolTipText)
            control.render(labels.copy(defaultTooltip = "Default: Workspace"), true)
            control.primary.doClick()
            assertEquals(OcrWorkflowCommand.DEFAULT, commands.last())
            assertEquals("Default: Workspace", control.primary.toolTipText)
            control.render(labels, false)
            control.primary.doClick()
            assertEquals(7, commands.size)
        }
    }
}
