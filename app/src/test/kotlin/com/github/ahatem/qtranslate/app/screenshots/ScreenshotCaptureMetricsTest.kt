package com.github.ahatem.qtranslate.app.screenshots

import java.awt.Font
import java.awt.image.BufferedImage
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Offscreen capture must measure text the way the component being captured does.
 *
 * This is the regression pin for the clipped Settings headings in the screenshot gallery. Swing
 * sizes a label from the metrics of the screen's graphics; if the capture graphics resolves glyph
 * advances more widely, the painted run is truncated at the label's bounds. The invariant is
 * cheap to state and was violated by several pixels before [captureGraphics] copied the platform
 * text hints.
 *
 * On a headless runner both sides fall back to the same integer metrics, so the test passes there
 * without exercising the hints; on a desktop it fails when they stop being applied.
 */
class ScreenshotCaptureMetricsTest {

    @Test
    fun `capture graphics resolve text at the same advance as the label`() {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val graphics = captureGraphics(image)
        try {
            listOf(
                13 to "Startup & Updates",
                13 to "Wikimedia Reference",
                13 to "Keyboard & Hotkeys",
                20 to "Close Button Behaviour",
                26 to "Service selector style",
            ).forEach { (size, text) ->
                val font = Font(Font.SANS_SERIF, Font.BOLD, size)
                val label = JLabel(text).apply { this.font = font }
                val onScreen = label.getFontMetrics(font).stringWidth(text)
                val onCapture = font.getStringBounds(text, graphics.fontRenderContext).width

                assertEquals(
                    onScreen.toDouble(),
                    onCapture,
                    0.5,
                    "size $size \"$text\": the capture graphics advances $onCapture px where the " +
                        "label was laid out for $onScreen px, which clips its last glyph",
                )
            }
        } finally {
            graphics.dispose()
        }
    }
}
