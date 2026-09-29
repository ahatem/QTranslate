package com.github.ahatem.qtranslate.ui.swing.shared.textpane

import java.awt.font.TextHitInfo
import java.awt.font.TextLayout
import java.text.BreakIterator

/**
 * Judges a shaped layout's carets, taken at grapheme-cluster boundaries in logical order across one
 * unidirectional run, and fills in the positions a sound layout collapses inside a ligature. Nothing
 * here depends on the script or the font.
 *
 * - A sound run moves every caret one way along the reading direction, or not at all.
 * - Not moving is normal inside a ligature: the JDK gives the glyph's advance to its first character
 *   and puts every character ligatured into it at the glyph's trailing edge with zero advance.
 * - A space never collapses in a sound run, since a space is never part of a ligature. Some fonts'
 *   layouts collapse every later caret, spaces included, onto one point past a ligature.
 */
internal object ShapedCarets {

    /** Below this, two carets are the same point. */
    private const val COLLAPSE_EPSILON = 0.01f

    /** How far a caret may move against the reading direction before it counts as going backwards. */
    private const val REGRESSION_TOLERANCE = 1f

    /**
     * Whether a unidirectional run's carets can be trusted.
     *
     * @param boundaries grapheme-cluster boundaries, ascending, indexes into [text].
     * @param xs the caret position the layout reports for each of [boundaries].
     */
    fun areSound(text: CharSequence, boundaries: IntArray, xs: FloatArray, rightToLeft: Boolean): Boolean {
        val direction = if (rightToLeft) -1f else 1f
        for (i in 0 until boundaries.size - 1) {
            val step = direction * (xs[i + 1] - xs[i])
            if (step < -REGRESSION_TOLERANCE) return false
            if (step <= COLLAPSE_EPSILON && (boundaries[i] until boundaries[i + 1]).any { isSpace(text[it]) }) return false
        }
        return true
    }

    /**
     * [xs] with a sound run's ligature components spread across the ligature glyph, in proportion to
     * each cluster's own width ([standaloneAdvance]), so a genuinely zero-width cluster keeps its caret.
     */
    fun spreadLigatures(boundaries: IntArray, xs: FloatArray, standaloneAdvance: (Int, Int) -> Float): FloatArray {
        val spread = xs.copyOf()
        var k = 1
        while (k < xs.size) {
            if (!same(xs[k - 1], xs[k])) { k++; continue }
            // xs[k - 1] and xs[k] collapsed: the group starts at k - 1, and the glyph starts one before.
            val first = k - 1
            var last = k
            while (last + 1 < xs.size && same(xs[last + 1], xs[last])) last++
            val anchor = first - 1
            if (anchor >= 0) {
                val weights = FloatArray(last - anchor) { i ->
                    standaloneAdvance(boundaries[anchor + i], boundaries[anchor + i + 1]).coerceAtLeast(0f)
                }
                val total = weights.sum()
                if (total > COLLAPSE_EPSILON) {
                    var along = 0f
                    for (j in first until last) {
                        along += weights[j - 1 - anchor]
                        spread[j] = xs[anchor] + (xs[last] - xs[anchor]) * (along / total)
                    }
                }
            }
            k = last + 1
        }
        return spread
    }

    /**
     * Whether [layout], laid out over [text], reports sound carets for every unidirectional run that
     * intersects `[from, to)`.
     */
    fun layoutIsSound(text: String, layout: TextLayout, from: Int, to: Int): Boolean {
        if (to - from <= 1) return true
        val clusters = BreakIterator.getCharacterInstance().apply { setText(text) }
        var start = from
        while (start < to) {
            val level = layout.getCharacterLevel(start)
            var end = start + 1
            while (end < to && layout.getCharacterLevel(end) == level) end++
            val boundaries = boundariesWithin(clusters, start, end)
            val xs = FloatArray(boundaries.size) { caretOf(layout, boundaries[it], end) }
            if (!areSound(text, boundaries, xs, rightToLeft = level % 2 == 1)) return false
            start = end
        }
        return true
    }

    /** The caret a layout reports at [boundary], leaning into the run that ends at [runEnd]. */
    private fun caretOf(layout: TextLayout, boundary: Int, runEnd: Int): Float {
        val hit = if (boundary < runEnd) TextHitInfo.leading(boundary) else TextHitInfo.trailing(boundary - 1)
        return layout.getCaretInfo(hit)[0]
    }

    /** Every cluster boundary in `[start, end]`, with both ends included even if a cluster straddles them. */
    private fun boundariesWithin(clusters: BreakIterator, start: Int, end: Int): IntArray {
        val found = ArrayList<Int>()
        found += start
        var next = clusters.following(start)
        while (next != BreakIterator.DONE && next < end) {
            found += next
            next = clusters.next()
        }
        found += end
        return found.toIntArray()
    }

    private fun same(a: Float, b: Float) = kotlin.math.abs(a - b) <= COLLAPSE_EPSILON

    /** An ordinary space of any width: never ligatured, never legitimately zero-advance. */
    private fun isSpace(c: Char) = Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
}
