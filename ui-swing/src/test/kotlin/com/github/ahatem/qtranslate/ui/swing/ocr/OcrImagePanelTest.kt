package com.github.ahatem.qtranslate.ui.swing.ocr

import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OcrImagePanelTest {
    @Test
    fun `fit limits enlargement and manual zoom survives resize`() {
        SwingUtilities.invokeAndWait {
            val panel = OcrImagePanel()
            panel.setImage(BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB))
            panel.fitToWindow(Dimension(1000, 800))
            assertEquals(1.5, panel.zoomScale())
            panel.zoomIn()
            val manual = panel.zoomScale()
            panel.refitIfFitted(Dimension(200, 100))
            assertEquals(manual, panel.zoomScale())
            panel.actualSize()
            assertEquals(1.0, panel.zoomScale())
        }
    }

    @Test
    fun `click maps centered image coordinates to region`() {
        SwingUtilities.invokeAndWait {
            val panel = OcrImagePanel()
            panel.setImage(BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB))
            panel.actualSize()
            panel.size = Dimension(300, 200)
            panel.setRegions(listOf(OCRTextRegion("word", 10, 10, 40, 40)))
            panel.setAnnotationsVisible(true)
            var clicked: Int? = null
            panel.onRegionClicked = { clicked = it }
            panel.dispatchEvent(MouseEvent(panel, MouseEvent.MOUSE_CLICKED, 0, 0, 120, 70, 1, false, MouseEvent.BUTTON1))
            assertEquals(0, clicked)
            clicked = null
            panel.dispatchEvent(MouseEvent(panel, MouseEvent.MOUSE_CLICKED, 0, 0, 20, 20, 1, false, MouseEvent.BUTTON1))
            assertNull(clicked)
        }
    }
}
