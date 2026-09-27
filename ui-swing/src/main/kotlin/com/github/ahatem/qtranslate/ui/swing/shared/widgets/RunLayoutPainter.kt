package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.textpane.GraphemeBoundary
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextHitInfo
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
 * Paints one unidirectional run from a `TextLayout` of its own text, font and direction.
 *
 * Used where Swing's shaped painter is not available:
 * - a fragment re-cut from a shaped run ([measured] false). Swing's shaped painter cannot be carried
 *   into a new range, and its fallback assumes left-to-right text. Carets come from this layout,
 *   which is exact because a re-cut lands at a legal break and no script joins across one;
 * - the last resort, text no configured font shapes soundly ([measured] true). That layout's carets
 *   cannot be trusted, so each position is measured instead: exact between words, since no script
 *   shapes across a space, and approximate inside one.
 *
 * Either way the whole run is drawn as one layout, so what is painted is shaped in context.
 */
internal class RunLayoutPainter(val measured: Boolean) : GlyphView.GlyphPainter() {

    private val clusters = GraphemeBoundary()

    private data class LayoutKey(val start: Int, val text: String, val font: Font, val rightToLeft: Boolean)

    private var key: LayoutKey? = null
    private var whole: TextLayout? = null
    private var measure: Measure? = null

    /** The whole run, laid out once for as long as its text, font and direction stay the same. */
    private fun wholeLayout(v: GlyphView): TextLayout {
        val text = v.document.getText(v.startOffset, v.endOffset - v.startOffset)
        val current = LayoutKey(v.startOffset, text, v.font, v.readsRightToLeft())
        whole?.let { if (current == key) return it }
        key = current
        measure = null
        return layoutOf(v, text).also { whole = it }
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

    /** Distance from the run's leading edge to the caret at document offset [pos]. */
    private fun along(v: GlyphView, pos: Int): Float {
        val layout = wholeLayout(v)
        val relative = (pos - v.startOffset).coerceIn(0, v.endOffset - v.startOffset)
        if (measured) {
            val m = measure ?: Measure(v, v.document.getText(v.startOffset, v.endOffset - v.startOffset)).also { measure = it }
            return m.along(relative)
        }
        val length = layout.characterCount
        val hit = if (relative < length) TextHitInfo.leading(relative) else TextHitInfo.trailing(length - 1)
        val x = layout.getCaretInfo(hit)[0]
        return if (v.readsRightToLeft()) layout.advance - x else x
    }

    /**
     * The last resort's measurement: the words of the run and the spaces between them are each laid
     * out once, and a position inside a word adds only the part of that word before it.
     */
    private inner class Measure(private val v: GlyphView, private val text: String) {
        /** Start of each piece (a word, or the spaces between two), relative to the run. */
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

        fun along(relative: Int): Float {
            if (relative == text.length) return before.last()
            var piece = pieceStarts.binarySearch(relative)
            if (piece >= 0) return before[piece]
            piece = -piece - 2
            // A cut word takes the forms of a cut word, so its start can measure wider than the whole
            // word; kept within the word's own extent, a position never lands beyond it.
            return partial.getOrPut(relative) {
                (before[piece] + layoutOf(v, text.substring(pieceStarts[piece], relative)).advance)
                    .coerceIn(before[piece], before[piece + 1])
            }
        }

        private fun isSpace(c: Char) = Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
    }

    /** Advance of `[p0, p1)`. */
    private fun span(v: GlyphView, p0: Int, p1: Int): Float = if (p1 <= p0) 0f else along(v, p1) - along(v, p0)

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
        // Part of the run, such as a selection: the whole run clipped to that part, so every glyph
        // keeps the form it has in context.
        val oldClip = g2.clip
        g2.clip(rangeShape(v, p0, p1, a))
        layout.draw(g2, x, y)
        g2.clip = oldClip
    }

    private fun caretX(v: GlyphView, pos: Int, alloc: Rectangle2D): Double {
        val along = along(v, pos)
        return if (v.readsRightToLeft()) alloc.x + along(v, v.endOffset) - along else alloc.x + along
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
        val target = if (v.readsRightToLeft()) (alloc.x + along(v, v.endOffset) - x).toFloat() else (x - alloc.x).toFloat()
        val stops = caretStops(v)
        // Positions only move forward along the run, so the nearest stop is found by bisection.
        var lo = 0
        var hi = stops.size - 1
        var below = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (along(v, stops[mid]) <= target) { below = mid; lo = mid + 1 } else hi = mid - 1
        }
        var found = stops[below]
        if (below + 1 < stops.size && along(v, stops[below + 1]) - target < target - along(v, stops[below])) {
            found = stops[below + 1]
        }
        biasReturn[0] = if (found == v.endOffset && found > v.startOffset) Position.Bias.Backward else Position.Bias.Forward
        return found
    }

    /**
     * The largest cluster boundary from [p0] whose text fits in [len], looking at a window of the run
     * that doubles while everything in it fits, so a break costs the length of a line.
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

    override fun getPainter(v: GlyphView, p0: Int, p1: Int): GlyphView.GlyphPainter = RunLayoutPainter(measured)

    /**
     * One cluster per step in the direction the run reads, and -1 once the step leaves the run, the
     * same contract the JDK's shaped painter keeps.
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
