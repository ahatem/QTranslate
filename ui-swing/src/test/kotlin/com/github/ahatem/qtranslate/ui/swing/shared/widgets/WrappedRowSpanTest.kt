package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.LineBreakOpportunities
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ShapedText.onEdt
import java.awt.Font
import javax.swing.text.StyledDocument
import javax.swing.text.FlowView
import javax.swing.text.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A row reports the span it needs, and that span has to stay inside the width the row was laid out
 * in.
 *
 * Rows used to break *after* the space following their last word even when that space did not fit,
 * so the row claimed a few pixels more than it had been given (#374). The excess was the hanging
 * space's advance — small, invisible in left-to-right text and gone from right-to-left measurement —
 * which is why it showed up as a measured number rather than as misdrawn glyphs. The bug is in which
 * break is taken, so the assertion is on the geometry of the rows, not on painted ink: a row spanning
 * past its flow span is the defect, whether or not a glyph is ever drawn there.
 *
 * Two painters take breaks here: the run's own glyph painter (left-to-right text in a sound face) and
 * the measured last resort that a run without sound carets uses (Arabic in Rubik), because a fix in
 * one path is not evidence about the other. Widths sweep past many word boundaries, which is what
 * makes a space straddle a line's end.
 */
class WrappedRowSpanTest {

    /**
     * Room for the fractional rounding a bounded position allows: the break is taken at the last
     * cluster boundary that fits and the row's span is measured from the same layout, so the two agree
     * to well under a pixel. The defect this guards against overran by a whole space (3-8px at the
     * default size), so the two do not overlap.
     */
    private val tolerance = 1.5f

    private val singleSpaced =
        "The quick brown fox jumps over the lazy dog and keeps running for a long while today"

    /** Two spaces per gap: a wider hanging run, and so a wider excess in the defect. */
    private val doubleSpaced =
        "The  quick  brown  fox  jumps  over  the  lazy  dog  and  keeps  running  for  a  long  while"

    private val arabic =
        "تحذير صادق أريد وضع علامة عليه مباشرة بالنسبة للخط الذي يكون شكله العربي مكسورا بشكل حقيقي"

    /** The rows of the first paragraph, with the room each was given. */
    private class Row(val start: Int, val end: Int, val preferred: Float, val flow: Int)

    private fun rows(pane: AdvancedTextPane): List<Row> = onEdt {
        val paragraph = pane.ui.getRootView(pane).getView(0).getView(0) as FlowView
        (0 until paragraph.viewCount).map { i ->
            val row = paragraph.getView(i)
            Row(row.startOffset, row.endOffset, row.getPreferredSpan(View.X_AXIS), paragraph.getFlowSpan(i))
        }.sortedBy { it.start }
    }

    /** Rooms to wrap in, past many word boundaries so spaces land either side of a row's end. */
    private val widths = (150..300 step 3).toList()

    /**
     * Left-to-right text in a sound face, the same text with wider gaps, and Arabic in a face whose
     * layout needs the measured last resort.
     */
    private val samples = listOf(
        Triple("english", singleSpaced, Font.SERIF),
        Triple("english, double spaces", doubleSpaced, Font.SERIF),
        Triple("arabic, measured last resort", arabic, RubikSansFont.FAMILY),
    )

    @Test
    fun `a wrapped row claims no more width than the row was given`() {
        for ((label, text, family) in samples) {
            val pane = ShapedText.pane(text, widths.last(), family, family)
            for (width in widths) {
                ShapedText.resize(pane, width)
                for (row in rows(pane)) {
                    val over = row.preferred - row.flow
                    assertTrue(
                        over <= tolerance,
                        "$label at ${width}px: row ${row.start}-${row.end} claims ${row.preferred}px " +
                            "in a ${row.flow}px row, ${over}px over",
                    )
                }
            }
        }
    }

    /**
     * The rows still cover the paragraph once, and a row ends where a line may legally end unless
     * nothing inside it was legal to break at.
     *
     * Fitting every row inside its width is worthless if the wrap buys it by dropping text or by
     * ending a line in the middle of a word that had a legal break available.
     */
    @Test
    fun `the rows tile the paragraph and end where a line may end`() {
        for ((label, text, family) in samples) {
            val pane = ShapedText.pane(text, widths.last(), family, family)
            val doc: StyledDocument = onEdt { pane.styledDocument }
            // The document's implicit trailing newline belongs to the paragraph the rows tile.
            val paragraphEnd = onEdt<Int> { doc.getParagraphElement(0).endOffset }
            for (width in widths) {
                ShapedText.resize(pane, width)
                val laid = rows(pane)
                assertEquals(0, laid.first().start, "$label at ${width}px: the first row starts the paragraph")
                assertEquals(paragraphEnd, laid.last().end, "$label at ${width}px: the last row ends the paragraph")
                laid.zipWithNext().forEach { (row, next) ->
                    assertEquals(
                        row.end,
                        next.start,
                        "$label at ${width}px: rows ${row.start}-${row.end} and ${next.start}-${next.end} " +
                            "must meet, not overlap or leave a gap",
                    )
                }
                for (row in laid) {
                    assertTrue(row.end > row.start, "$label at ${width}px: row ${row.start}-${row.end} is empty")
                    val lastLegal = LineBreakOpportunities.lastIn(doc, row.start, row.end)
                    assertTrue(
                        lastLegal == row.end || lastLegal == row.start,
                        "$label at ${width}px: row ${row.start}-${row.end} ends mid-word although it " +
                            "could have ended at $lastLegal",
                    )
                }
            }
        }
    }

    @Test
    fun `the sampled rows break often enough to exercise the wrap`() {
        // Guards the samples rather than the product: a text that never wrapped, or never ended a row
        // after a space, would make the assertions above vacuous.
        for ((label, text, family) in samples) {
            val pane = ShapedText.pane(text, widths.last(), family, family)
            val doc: StyledDocument = onEdt { pane.styledDocument }
            var spaceEndingRows = 0
            for (width in widths) {
                ShapedText.resize(pane, width)
                val laid = rows(pane)
                assertTrue(laid.size > 1, "$label at ${width}px should wrap")
                for (row in laid.dropLast(1)) {
                    val rowText = onEdt { doc.getText(row.start, row.end - row.start) }
                    if (rowText.endsWith(" ")) spaceEndingRows++
                }
            }
            assertTrue(spaceEndingRows > 0, "$label should sometimes end a row after a space")
        }
    }
}
