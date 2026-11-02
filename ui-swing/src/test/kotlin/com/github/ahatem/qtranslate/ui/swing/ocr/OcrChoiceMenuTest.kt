package com.github.ahatem.qtranslate.ui.swing.ocr

import javax.swing.JComboBox
import javax.swing.JRadioButtonMenuItem
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OcrChoiceMenuTest {
    @Test
    fun `menu uses selector model and choosing an item fires the existing listener`() {
        SwingUtilities.invokeAndWait {
            val selector = JComboBox(arrayOf("Automatic", "Japanese"))
            val changes = mutableListOf<String>()
            selector.addActionListener { changes += selector.selectedItem as String }
            val menu = ocrChoiceMenu("Language", selector) { it }
            assertTrue((menu.getItem(0) as JRadioButtonMenuItem).isSelected)
            menu.getItem(1).doClick()
            assertEquals("Japanese", selector.selectedItem)
            assertEquals(listOf("Japanese"), changes)
            assertTrue((ocrChoiceMenu("Language", selector) { it }.getItem(1) as JRadioButtonMenuItem).isSelected)
            selector.isEnabled = false
            assertFalse(ocrChoiceMenu("Language", selector) { it }.isEnabled)
        }
    }
}
