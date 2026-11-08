package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.PortableFallbackFonts
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.swing.JScrollPane
import javax.swing.text.DefaultCaret
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Text has to be drawn with the same metrics it was laid out with.
 *
 * The text UI lays a row out through the component's own `FontMetrics`, which uses integer advances.
 * A component that turns `FRACTIONALMETRICS` on only while painting makes the glyphs advance by
 * fractional amounts instead. The two policies disagree by a fraction of a pixel per glyph, and the
 * difference accumulates along a line, so the drawn text ends up past the measured span and past the
 * edge of the pane (#373).
 *
 * The same disagreement is visible two other ways, and both were reported together (#359): a selection
 * is painted as separate draws for the selected and unselected parts, each starting at the advance the
 * layout reported, so text after the selection jumps when the selection appears; and the caret, whose
 * position comes from the layout, no longer sits on the character boundary that was painted.
 *
 * The invariants asserted here are behavioural: how far the painted ink reaches past the start of its
 * row has to stay within a small constant of the span the layout reported for that row; selecting a
 * range must not move a painted pixel; and the caret has to stand on the boundary the glyphs were
 * painted at. Nothing here asserts which rendering hints the component sets.
 *
 * The face is the logical `Serif` family rather than a named font file, because a named face such as
 * Times New Roman is not present on every CI machine and this has to run everywhere. The real glyph
 * advances therefore differ per platform, which is what the allowance covers.
 */
class TextPaintLayoutAgreementTest {

    /**
     * Headroom for the last glyph's right side bearing and for antialiasing, which differ between the
     * faces the logical `Serif` family resolves to on different platforms.
     *
     * That is a constant per row. The drift this guards against is per glyph and grows along the
     * line, so the two are well separated.
     */
    private val allowance = 4.0

    /** How far past the laid out span to look, independent of the allowance being asserted. */
    private val searchMargin = 40.0

    private val english =
        "The quick brown fox jumps over the lazy dog and keeps running for a long while"

    /**
     * The Latin is drawn by the primary face and the script by the bundled rescue face, so the row
     * mixes two faces that advance differently. This is the shape of a real fallback row, unlike a
     * script no face in the chain covers, which paints as missing-glyph boxes.
     */
    private val fallbackRow = "Hello ภาษาไทย wrapping words here and more English today"

    private fun pane(text: String, size: Int, width: Int, fallback: String): AdvancedTextPane {
        val pane = ShapedText.onEdt {
            AdvancedTextPane(onTextChanged = {}, onTranslateRequest = {}, onListenRequest = {})
        }
        ShapedText.onEdt {
            JScrollPane(pane)
            pane.render(text, emptyList(), isEditable = true)
            pane.updateFontsAndRescanDocument(
                Font("Serif", Font.PLAIN, size),
                Font(fallback, Font.PLAIN, size)
            )
            pane.setSize(width, 300)
        }
        awaitFontRuns(pane)
        return pane
    }

    /** The font-fallback pass rewrites runs on a timer; wait until it has stopped, as the harness does. */
    private fun awaitFontRuns(pane: AdvancedTextPane, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        var stable = 0
        while (System.currentTimeMillis() < deadline && stable < 5) {
            Thread.sleep(20)
            val runs = ShapedText.onEdt { ShapedText.runs(pane).joinToString() }
            stable = if (runs == last) stable + 1 else 0
            last = runs
        }
    }

    /** Paints the pane the way the app does, so the text UI answers geometry at all. */
    private fun painted(pane: AdvancedTextPane): BufferedImage {
        val image = BufferedImage(maxOf(1, pane.width), maxOf(1, pane.height), BufferedImage.TYPE_INT_ARGB)
        ShapedText.onEdt {
            val g = image.createGraphics() as Graphics2D
            g.color = Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            pane.paint(g)
            g.dispose()
        }
        return image
    }

    /** Offsets that begin a laid out row. */
    private fun rowStarts(pane: AdvancedTextPane): List<Int> {
        val n = pane.document.length
        val ys = (0..n).map { ShapedText.onEdt { runCatching { pane.modelToView2D(it)?.bounds2D?.y }.getOrNull() } }
        return (0..n).filter { it == 0 || ys[it] != ys[it - 1] }
    }

    /**
     * The rightmost ink column belonging to one row.
     *
     * Scanned only across that row's own vertical band, and horizontally only from just before its
     * first glyph to a fixed distance past its laid out span, so the caret, the text border, the
     * character counter and the next row's ascenders cannot leak into the measurement.
     */
    private fun rightmostRowInk(
        image: BufferedImage,
        rowLeft: Int,
        rowTop: Int,
        rowBottom: Int,
        searchRight: Int
    ): Int {
        var right = rowLeft
        for (y in maxOf(0, rowTop) until minOf(image.height, rowBottom)) {
            for (x in maxOf(0, rowLeft - 2) until minOf(image.width, searchRight)) {
                if (image.getRGB(x, y) != Color.WHITE.rgb) {
                    if (x > right) right = x
                }
            }
        }
        return right
    }

    private fun assertRowsAgree(pane: AdvancedTextPane, image: BufferedImage, label: String) {
        val n = pane.document.length
        val starts = rowStarts(pane)

        var checked = 0
        var sawInk = false
        var widest = 0.0

        starts.forEachIndexed { index, start ->
            val end = starts.getOrNull(index + 1)?.minus(1) ?: n
            val xs = (start..end).mapNotNull {
                ShapedText.onEdt { runCatching { pane.modelToView2D(it)?.bounds2D?.x }.getOrNull() }
            }
            // Too short for accumulated drift to say anything.
            if (xs.size < 8) return@forEachIndexed
            val rowLeft = xs.min()
            val span = xs.max() - rowLeft

            val y = ShapedText.onEdt { runCatching { pane.modelToView2D(start)?.bounds2D?.y }.getOrNull() }
                ?: return@forEachIndexed
            val height = ShapedText.onEdt { runCatching { pane.modelToView2D(start)?.height }.getOrNull() }
                ?: return@forEachIndexed

            val inkRight = rightmostRowInk(
                image,
                rowLeft = rowLeft.toInt(),
                rowTop = y.toInt(),
                rowBottom = (y + height).toInt(),
                searchRight = (rowLeft + span + searchMargin).toInt()
            )

            checked++
            if (inkRight > rowLeft) sawInk = true
            if (span > widest) widest = span

            assertTrue(
                inkRight <= rowLeft + span + allowance,
                "$label row $index: painted ink reaches x=$inkRight, but the row starts at $rowLeft and " +
                    "was laid out $span px wide, so it is painted past its own layout by more than the " +
                    "$allowance px that a glyph's side bearing and antialiasing can account for"
            )
        }

        // Otherwise a pane that laid out one short row, or painted nothing at all, would pass by
        // measuring nothing.
        assertTrue(checked > 0, "$label: no row was long enough to say anything about accumulated drift")
        assertTrue(sawInk, "$label: the pane painted no ink, so the comparison proves nothing")
        assertTrue(
            widest > 100.0,
            "$label: the widest row was only $widest px, too short to show drift along a line"
        )
    }

    @Test
    fun `painted text agrees with the layout that positioned it`() {
        val pane = pane(english, size = 15, width = 280, fallback = "Serif")
        assertRowsAgree(pane, painted(pane), "serif 15")
    }

    @Test
    fun `painted text agrees with the layout at a larger size`() {
        val pane = pane(english, size = 19, width = 320, fallback = "Serif")
        assertRowsAgree(pane, painted(pane), "serif 19")
    }

    @Test
    fun `painted text agrees with the layout on a row that mixes two faces`() {
        NotoNaskhArabicFont.install()
        // The Arabic fallback cannot draw Thai, so the script has to land in the bundled face. If that
        // stops happening this test would quietly stop covering a two-faced row.
        val pane = pane(fallbackRow, size = 15, width = 300, fallback = NotoNaskhArabicFont.FAMILY)

        val doc = ShapedText.onEdt { pane.styledDocument }
        val families = (0 until doc.length)
            .map { StyleConstants.getFontFamily(doc.getCharacterElement(it).attributes) }
            .toSet()
        assertEquals(2, families.size, "one face for the Latin and one for the script: $families")
        assertTrue(PortableFallbackFonts.THAI in families, "the bundled Thai face draws the script")

        assertRowsAgree(pane, painted(pane), "serif 15 with a Thai run")
    }

    /** The pane painted the way the application paints it, with a range selected. */
    private fun paintedSelected(pane: AdvancedTextPane, from: Int, to: Int): BufferedImage {
        ShapedText.onEdt {
            // A selection is only painted while the caret considers it visible, which normally
            // happens when the pane gains focus; an offscreen pane never does.
            (pane.caret as DefaultCaret).setSelectionVisible(true)
            // Distinct from the foreground so Swing paints the selected and unselected parts as two
            // draws, which is where a metrics mismatch between them shows up. The colours themselves
            // are irrelevant: the comparison is on where the ink is, not on what colour it is.
            pane.selectionColor = Color.WHITE
            pane.selectedTextColor = Color.RED
            pane.select(from, to)
        }
        return painted(pane).also {
            ShapedText.onEdt {
                pane.select(0, 0)
                (pane.caret as DefaultCaret).setSelectionVisible(false)
            }
        }
    }

    /** Whether the same pixel was inked in both images. */
    private fun sameInk(a: BufferedImage, b: BufferedImage, x: Int, y: Int): Boolean =
        (a.getRGB(x, y) != Color.WHITE.rgb) == (b.getRGB(x, y) != Color.WHITE.rgb)

    @Test
    fun `selecting text does not move it`() {
        // The reported symptom: text shifts slightly when the selection changes. With one metrics
        // policy for layout and painting the selected and unselected parts are drawn from advances
        // the layout agrees with, so a selection can only change colours.
        val cases = listOf(
            "serif 15" to pane(english, size = 15, width = 280, fallback = "Serif"),
            "serif 19" to pane(english, size = 19, width = 320, fallback = "Serif"),
            "serif 19 with a Thai run" to pane(fallbackRow, size = 19, width = 340, fallback = NotoNaskhArabicFont.FAMILY),
        )
        cases.forEach { (label, pane) ->
            val plain = painted(pane)
            val length = ShapedText.onEdt { pane.document.length }
            // A range in the middle of a row, a range crossing a row, and a range from the start.
            listOf(1 to 2, 5 to 6, 0 to 12, 7 to 19, 0 to length / 2).forEach { (from, to) ->
                if (to > length) return@forEach
                val selected = paintedSelected(pane, from, to)
                var differing = 0
                var first: String? = null
                for (y in 0 until plain.height) {
                    for (x in 0 until plain.width) {
                        if (!sameInk(plain, selected, x, y)) {
                            differing++
                            if (first == null) first = "($x, $y)"
                        }
                    }
                }
                assertEquals(
                    0,
                    differing,
                    "$label: selecting [$from, $to) moved painted text, first at $first",
                )
            }
        }
    }

    @Test
    fun `the caret stands on the boundary the glyphs were painted at`() {
        // The other half of the report: caret positions that do not line up with the characters.
        // The caret comes from the layout and the ink comes from painting, so the two only meet
        // while both advance by the same amounts. Measured at the end of each row, where any
        // disagreement has accumulated over a whole line, in both directions: painted wider than
        // the caret (ink past it) and painted narrower (ink short of it).
        listOf(
            "serif 15" to pane(english, size = 15, width = 280, fallback = "Serif"),
            "serif 19" to pane(english, size = 19, width = 320, fallback = "Serif"),
        ).forEach { (label, pane) ->
            val image = painted(pane)
            val text = ShapedText.onEdt { pane.document.getText(0, pane.document.length) }
            val starts = rowStarts(pane)
            var checked = 0

            starts.forEachIndexed { index, start ->
                val end = starts.getOrNull(index + 1)?.minus(1) ?: text.length
                // A row ends at a space or a break, neither of which is painted, so the last drawn
                // character is what the ink can actually be compared against.
                val lastLetter = (end - 1 downTo start).firstOrNull { text[it].isLetter() }
                    ?: return@forEachIndexed
                val caret = ShapedText.onEdt { runCatching { pane.modelToView2D(lastLetter + 1)?.bounds2D?.x }.getOrNull() }
                    ?: return@forEachIndexed
                val y = ShapedText.onEdt { runCatching { pane.modelToView2D(lastLetter)?.bounds2D?.y }.getOrNull() }
                    ?: return@forEachIndexed
                val height = ShapedText.onEdt { runCatching { pane.modelToView2D(lastLetter)?.height }.getOrNull() }
                    ?: return@forEachIndexed
                val rowLeft = ShapedText.onEdt { runCatching { pane.modelToView2D(start)?.bounds2D?.x }.getOrNull() }
                    ?: return@forEachIndexed

                val inkRight = rightmostRowInk(
                    image,
                    rowLeft = rowLeft.toInt(),
                    rowTop = y.toInt(),
                    rowBottom = (y + height).toInt(),
                    searchRight = (caret + searchMargin).toInt(),
                )
                checked++
                assertTrue(
                    Math.abs(inkRight - caret) <= allowance,
                    "$label row $index: the last drawn character ends at x=$inkRight, but the caret position " +
                        "after it is x=$caret, ${Math.abs(inkRight - caret)} px apart",
                )
            }

            assertTrue(checked > 0, "$label: no row had a drawn character to compare against the caret")
        }
    }
}
