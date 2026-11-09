package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.textpane.CaretGeometry
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.InsertionDirection
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultCaret

/**
 * The caret, drawn as a rectangle on the display's pixel grid and sized to the text it sits in.
 *
 * Only the appearance is custom. Blinking, focus and selection all stay with [DefaultCaret], which
 * already starts and stops its flasher with focus and editability; a second timer would run
 * regardless of focus and compete with it for visibility.
 *
 * Where the caret goes remains Swing's decision and not this class's. The position comes from the
 * text UI at the caret's own bias, so it lands on exactly the boundary the editor answers
 * hit-testing with, including inside ligatures, combining marks and bidirectional text.
 *
 * The rectangle stays inside the row the text UI reported. That is the area Swing repaints when the
 * caret blinks and moves, so anything painted outside it would survive as a trail, and the superclass
 * sizes that area without knowing how wide this caret is.
 *
 * Where the text runs both ways, the caret also carries the small direction flag the superclass draws:
 * at a boundary between two directions the rectangle alone says where the insertion point is but not
 * which way what is typed there will go, and Arabic mixed with Latin is exactly where that is
 * ambiguous. The flag is Swing's own answer to that question, taken from the same bidi structure the
 * text UI lays the text out from, so it agrees with the text beside it instead of second-guessing it.
 *
 * See [CaretGeometry] for why the rectangle is resolved in device pixels.
 */
class AdvancedCaret(
    private val nominalWidth: Int = CaretGeometry.NOMINAL_WIDTH,
    blinkRate: Int = 600,
) : DefaultCaret() {

    init { setBlinkRate(blinkRate) }

    override fun paint(g: Graphics?) {
        if (!isVisible) return
        val comp = component ?: return
        val g2 = g as? Graphics2D ?: return

        // The caret's own bias rather than a hardcoded Forward. Where two graphemes meet, Forward and
        // Backward resolve to different boundaries, and the caret has to sit on the one the caret
        // itself reports, because that is the one a click is answered against.
        val row = try {
            comp.ui.modelToView2D(comp, dot, dotBias) ?: return
        } catch (_: BadLocationException) {
            return
        }
        if (row.width == 0.0 && row.height == 0.0) return

        val oldColor = g2.color
        val oldTransform = g2.transform
        try {
            g2.color = comp.caretColor

            // The component's own font, which is the document's primary font, so the caret keeps one
            // height for the whole document rather than one per font run.
            val metrics = comp.getFontMetrics(comp.font)
            val placement = CaretGeometry.place(
                rowX = row.x,
                rowY = row.y,
                rowHeight = row.height,
                ascent = metrics.ascent,
                descent = metrics.descent,
                transform = oldTransform,
                nominalWidth = nominalWidth,
            )
            g2.transform = CaretGeometry.transformFor(placement)
            g2.fillRect(placement.x, placement.y, placement.width, placement.height)

            // Null where the document holds one direction: an ordinary caret gains no mark.
            val insertionIsLeftToRight = InsertionDirection.at(comp.document, dot, dotBias)
            if (insertionIsLeftToRight != null) {
                g2.fillPolygon(CaretGeometry.flag(placement, insertionIsLeftToRight, oldTransform))
            }
        } finally {
            g2.transform = oldTransform
            g2.color = oldColor
        }
    }
}