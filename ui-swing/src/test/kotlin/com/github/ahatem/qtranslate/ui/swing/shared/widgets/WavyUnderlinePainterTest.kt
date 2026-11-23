package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JScrollPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The painter caches the wave strip; it must never replay a stale colour or scale, and selection drags must not corrupt it. */
class WavyUnderlinePainterTest {

    private var waveColor: Color = Color.RED
    private lateinit var painter: WavyUnderlineHighlighter.WavyUnderlinePainter

    private fun newPane(text: String): AdvancedTextPane = onEdt {
        painter = WavyUnderlineHighlighter.WavyUnderlinePainter { waveColor }
        AdvancedTextPane({}, {}, {}).also {
            it.font = it.font.deriveFont(14f)
            it.size = Dimension(700, 900)
            it.render(text, emptyList(), true)
            JScrollPane(it).apply { setSize(700, 900); doLayout() }
            it.doLayout()
        }
    }

    private fun paintPane(pane: AdvancedTextPane, scale: Double = 1.0): BufferedImage {
        val image = BufferedImage(700, 900, BufferedImage.TYPE_INT_ARGB)
        onEdt {
            // An opaque white ground so the wave color reads back unambiguously.
            val g = image.createGraphics()
            try {
                g.color = Color.WHITE
                g.fillRect(0, 0, 700, 900)
                if (scale != 1.0) g.scale(scale, scale)
                pane.paint(g)
            } finally {
                g.dispose()
            }
        }
        return image
    }

    private fun highlight(pane: AdvancedTextPane, start: Int, end: Int) {
        onEdt { pane.highlighter.addHighlight(start, end, painter) }
    }

    private fun countPixels(image: BufferedImage, matches: (r: Int, g: Int, b: Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                if (matches((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)) n++
            }
        }
        return n
    }

    private fun isRed(r: Int, g: Int, b: Int) = r > 180 && g < 150 && b < 150
    private fun isBlue(r: Int, g: Int, b: Int) = b > 180 && r < 150 && g < 150

    @Test
    fun `a theme color change re-renders instead of replaying the old color`() {
        val pane = newPane("the quick brown fox jumps over the lazy dog")
        highlight(pane, 4, 9)

        val redImage = paintPane(pane)
        assertTrue(countPixels(redImage, ::isRed) > 0, "the red underline must be painted")
        assertEquals(0, countPixels(redImage, ::isBlue))

        waveColor = Color.BLUE
        val blueImage = paintPane(pane)
        assertTrue(countPixels(blueImage, ::isBlue) > 0, "the blue underline must be painted")
        assertEquals(0, countPixels(blueImage, ::isRed), "no stale red pixels may survive the theme change")
    }

    @Test
    fun `a device scale change paints a larger wave, and returning to a scale paints the same pixels`() {
        val pane = newPane("the quick brown fox jumps over the lazy dog")
        highlight(pane, 4, 9)

        val atTwo = paintPane(pane, scale = 2.0)
        val atOne = paintPane(pane, scale = 1.0)
        val atTwoAgain = paintPane(pane, scale = 2.0)

        assertTrue(countPixels(atOne, ::isRed) > 0, "the underline must be painted at 100%")
        assertTrue(
            countPixels(atTwo, ::isRed) > countPixels(atOne, ::isRed),
            "a doubled device scale must paint a larger wave, not replay the 100% strip"
        )
        for (y in 0 until atTwo.height) {
            for (x in 0 until atTwo.width) {
                assertEquals(atTwo.getRGB(x, y), atTwoAgain.getRGB(x, y), "pixel ($x, $y) changed after a scale round trip")
            }
        }
    }

    /**
     * A drag-like sweep across corrected text: stepwise caret moves with a paint
     * after each, forward and backward, in both editable and read-only mode.
     * Guards that cached underlines never corrupt the selection rendering.
     */
    @Test
    fun `drag-like selection across corrected text keeps selection and underlines intact`() {
        for (editable in listOf(true, false)) {
            val text = (0 until 30).joinToString("\n") { p -> "Paragraph $p with a few words to select" }
            val pane = newPane(text)
            onEdt {
                var offset = 10
                repeat(30) {
                    pane.highlighter.addHighlight(offset, offset + 5, painter)
                    offset = (offset + 137) % (text.length - 6)
                }
                pane.render(text, emptyList(), editable)
            }

            val image = BufferedImage(700, 900, BufferedImage.TYPE_INT_ARGB)
            onEdt {
                val caret = pane.caret
                caret.setDot(0)
                var pos = 0
                while (pos < text.length) {
                    pos = (pos + 173).coerceAtMost(text.length)
                    caret.moveDot(pos)
                    val g = image.createGraphics()
                    try {
                        g.color = Color.WHITE
                        g.fillRect(0, 0, 700, 900)
                        pane.paint(g)
                    } finally {
                        g.dispose()
                    }
                }
                // Backward sweep, then collapse and select-all.
                while (pos > 0) {
                    pos = (pos - 211).coerceAtLeast(0)
                    caret.moveDot(pos)
                }
                caret.setDot(0)
                pane.selectAll()
            }

            assertEquals(text, onEdt { pane.text }, "editable=$editable: drag must not mutate text")
            assertEquals(0 to text.length, onEdt { pane.selectionStart to pane.selectionEnd })
            val final = paintPane(pane)
            assertTrue(
                countPixels(final, ::isRed) > 0,
                "editable=$editable: underlines must survive drag-like selection",
            )
        }
    }
}
