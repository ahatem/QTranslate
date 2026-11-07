package com.github.ahatem.qtranslate.ui.swing.ocr

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.SwingUtilities

class OcrTranslationControlsTest {
    @Test
    fun `translate remains connected when controls wrap and return inline`() {
        SwingUtilities.invokeAndWait {
            var calls = 0
            val button = JButton("Translate").apply { addActionListener { calls++ } }
            val controls = OcrTranslationControls(JComboBox(arrayOf("Auto")), JComboBox(arrayOf("English")), button, false)
            controls.adaptToWidth(100)
            assertTrue(controls.isActionWrapped)
            button.doClick()
            controls.adaptToWidth(2000)
            assertFalse(controls.isActionWrapped)
            button.doClick()
            assertEquals(2, calls)
        }
    }
}
