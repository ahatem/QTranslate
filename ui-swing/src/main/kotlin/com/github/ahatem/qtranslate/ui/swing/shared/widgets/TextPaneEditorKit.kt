package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.textpane.GraphemeBoundary
import java.awt.Rectangle
import java.awt.Shape
import java.text.Bidi
import java.text.BreakIterator
import java.util.Locale
import javax.swing.event.DocumentEvent
import javax.swing.text.AbstractDocument
import javax.swing.text.BadLocationException
import javax.swing.text.BoxView
import javax.swing.text.ComponentView
import javax.swing.text.Element
import javax.swing.text.IconView
import javax.swing.text.LabelView
import javax.swing.text.ParagraphView
import javax.swing.text.Position
import javax.swing.text.StyleConstants
import javax.swing.text.StyledEditorKit
import javax.swing.text.View
import javax.swing.text.ViewFactory
import kotlin.math.abs

/**
 * The pane's view layer: a [StyledEditorKit] whose views break lines without splitting a grapheme
 * cluster and never trust a shaped run's hit-testing once it has been caught lying.
 */
class WrappingEditorKit : StyledEditorKit() {
    private val viewFactory = WrappingViewFactory()

    override fun getViewFactory() = viewFactory

    class WrappingViewFactory : ViewFactory {
        override fun create(elem: Element): View = when (elem.name) {
            AbstractDocument.ContentElementName  -> SafeLabelView(elem)
            AbstractDocument.ParagraphElementName -> WrappingParagraphView(elem)
            AbstractDocument.SectionElementName  -> BoxView(elem, View.Y_AXIS)
            StyleConstants.ComponentElementName  -> ComponentView(elem)
            StyleConstants.IconElementName       -> IconView(elem)
            else                                 -> LabelView(elem)
        }
    }

    /**
     * A [LabelView] that never breaks inside a grapheme cluster, and falls back to its own
     * measurement for line breaking, the caret and the mouse whenever the run's own hit-testing is
     * caught contradicting itself.
     */
    private class SafeLabelView(elem: Element) : LabelView(elem) {

        private val graphemeBoundary = GraphemeBoundary()

        /**
         * Cached once resolved, and invalidated by [changedUpdate]: a view survives an attribute-only
         * change to the text it holds (this is exactly how the font-fallback pass hands a run a
         * different font, well after this view was first measured), and a verdict reached under the
         * font it had before that change says nothing about the one it has after.
         */
        private var reliableCache: Boolean? = null

        /**
         * Whether this run's own hit-testing can be trusted for breaking, the caret and the mouse.
         *
         * Swing measures anything needing complex shaping -- bidi text, combining marks, some
         * ligatures -- through a `TextLayout`, and that layout's own character-by-character answers
         * are not reliable for every font and glyph sequence: past a particular Arabic ligature,
         * several font files (bundled and platform alike) report every later position as the same
         * point, which is what makes Swing's line breaker give up far short of the room it actually
         * has, and mouse and caret placement wrong the same way. [checkReliable] tells the two cases
         * apart by sampling the run's own answers rather than by asking which font drew it, so a
         * well-behaved shaped run -- most of them, on most fonts -- is left exactly as Swing would
         * have handled it, at full precision, and only a run actually caught misbehaving falls back
         * to this class's own, approximate-but-safe measurement.
         */
        private fun isReliable(): Boolean {
            reliableCache?.let { return it }
            val reliable = runCatching { checkReliable() }.getOrDefault(false)
            reliableCache = reliable
            return reliable
        }

        private fun needsFallback(): Boolean {
            checkPainter()
            if (glyphPainter?.javaClass?.simpleName != SHAPED_PAINTER) return false
            return !isReliable()
        }

        /**
         * Samples a handful of offsets across the run and checks the stock implementation places
         * their carets in non-decreasing order along the reading direction, within a tolerance loose
         * enough to absorb ordinary sub-pixel measurement noise. The corruption this class works
         * around is not subtle: it collapses many distinct offsets onto the same point, or moves
         * backwards by a large fraction of the run's own width, which a run that is shaping correctly
         * never does regardless of language -- a real regression is nothing like a rounding error.
         */
        private fun checkReliable(): Boolean {
            val length = endOffset - startOffset
            if (length <= 1) return true
            val totalSpan = getPreferredSpan(X_AXIS)
            // Wide enough for the run with generous room to spare, rather than an extreme value that
            // risks its own numerical surprises in whatever native text-layout code answers this.
            val alloc = Rectangle(0, 0, (totalSpan * 4).toInt().coerceAtLeast(1000), 20)
            val rtl = isRightToLeftRun()
            val sampleCount = minOf(SAMPLE_COUNT, length + 1)
            // A regression has to give back a meaningful fraction of the run's own width to count;
            // this is what tells an actual corrupted jump apart from ordinary rounding noise between
            // two otherwise-adjacent samples.
            val tolerance = (totalSpan / sampleCount * 0.5).coerceAtLeast(1.0)
            var previous: Double? = null
            for (i in 0 until sampleCount) {
                val offset = startOffset + (i * length) / (sampleCount - 1).coerceAtLeast(1)
                // Forward bias at this view's own endOffset asks for the position leaning toward the
                // character that starts the *next* view, which is not this view's to answer and is
                // exactly the kind of boundary Swing's own implementations handle inconsistently --
                // backward bias keeps the query inside the range this view actually owns.
                val bias = if (offset >= endOffset) Position.Bias.Backward else Position.Bias.Forward
                val shape = super.modelToView(offset, alloc, bias)
                val x = shape.bounds2D.x
                if (previous != null) {
                    val advanced = if (rtl) x < previous + tolerance else x > previous - tolerance
                    if (!advanced) return false
                }
                previous = x
            }
            return true
        }

        override fun getMinimumSpan(axis: Int): Float =
            if (axis == X_AXIS) super.getPreferredSpan(axis) / 4 else super.getMinimumSpan(axis)

        override fun getBreakWeight(axis: Int, pos: Float, len: Float): Int =
            if (axis == X_AXIS) GoodBreakWeight else super.getBreakWeight(axis, pos, len)

        /** An attribute-only change can hand this run a different font; the old reliability verdict does not carry over. */
        override fun changedUpdate(e: DocumentEvent, a: Shape?, f: ViewFactory) {
            reliableCache = null
            super.changedUpdate(e, a, f)
        }

        /**
         * The painter's choice can land on a code unit in the middle of a grapheme cluster, or, for
         * an unreliable shaped run, come from broken hit-testing altogether; either way the result is
         * checked against cluster boundaries, and an unreliable run is broken by [breakShaped]
         * instead of trusting the standard implementation's break point.
         *
         * [needsFallback]'s sample is a prediction, taken before this specific break is asked for, and
         * checked against a handful of points rather than every one; it can pass for a run that then
         * goes on to answer a particular break request badly anyway. The result is checked again here,
         * directly, against the one thing that actually matters: whether it fits the room it was asked
         * to fit. A fragment that does not is retried with [breakShaped], whatever the sample said --
         * an overflowing line is exactly the visible defect this class exists to prevent, so nothing is
         * trusted merely because it came from the standard implementation.
         */
        override fun breakView(axis: Int, p0: Int, pos: Float, len: Float): View? {
            if (axis != X_AXIS) return super.breakView(axis, p0, pos, len)

            var standard = (
                if (needsFallback()) runCatching { breakShaped(p0, len) }.getOrElse { super.breakView(axis, p0, pos, len) }
                else super.breakView(axis, p0, pos, len)
                ) ?: return null

            val fits = runCatching { standard.getPreferredSpan(X_AXIS) }.getOrDefault(0f) <= len + OVERFLOW_TOLERANCE
            if (!fits) {
                standard = runCatching { breakShaped(p0, len) }.getOrNull() ?: standard
            }

            if (standard === this) return this

            val end = standard.endOffset
            val safeEnd = safeBreakEnd(p0, end)
            if (safeEnd == end) return standard
            return if (safeEnd > p0) createFragment(p0, safeEnd) else null
        }

        override fun modelToView(pos: Int, a: Shape, b: Position.Bias): Shape {
            if (!needsFallback()) return super.modelToView(pos, a, b)
            return runCatching { shapedCaretShape(pos, a) }.getOrElse { super.modelToView(pos, a, b) }
        }

        override fun modelToView(p0: Int, b0: Position.Bias, p1: Int, b1: Position.Bias, a: Shape): Shape {
            if (!needsFallback()) return super.modelToView(p0, b0, p1, b1, a)
            return runCatching { shapedRangeShape(p0, p1, a) }.getOrElse { super.modelToView(p0, b0, p1, b1, a) }
        }

        override fun viewToModel(x: Float, y: Float, a: Shape, biasReturn: Array<Position.Bias>): Int {
            if (!needsFallback()) return super.viewToModel(x, y, a, biasReturn)
            biasReturn[0] = Position.Bias.Forward
            return runCatching { shapedViewToModel(x, a) }.getOrElse { super.viewToModel(x, y, a, biasReturn) }
        }

        private fun shapedCaretShape(pos: Int, a: Shape): Shape {
            if (pos < startOffset || pos > endOffset) {
                throw BadLocationException("Position not represented by view", pos)
            }
            val alloc = a.bounds
            return Rectangle(caretX(alloc, pos), alloc.y, 0, alloc.height)
        }

        private fun shapedRangeShape(p0: Int, p1: Int, a: Shape): Shape {
            val alloc = a.bounds
            val from = caretX(alloc, p0)
            val to = caretX(alloc, p1)
            return Rectangle(minOf(from, to), alloc.y, abs(to - from), alloc.height)
        }

        private fun shapedViewToModel(x: Float, a: Shape): Int {
            val alloc = a.bounds
            val distance = if (isRightToLeftRun()) alloc.x + alloc.width - x else x - alloc.x
            if (distance <= 0f) return startOffset

            val cuts = clusterCutsFrom(startOffset, endOffset)
            if (cuts.isEmpty()) return startOffset

            var lo = 0
            var hi = cuts.size - 1
            var below = startOffset
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (spanOf(startOffset, cuts[mid]) <= distance) { below = cuts[mid]; lo = mid + 1 } else hi = mid - 1
            }
            val above = cuts.firstOrNull { it > below } ?: return below
            val nearer = distance - spanOf(startOffset, below) < spanOf(startOffset, above) - distance
            return if (nearer) below else above
        }

        /** Where the caret sits at [pos]: its distance along the run, from the edge the run starts at. */
        private fun caretX(alloc: Rectangle, pos: Int): Int {
            val along = spanOf(startOffset, pos)
            return if (isRightToLeftRun()) (alloc.x + alloc.width - along).toInt() else (alloc.x + along).toInt()
        }

        private fun isRightToLeftRun(): Boolean {
            val text = document.getText(startOffset, endOffset - startOffset)
            return Bidi.requiresBidi(text.toCharArray(), 0, text.length) &&
                Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).isRightToLeft
        }

        /**
         * Width of `[from, to)`, laid out as a fragment of its own -- which is how a wrapped row
         * holds it -- rather than read from the run's own broken hit-test data.
         *
         * An approximation, not the exact answer a correctly shaping font would give: cutting a word
         * changes which glyph forms its cut ends take, so a fragment's measured width drifts a little
         * from its true share of the whole run's advance, more so the more of the word is cut away.
         * That is an acceptable trade against the alternative, which is trusting data already shown
         * to be wrong; it is only ever used once [needsFallback] has found the run's own numbers
         * unreliable to begin with.
         */
        private fun spanOf(from: Int, to: Int): Float = when {
            to <= from -> 0f
            from == startOffset && to == endOffset -> getPreferredSpan(X_AXIS)
            else -> createFragment(from, to).getPreferredSpan(X_AXIS)
        }

        /** Cluster boundaries in `(from, to]`, ascending. */
        private fun clusterCutsFrom(from: Int, to: Int): IntArray {
            if (to <= from) return IntArray(0)
            val text = document.getText(from, to - from)
            return graphemeBoundary.boundariesAfterStart(text).map { from + it }.toIntArray()
        }

        /** The fragment starting at [p0] that fills [room], ending at a line-break opportunity where there is one. */
        private fun breakShaped(p0: Int, room: Float): View? {
            val fit = fittingEnd(p0, room)
            if (fit <= p0) return null
            // A space that does not fit hangs past the line's end instead of starting the next one.
            var trimmed = fit
            while (trimmed < endOffset && document.getText(trimmed, 1) == " ") trimmed++
            if (trimmed >= endOffset) return if (p0 == startOffset) this else createFragment(p0, endOffset)

            val spot = lineBreakAtOrBefore(p0, trimmed)
            return createFragment(p0, if (spot > p0) spot else trimmed)
        }

        /**
         * The largest cluster boundary from [from] whose text still fits in [room].
         *
         * Looks at a window of the run that doubles while everything in it fits, so a break in a long
         * run costs the length of the line, not the length of the whole run.
         */
        private fun fittingEnd(from: Int, room: Float): Int {
            var reach = INITIAL_REACH
            while (true) {
                val windowEnd = minOf(endOffset, from + reach)
                val cuts = clusterCutsFrom(from, windowEnd)
                if (cuts.isEmpty()) return from

                var lo = 0
                var hi = cuts.size - 1
                var best = -1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (spanOf(from, cuts[mid]) <= room) { best = mid; lo = mid + 1 } else hi = mid - 1
                }
                val everythingFits = best == cuts.size - 1
                if (!everythingFits || windowEnd >= endOffset) return if (best < 0) from else cuts[best]
                reach *= 2
            }
        }

        private fun lineBreakAtOrBefore(from: Int, fit: Int): Int {
            val lookEnd = minOf(endOffset, fit + GraphemeBoundary.LOOKAHEAD)
            val breaker = BreakIterator.getLineInstance(Locale.getDefault())
            breaker.setText(document.getText(from, lookEnd - from))
            val spot = breaker.preceding(fit - from + 1)
            return if (spot == BreakIterator.DONE) from else from + spot
        }

        /** [proposedEnd] moved back to the nearest grapheme-cluster boundary at or below it. */
        private fun safeBreakEnd(start: Int, proposedEnd: Int): Int {
            if (proposedEnd <= start) return start
            val doc = document
            // Look past the candidate end so a cluster straddling it is recognised and dropped
            // rather than split at the fragment edge.
            val lookaheadEnd = (proposedEnd + GraphemeBoundary.LOOKAHEAD).coerceAtMost(doc.length)
            val text = runCatching { doc.getText(start, lookaheadEnd - start) }.getOrNull()
                ?: return proposedEnd
            return start + graphemeBoundary.lastAtOrBelow(text, proposedEnd - start)
        }
    }

    private companion object {
        /** `GlyphPainter2` is package-private in the JDK, so it can only be recognised by name. */
        const val SHAPED_PAINTER = "GlyphPainter2"
        const val INITIAL_REACH = 48

        /** Sample points used to check a shaped run's hit-testing advances monotonically. */
        const val SAMPLE_COUNT = 8

        /** Slack allowed before a break result is treated as overflowing the room it was given. */
        const val OVERFLOW_TOLERANCE = 2f
    }

    class WrappingParagraphView(elem: Element) : ParagraphView(elem) {
        override fun layout(width: Int, height: Int) {
            super.layout(width, height)
            for (i in 0 until viewCount) {
                val child = getView(i)
                child.setSize(width.toFloat(), child.getPreferredSpan(Y_AXIS))
            }
        }

        override fun getMinimumSpan(axis: Int): Float =
            if (axis == X_AXIS) 0f else super.getMinimumSpan(axis)

        override fun getMaximumSpan(axis: Int): Float =
            if (axis == X_AXIS) Float.MAX_VALUE else super.getMaximumSpan(axis)
    }
}
