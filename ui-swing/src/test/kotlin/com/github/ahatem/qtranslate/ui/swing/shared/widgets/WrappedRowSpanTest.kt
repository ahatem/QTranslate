package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.LineBreakOpportunities
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ShapedText.onEdt
import java.awt.Font
import javax.swing.text.StyledDocument
import javax.swing.text.FlowView
import javax.swing.text.View
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A row's reported span has to stay inside the width it was laid out in. Rows used to break after the
 * space following their last word even when that space did not fit (#374). Covers both painters: the
 * run's own glyph painter and the measured last resort (Arabic in Rubik).
 */
class WrappedRowSpanTest {

    /** Fractional rounding only; the defect overran by a whole space (3-8px). */
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
    private val widths = (150..300 step 15).toList()

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
    fun `a wrapped row claims no more width than the row was given, and still ends where a line may end`() {
        for ((label, text, family) in samples) {
            val pane = ShapedText.pane(text, widths.last(), family, family)
            val doc: StyledDocument = onEdt { pane.styledDocument }
            for (width in widths) {
                ShapedText.resize(pane, width)
                for (row in rows(pane)) {
                    val lastLegal = LineBreakOpportunities.lastIn(doc, row.start, row.end)
                    assertTrue(
                        lastLegal == row.end || lastLegal == row.start,
                        "$label at ${width}px: row ${row.start}-${row.end} ends mid-word although it could have ended at $lastLegal",
                    )
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
}
