package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.textpane.GraphemeBoundary
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.Rectangle2D
import java.text.AttributedString
import java.text.Bidi
import javax.swing.text.AbstractDocument
import javax.swing.text.BadLocationException
import javax.swing.text.GlyphView
import javax.swing.text.Position
import javax.swing.text.StyleConstants
import javax.swing.text.TabExpander
import javax.swing.text.View
import kotlin.math.abs

/**
 * Whether this view's text reads right to left: its level in the document's own bidi structure, so a
 * run holding only spaces or punctuation inside right-to-left text reads the way its surroundings do.
 */
internal fun GlyphView.readsRightToLeft(): Boolean {
    val doc = document
    if (doc is AbstractDocument) {
        val bidi = doc.bidiRootElement
        return StyleConstants.getBidiLevel(bidi.getElement(bidi.getElementIndex(startOffset)).attributes) % 2 == 1
    }
    val text = doc.getText(startOffset, endOffset - startOffset)
    return Bidi.requiresBidi(text.toCharArray(), 0, text.length) &&
        Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).isRightToLeft
}

/**
 * The last-resort painter, for text no configured font lays out with sound hit-testing.
 *
 * A layout whose carets have been caught contradicting themselves (see
 * [ShapedCarets][com.github.ahatem.qtranslate.ui.swing.shared.textpane.ShapedCarets]) cannot say
 * where any of its characters are, so nothing here asks it. Each question is answered by laying out
 * the part of the run it is about on its own and reading that layout's total advance -- a quantity the
 * corruption does not touch. The whole run is still drawn as one layout, in context, so what is
 * painted looks exactly as it would anywhere else; only the caret, the mouse and line breaking are
 * measured.
 *
 * Between words the measurement is exact, since no script shapes across a space. Inside a word it
 * is an approximation: the part of a word before a position, laid out on its own, takes the glyph
 * forms of a cut word, so its advance drifts slightly from its share of the whole word. This painter
 * is used only where the alternative is a layout already shown to be wrong, and never for text any
 * configured font shapes soundly.
 */
internal class MeasuredRunPainter : GlyphView.GlyphPainter() {

    private val clusters = GraphemeBoundary()

    private data class LayoutKey(val start: Int, val text: String, val font: Font, val rightToLeft: Boolean)

    private var wholeKey: LayoutKey? = null
    private var whole: TextLayout? = null
    private var measure: Measure? = null

    /** The whole run, laid out once for as long as its text, font and direction stay the same. */
    private fun wholeLayout(v: GlyphView): TextLayout {
        refresh(v)
        return whole!!
    }

    private fun measureOf(v: GlyphView): Measure {
        refresh(v)
        return measure!!
    }

    private fun refresh(v: GlyphView) {
        val text = v.document.getText(v.startOffset, v.endOffset - v.startOffset)
        val key = LayoutKey(v.startOffset, text, v.font, v.readsRightToLeft())
        if (key == wholeKey && whole != null) return
        wholeKey = key
        whole = layoutOf(v, text)
        measure = Measure(v, text)
    }

    private fun layoutOf(v: GlyphView, text: String): TextLayout {
        val attributed = AttributedString(text.ifEmpty { " " })
        attributed.addAttribute(TextAttribute.FONT, v.font)
        attributed.addAttribute(
            TextAttribute.RUN_DIRECTION,
            if (v.readsRightToLeft()) TextAttribute.RUN_DIRECTION_RTL else TextAttribute.RUN_DIRECTION_LTR
        )
        return TextLayout(attributed.iterator, renderContext(v))
    }

    private fun renderContext(v: GlyphView): FontRenderContext =
        v.container?.getFontMetrics(v.font)?.fontRenderContext ?: FontRenderContext(null, true, true)

    /**
     * How far along the run each position is, from the run's leading edge.
     *
     * No script shapes across a space, so the run is measured a word at a time: the words and the
     * spaces between them are each laid out once, and a position inside a word adds only the part of
     * that word before it. That keeps every question about a long run to one short layout at most,
     * instead of laying out everything in front of the position again for each one.
     */
    private inner class Measure(private val v: GlyphView, private val text: String) {
        /** Start of each piece -- a word, or the spaces between two -- relative to the run. */
        private val pieceStarts: IntArray
        /** Advance of everything before each piece. */
        private val before: FloatArray
        private val partial = HashMap<Int, Float>()

        init {
            val starts = ArrayList<Int>()
            for (i in text.indices) {
                if (i == 0 || isSpace(text[i]) != isSpace(text[i - 1])) starts += i
            }
            pieceStarts = starts.toIntArray()
            before = FloatArray(pieceStarts.size + 1)
            for (i in pieceStarts.indices) {
                val end = if (i + 1 < pieceStarts.size) pieceStarts[i + 1] else text.length
                before[i + 1] = before[i] + layoutOf(v, text.substring(pieceStarts[i], end)).advance
            }
        }

        /** Distance from the run's leading edge to the caret at document offset [pos]. */
        fun along(pos: Int): Float {
            val relative = (pos - v.startOffset).coerceIn(0, text.length)
            if (relative == text.length) return before.last()
            var piece = pieceStarts.binarySearch(relative)
            if (piece >= 0) return before[piece]
            piece = -piece - 2
            return partial.getOrPut(relative) {
                before[piece] + layoutOf(v, text.substring(pieceStarts[piece], relative)).advance
            }
        }

        private fun isSpace(c: Char) = Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
    }

    /** Advance of `[p0, p1)`. */
    private fun span(v: GlyphView, p0: Int, p1: Int): Float = when {
        p1 <= p0 -> 0f
        p0 == v.startOffset && p1 == v.endOffset -> measureOf(v).along(p1)
        else -> measureOf(v).let { it.along(p1) - it.along(p0) }
    }

    override fun getSpan(v: GlyphView, p0: Int, p1: Int, e: TabExpander?, x: Float): Float = span(v, p0, p1)

    override fun getHeight(v: GlyphView): Float = wholeLayout(v).let { it.ascent + it.descent + it.leading }

    override fun getAscent(v: GlyphView): Float = wholeLayout(v).ascent

    override fun getDescent(v: GlyphView): Float = wholeLayout(v).descent

    override fun paint(v: GlyphView, g: Graphics, a: Shape, p0: Int, p1: Int) {
        val g2 = g as? Graphics2D ?: return
        val alloc = a.bounds2D
        val layout = wholeLayout(v)
        val x = alloc.x.toFloat()
        val y = (alloc.y + layout.ascent + layout.leading).toFloat()
        if (p0 <= v.startOffset && p1 >= v.endOffset) {
            layout.draw(g2, x, y)
            return
        }
        // Part of the run, such as a selection: the whole run drawn, clipped to that part, so every
        // glyph keeps the form it has in context.
        val oldClip = g2.clip
        g2.clip(rangeShape(v, p0, p1, a))
        layout.draw(g2, x, y)
        g2.clip = oldClip
    }

    /** Where the caret sits at [pos]. */
    private fun caretX(v: GlyphView, pos: Int, alloc: Rectangle2D): Double {
        val along = span(v, v.startOffset, pos)
        return if (v.readsRightToLeft()) alloc.x + span(v, v.startOffset, v.endOffset) - along else alloc.x + along
    }

    override fun modelToView(v: GlyphView, pos: Int, bias: Position.Bias, a: Shape): Shape {
        if (pos < v.startOffset || pos > v.endOffset) throw BadLocationException("Position not represented by view", pos)
        val alloc = a.bounds2D
        return Rectangle2D.Double(caretX(v, pos, alloc), alloc.y, 1.0, alloc.height)
    }

    fun rangeShape(v: GlyphView, p0: Int, p1: Int, a: Shape): Shape {
        val alloc = a.bounds2D
        val from = caretX(v, p0, alloc)
        val to = caretX(v, p1, alloc)
        return Rectangle2D.Double(minOf(from, to), alloc.y, abs(to - from), alloc.height)
    }

    override fun viewToModel(v: GlyphView, x: Float, y: Float, a: Shape, biasReturn: Array<Position.Bias>): Int {
        val alloc = a.bounds2D
        val along = if (v.readsRightToLeft()) (alloc.x + span(v, v.startOffset, v.endOffset) - x).toFloat() else (x - alloc.x).toFloat()
        val stops = caretStops(v)
        // Prefix advances only grow along the run, so the nearest stop is found by bisection.
        var lo = 0
        var hi = stops.size - 1
        var below = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (span(v, v.startOffset, stops[mid]) <= along) { below = mid; lo = mid + 1 } else hi = mid - 1
        }
        var found = stops[below]
        if (below + 1 < stops.size) {
            val before = along - span(v, v.startOffset, stops[below])
            val after = span(v, v.startOffset, stops[below + 1]) - along
            if (after < before) found = stops[below + 1]
        }
        biasReturn[0] = if (found == v.endOffset && found > v.startOffset) Position.Bias.Backward else Position.Bias.Forward
        return found
    }

    /**
     * The largest cluster boundary from [p0] whose text fits in [len].
     *
     * Looks at a window of the run that doubles while everything in it fits, so a break in a long run
     * costs the length of the line, not the length of the whole run.
     */
    override fun getBoundedPosition(v: GlyphView, p0: Int, x: Float, len: Float): Int {
        var reach = INITIAL_REACH
        while (true) {
            val windowEnd = minOf(v.endOffset, p0 + reach)
            val cuts = cutsAfter(v, p0, windowEnd)
            if (cuts.isEmpty()) return p0
            var lo = 0
            var hi = cuts.size - 1
            var best = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (span(v, p0, cuts[mid]) <= len) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (best < cuts.size - 1 || windowEnd >= v.endOffset) return if (best < 0) p0 else cuts[best]
            reach *= 2
        }
    }

    override fun getPainter(v: GlyphView, p0: Int, p1: Int): GlyphView.GlyphPainter = MeasuredRunPainter()

    /**
     * One cluster per step, in the direction the run reads, and -1 once the step leaves the run so
     * the row moves on to the next one -- the same contract the JDK's shaped painter keeps.
     */
    override fun getNextVisualPositionFrom(
        v: GlyphView, pos: Int, b: Position.Bias, a: Shape, direction: Int, biasRet: Array<Position.Bias>,
    ): Int {
        if (direction != View.EAST && direction != View.WEST) {
            return super.getNextVisualPositionFrom(v, pos, b, a, direction, biasRet)
        }
        val rightToLeft = v.readsRightToLeft()
        val stops = caretStops(v)
        if (pos == -1) {
            // Entering the run from one of its visual edges.
            val atStart = (direction == View.EAST) != rightToLeft
            val entered = if (atStart) stops.first() else stops.last()
            biasRet[0] = if (entered == v.endOffset && entered > v.startOffset) Position.Bias.Backward else Position.Bias.Forward
            return entered
        }
        val onward = (direction == View.WEST) == rightToLeft
        val next = if (onward) stops.firstOrNull { it > pos } else stops.lastOrNull { it < pos }
        next ?: return -1
        biasRet[0] = if (next == v.endOffset) Position.Bias.Backward else Position.Bias.Forward
        return next
    }

    /** Every place a caret may sit in the run: its cluster boundaries, less the one past a closing newline. */
    private fun caretStops(v: GlyphView): IntArray {
        val all = intArrayOf(v.startOffset) + cutsAfter(v, v.startOffset, v.endOffset)
        val endsWithNewline = v.endOffset > v.startOffset && v.document.getText(v.endOffset - 1, 1) == "\n"
        return if (endsWithNewline && all.size > 1) all.copyOf(all.size - 1) else all
    }

    /** Cluster boundaries in `(from, to]`, ascending. */
    private fun cutsAfter(v: GlyphView, from: Int, to: Int): IntArray {
        if (to <= from) return IntArray(0)
        val text = v.document.getText(from, to - from)
        return clusters.boundariesAfterStart(text).map { from + it }.toIntArray()
    }

    private companion object {
        const val INITIAL_REACH = 48
    }
}
