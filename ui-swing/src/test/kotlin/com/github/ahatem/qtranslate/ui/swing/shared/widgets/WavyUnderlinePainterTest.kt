package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression suite for [WavyUnderlineHighlighter.WavyUnderlinePainter].
 *
 * Selection dragging repaints the selected block on every mouse event, and each
 * repaint re-invokes the painter for every correction in the region. The painter
 * therefore caches the rendered wave strip and must re-render — never go stale —
 * whenever the area, the theme color or the device scale changes. Deterministic
 * and bound to the event dispatch thread through [onEdt]; no timing thresholds.
 */
class WavyUnderlinePainterTest {

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

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
        // The font-fallback pass is batched; it only writes attributes, which never
        // changes the underline geometry asserted here.
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
    fun `repainting an unchanged underline reuses the cached strip`() {
        val pane = newPane("the quick brown fox jumps over the lazy dog")
        highlight(pane, 4, 9)

        paintPane(pane)
        assertEquals(1, onEdt { painter.cachedStripCount() })

        paintPane(pane)
        assertEquals(1, onEdt { painter.cachedStripCount() }, "a second identical paint must not render again")
    }

    @Test
    fun `each distinct underline area renders once`() {
        val pane = newPane("the quick brown fox jumps over the lazy dog")
        highlight(pane, 4, 9)
        highlight(pane, 16, 19)

        paintPane(pane)
        assertEquals(2, onEdt { painter.cachedStripCount() })

        paintPane(pane)
        assertEquals(2, onEdt { painter.cachedStripCount() })
    }

    @Test
    fun `a moved underline re-renders at its new area`() {
        val pane = newPane("the quick brown fox jumps over the lazy dog")
        highlight(pane, 4, 9)
        paintPane(pane)
        assertEquals(1, onEdt { painter.cachedStripCount() })

        onEdt { pane.highlighter.removeAllHighlights() }
        highlight(pane, 16, 19)
        val image = paintPane(pane)
        assertEquals(2, onEdt { painter.cachedStripCount() })

        // The wave follows the highlight: red pixels where the new range sits.
        assertTrue(countPixels(image, ::isRed) > 0, "the moved underline must be painted")
    }

    @Test
    fun `a theme color change re-renders instead of replaying the old color`() {
        val pane = newPane("the quick brown fox jumps over the lazy dog")
        highlight(pane, 4, 9)

        val redImage = paintPane(pane)
        assertTrue(countPixels(redImage, ::isRed) > 0, "the red underline must be painted")
        assertEquals(0, countPixels(redImage, ::isBlue))

        waveColor = Color.BLUE
        val blueImage = paintPane(pane)
        assertEquals(
            2, onEdt { painter.cachedStripCount() },
            "the new theme color must render, not reuse the red strip"
        )
        assertTrue(countPixels(blueImage, ::isBlue) > 0, "the blue underline must be painted")
        assertEquals(0, countPixels(blueImage, ::isRed), "no stale red pixels may survive the theme change")
    }

    @Test
    fun `a device scale change re-renders instead of replaying`() {
        val pane = newPane("the quick brown fox jumps over the lazy dog")
        highlight(pane, 4, 9)

        paintPane(pane, scale = 1.0)
        assertEquals(1, onEdt { painter.cachedStripCount() })

        paintPane(pane, scale = 2.0)
        assertEquals(2, onEdt { painter.cachedStripCount() }, "a new device scale must render, not replay")

        paintPane(pane, scale = 2.0)
        assertEquals(2, onEdt { painter.cachedStripCount() })
    }

    @Test
    fun `the cache stays bounded no matter how many underlines are painted`() {        val text = (0 until 400).joinToString(" ") { "word$it" }
        val pane = newPane(text)
        onEdt {
            var offset = 0
            var added = 0
            while (added < 300 && offset + 5 < text.length) {
                pane.highlighter.addHighlight(offset, offset + 5, painter)
                offset += 37
                added++
            }
        }

        paintPane(pane)
        assertTrue(
            onEdt { painter.cachedStripCount() } <= 256,
            "entries must be evicted past the cap, got ${onEdt { painter.cachedStripCount() }}"
        )
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
