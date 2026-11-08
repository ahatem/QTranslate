package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.CaretGeometry
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.InsertionDirection
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.swing.text.AbstractDocument
import javax.swing.text.DefaultCaret
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.Position
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The caret has to say which way the text at the insertion point runs.
 *
 * Where a document holds both directions, the caret rectangle alone says where the insertion point is
 * but not which direction what is typed there will take. Swing's own caret draws a small triangle on
 * the side the insertion runs towards; using a custom caret replaced that painting with a rectangle
 * and the mark went with it (#378).
 *
 * The direction is Swing's answer, not a second opinion: [InsertionDirection] reads the same bidi
 * structure the text UI lays the text out from, so the mark cannot disagree with the text beside it.
 * Documents with one direction get no mark at all, which is what keeps an ordinary caret unchanged.
 */
class InsertionDirectionTest {

    private fun document(text: String) = DefaultStyledDocument().apply { insertString(0, text, null) }

    private fun directions(text: String): List<Boolean?> {
        val doc = document(text)
        return (0..doc.length).map { InsertionDirection.at(doc, it, Position.Bias.Forward) }
    }

    @Test
    fun `text in one direction has no ambiguous insertion point`() {
        // Nothing to indicate: every insertion point in a single-direction document has one answer.
        assertEquals(listOf<Boolean?>(null), directions("Hello world").distinct(), "left to right")
        assertEquals(listOf<Boolean?>(null), directions("مرحبا بالعالم").distinct(), "right to left")
        assertNull(InsertionDirection.at(document(""), 0, Position.Bias.Forward), "an empty document")
    }

    @Test
    fun `a left to right paragraph reports the direction of the character at the caret`() {
        val doc = document("Hello مرحبا world")
        (0..5).forEach { dot ->
            assertEquals(true, InsertionDirection.at(doc, dot, Position.Bias.Forward), "Latin at $dot")
        }
        (6..10).forEach { dot ->
            assertEquals(false, InsertionDirection.at(doc, dot, Position.Bias.Forward), "Arabic at $dot")
        }
        (11..17).forEach { dot ->
            assertEquals(true, InsertionDirection.at(doc, dot, Position.Bias.Forward), "Latin after at $dot")
        }
    }

    @Test
    fun `a right to left paragraph reports the direction of the character at the caret`() {
        val text = "مرحبا hello مرحبا"
        val doc = document(text)
        (0..5).forEach { dot ->
            assertEquals(false, InsertionDirection.at(doc, dot, Position.Bias.Forward), "Arabic at $dot")
        }
        (6..10).forEach { dot ->
            assertEquals(true, InsertionDirection.at(doc, dot, Position.Bias.Forward), "Latin at $dot")
        }
        (12..17).forEach { dot ->
            assertEquals(false, InsertionDirection.at(doc, dot, Position.Bias.Forward), "Arabic after at $dot")
        }
    }

    @Test
    fun `at a boundary between two directions the caret's own bias decides`() {
        // The case the mark exists for: the same offset, two legitimate answers, and the caret's bias
        // is what says which one applies - exactly as Swing's caret decides it.
        val doc = document("Hello مرحبا world")
        val boundary = "Hello ".length
        assertEquals(false, InsertionDirection.at(doc, boundary, Position.Bias.Forward), "leaning into the Arabic")
        assertEquals(true, InsertionDirection.at(doc, boundary, Position.Bias.Backward), "leaning back into the Latin")
    }

    @Test
    fun `the end of the document follows the paragraph's own direction`() {
        // The bidi structure ends with a paragraph run at the base level, so the answer at the very
        // end is the paragraph's direction rather than the last character's. Swing's caret reads the
        // same element and answers the same way.
        val leftToRight = document("Hello مرحبا")
        assertEquals(true, InsertionDirection.at(leftToRight, leftToRight.length, Position.Bias.Forward))
        val rightToLeft = document("مرحبا hello")
        assertEquals(false, InsertionDirection.at(rightToLeft, rightToLeft.length, Position.Bias.Forward))
    }
}

/**
 * The flag as painted, on a real pane and measured off the pixels.
 *
 * The caret is painted on its own over a blank image, so what is measured is the caret and its mark
 * and nothing of the text. The bar is the tallest ink in that image; every other inked column is the
 * flag, which is why the two can be told apart without asking the code under test where it drew them.
 */
class CaretBidiFlagTest {

    /** The two paragraph directions a mixed document can have, each with the same two runs in it. */
    private val mixedParagraphs = listOf("Hello مرحبا world", "مرحبا hello مرحبا")

    private fun pane(text: String): AdvancedTextPane {
        val pane = ShapedText.pane(text, width = 300, primary = "Serif", fallback = NotoNaskhArabicFont.FAMILY)
        ShapedText.onEdt {
            pane.caret.isVisible = true
            pane.caretColor = Color.BLACK
        }
        return pane
    }

    private data class PaintedCaret(
        /** Device columns the caret bar covers. */
        val bar: IntRange,
        /** Device columns inked to the left of the bar, if any. */
        val left: IntRange?,
        /** Device columns inked to the right of the bar, if any. */
        val right: IntRange?,
        /** Device rows the flag's own ink covers. */
        val flagRows: IntRange?,
        /** Pixels the caret left only partly covered, which a crisp mark never does. */
        val partial: Int,
    ) {
        val flagLength: Int get() = (left?.count() ?: 0) + (right?.count() ?: 0)
    }

    private fun painted(
        pane: AdvancedTextPane,
        dot: Int,
        bias: Position.Bias,
        scale: Double = 1.0,
    ): PaintedCaret {
        val caret = pane.caret as DefaultCaret
        // The caret is painted at its own component coordinates, so the image is measured in the same
        // space: no translation, and every measurement below is already an absolute device row.
        val width = Math.ceil(pane.width * scale).toInt() + 8
        val height = Math.ceil(pane.height * scale).toInt() + 8
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics() as Graphics2D
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.transform = AffineTransform.getScaleInstance(scale, scale)
        ShapedText.onEdt {
            caret.setDot(dot, bias)
            caret.paint(g)
        }
        g.dispose()

        val inked = ArrayList<Int>()
        var partial = 0
        val rowsByColumn = HashMap<Int, MutableList<Int>>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = image.getRGB(x, y)
                if (rgb != Color.WHITE.rgb) {
                    if ((rgb ushr 24) != 255) partial++
                    inked += x
                    val rows = rowsByColumn.getOrPut(x) { ArrayList() }
                    rows += y
                }
            }
        }
        check(inked.isNotEmpty()) { "the caret painted nothing at dot $dot" }

        // The bar is the only part as tall as the caret; the flag is a few rows at the top.
        val tallest = rowsByColumn.entries.maxByOrNull { it.value.size }!!
        val barColumns = rowsByColumn.filterValues { it.size >= tallest.value.size * 3 / 4 }.keys.sorted()
        check(barColumns.isNotEmpty()) { "no caret bar was painted at dot $dot" }

        val bar = barColumns.first()..barColumns.last()
        val left = inked.filter { it < bar.first }.sorted()
        val right = inked.filter { it > bar.last }.sorted()
        val flagRows = (left + right).flatMap { rowsByColumn[it]!! }.let {
            if (it.isEmpty()) null else it.minOf { r -> r }..it.maxOf { r -> r }
        }
        return PaintedCaret(
            bar = bar,
            left = if (left.isEmpty()) null else left.first()..left.last(),
            right = if (right.isEmpty()) null else right.first()..right.last(),
            flagRows = flagRows,
            partial = partial,
        )
    }

    private data class Run(val start: Int, val end: Int, val level: Int) {
        val rightToLeft: Boolean get() = level % 2 == 1
    }

    /** The document's bidi runs, which is where the text UI's own directions come from. */
    private fun runs(pane: AdvancedTextPane): List<Run> {
        val bidi = (pane.styledDocument as AbstractDocument).bidiRootElement
        return (0 until bidi.elementCount).map { i ->
            val element = bidi.getElement(i)
            Run(element.startOffset, element.endOffset, StyleConstants.getBidiLevel(element.attributes))
        }
    }

    /** A caret position inside the first right-to-left run. */
    private fun rtlDot(pane: AdvancedTextPane): Int = runs(pane).first { it.rightToLeft }.start + 2

    /** A caret position inside the first left-to-right run that holds text. */
    private fun ltrDot(pane: AdvancedTextPane): Int =
        runs(pane).first { !it.rightToLeft && it.end - it.start >= 4 }.start + 2

    /** A right-to-left run that follows a left-to-right one: the junction both directions meet at. */
    private fun junction(pane: AdvancedTextPane): Int {
        val list = runs(pane)
        return list.zipWithNext().first { (before, after) -> !before.rightToLeft && after.rightToLeft }.second.start
    }

    @Test
    fun `single direction text keeps an ordinary caret`() {
        // The mark must not appear where there is nothing to say: English and Arabic alone.
        listOf("Hello world", "مرحبا بالعالم").forEach { text ->
            val pane = pane(text)
            val painted = painted(pane, 3, Position.Bias.Forward)
            assertNull(painted.left, "$text: the caret grew ink on its left")
            assertNull(painted.right, "$text: the caret grew ink on its right")
            assertEquals(
                CaretGeometry.NOMINAL_WIDTH,
                painted.bar.count(),
                "$text: the caret bar should be the plain nominal width",
            )
        }
    }

    @Test
    fun `mixed text points the flag the way the character at the caret runs`() {
        // Both paragraph directions with the same two runs inside them: a translation can be either.
        mixedParagraphs.forEach { text ->
            val pane = pane(text)

            // Inside the Latin run: the insertion point continues left to right, so the flag goes right.
            val latin = painted(pane, ltrDot(pane), Position.Bias.Forward)
            assertNull(latin.left, "$text: no ink should sit left of a left-to-right caret: ${latin.left}")
            assertTrue(latin.flagLength >= 3, "$text: the flag should be drawn to the right: ${latin.right}")

            // Inside the Arabic run: right to left, so the flag goes left.
            val rtl = painted(pane, rtlDot(pane), Position.Bias.Forward)
            assertNull(rtl.right, "$text: no ink should sit right of a right-to-left caret: ${rtl.right}")
            assertTrue(rtl.flagLength >= 3, "$text: the flag should be drawn to the left: ${rtl.left}")
        }
    }

    @Test
    fun `at a bidi boundary the bias turns the flag around`() {
        // The same careted offset, two insertion directions: this is the ambiguity #378 is about.
        mixedParagraphs.forEach { text ->
            val pane = pane(text)
            val boundary = junction(pane)

            val intoArabic = painted(pane, boundary, Position.Bias.Forward)
            assertTrue(intoArabic.flagLength >= 3, "$text: a forward caret at the boundary carries the flag")
            assertNull(intoArabic.right, "$text: leaning into the Arabic run puts the flag on the left")

            val intoLatin = painted(pane, boundary, Position.Bias.Backward)
            assertTrue(intoLatin.flagLength >= 3, "$text: a backward caret at the boundary carries the flag")
            assertNull(intoLatin.left, "$text: leaning back into the Latin run puts the flag on the right")
        }
    }

    @Test
    fun `the flag stays a small mark at the top of the caret`() {
        val pane = pane(mixedParagraphs.first())
        val painted = painted(pane, 2, Position.Bias.Forward)
        val rows = painted.flagRows ?: error("no flag was painted")
        assertEquals(
            CaretGeometry.FLAG_SIZE,
            rows.count(),
            "the flag is a 4px mark, not a second caret: $rows",
        )
        val row = ShapedText.onEdt { pane.ui.modelToView2D(pane, 2, Position.Bias.Forward)!! }
        val caretTop = ShapedText.onEdt {
            val metrics = pane.getFontMetrics(pane.font)
            val placement = CaretGeometry.place(
                rowX = row.x,
                rowY = row.y,
                rowHeight = row.height,
                ascent = metrics.ascent,
                descent = metrics.descent,
                transform = AffineTransform(),
            )
            placement.y
        }
        assertEquals(caretTop, rows.first, "the flag starts at the top of the caret body")
    }

    @Test
    fun `the flag scales with the display and keeps the caret's own thickness`() {
        val pane = pane(mixedParagraphs.first())
        listOf(1.0, 1.25, 1.5, 2.0).forEach { scale ->
            val painted = painted(pane, 2, Position.Bias.Forward, scale)
            assertEquals(
                Math.round(CaretGeometry.NOMINAL_WIDTH * scale).toInt(),
                painted.bar.count(),
                "at ${scale * 100}% the flag must not change the caret's own thickness",
            )
            assertEquals(
                Math.round(CaretGeometry.FLAG_SIZE * scale).toInt(),
                painted.flagLength,
                "at ${scale * 100}% the flag should follow the display scale",
            )
            assertEquals<Int>(
                0,
                painted.partial,
                "at ${scale * 100}% the flag left partly covered pixels: the mark has to stay crisp (#377)",
            )
        }
    }

    @Test
    fun `the flag is drawn on the caret's row`() {
        val pane = pane(mixedParagraphs.first())
        val painted = painted(pane, 2, Position.Bias.Forward)
        val row = ShapedText.onEdt { pane.ui.modelToView2D(pane, 2, Position.Bias.Forward)!! }
        val rows = painted.flagRows!!
        assertTrue(
            rows.first >= Math.floor(row.y).toInt() && rows.last <= Math.ceil(row.y + row.height).toInt(),
            "the flag must stay inside the row Swing repaints: $rows for $row",
        )
    }
}
