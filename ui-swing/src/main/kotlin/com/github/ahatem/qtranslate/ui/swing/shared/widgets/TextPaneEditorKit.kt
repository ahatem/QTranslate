package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.textpane.GraphemeBoundary
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.LineBreakOpportunities
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.ShapedCarets
import java.awt.Rectangle
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.Rectangle2D
import java.text.AttributedString
import java.text.Bidi
import javax.swing.event.DocumentEvent
import javax.swing.text.AbstractDocument
import javax.swing.text.BoxView
import javax.swing.text.ComponentView
import javax.swing.text.Element
import javax.swing.text.FlowView
import javax.swing.text.GlyphView
import javax.swing.text.IconView
import javax.swing.text.LabelView
import javax.swing.text.ParagraphView
import javax.swing.text.Position
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import javax.swing.text.StyledEditorKit
import javax.swing.text.View
import javax.swing.text.ViewFactory
import kotlin.math.abs

/**
 * The pane's view layer: a [StyledEditorKit] whose views break lines only where a line may legally
 * end, never split a grapheme cluster, and never trust a shaped layout's hit-testing once it has been
 * caught lying.
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
     * A [LabelView] with an explicit line-breaking contract and exact caret placement inside
     * ligatures.
     *
     * Line breaking, for every run:
     * - a line ends at the last legal line-break opportunity that fits, judged from the paragraph
     *   around the run rather than from the run alone (see [LineBreakOpportunities]);
     * - a word that starts on a line but does not fit moves to the next line whole;
     * - only a word wider than an empty line is cut inside, and only at a grapheme-cluster boundary.
     *
     * Hit-testing, for a shaped run (the `TextLayout`-backed painter Swing uses for bidi text,
     * combining marks and ligatures): the run's own carets are checked with [ShapedCarets.areSound].
     * A sound run keeps the standard implementation for everything, with exact corrections where the
     * standard one falls short -- the positions inside a ligature, which the JDK collapses onto the
     * ligature's trailing edge, are spread across the glyph so each one has a caret and a click of
     * its own. Text that no configured font shapes soundly never gets this far: the font-fallback pass
     * ([ShapingAwareFallback]) moves it to a font that does, and a paragraph where none does is laid
     * out by [WrappingParagraphView]'s last-resort strategy with a [MeasuredRunPainter] instead.
     */
    private class SafeLabelView(elem: Element) : LabelView(elem) {

        private val graphemeBoundary = GraphemeBoundary()

        /**
         * This run's carets, worked out once per painter. A view keeps its painter across layouts
         * but is handed a new one whenever the flow strategy re-lays it out or an attribute change
         * gives it a different font, and the carets of the old painter say nothing about the new.
         */
        private var stops: CaretStops? = null
        private var stopsPainter: GlyphView.GlyphPainter? = null

        /** Answers for a shaped run caught unsound despite everything above; created only if one is. */
        private var measured: MeasuredRunPainter? = null

        /**
         * Carets at every cluster boundary of a shaped run, relative to the run's own leading edge.
         *
         * @property boundaries document offsets, ascending, from [getStartOffset] to [getEndOffset].
         * @property reported what the run's own layout answers for each boundary.
         * @property spread [reported] with ligature components spread across their glyph.
         */
        private class CaretStops(
            val boundaries: IntArray,
            val reported: FloatArray,
            val spread: FloatArray,
            val sound: Boolean,
        ) {
            /** Index of [offset] in [boundaries] if it is a ligature component this class places itself. */
            fun spreadIndexOf(offset: Int): Int {
                val i = boundaries.binarySearch(offset)
                return if (i >= 0 && spread[i] != reported[i]) i else -1
            }
        }

        /**
         * The standard painter, except inside a paragraph laid out by the last-resort strategy, which
         * needs every run measured on its own. A fragment inherits its painter from the view it was cut
         * from, so this only has to decide for views that already belong to a paragraph.
         */
        override fun checkPainter() {
            val paragraph = enclosingParagraph()
            if (paragraph != null) {
                val current = glyphPainter
                if (paragraph.measuresRuns && current !is MeasuredRunPainter) setGlyphPainter(MeasuredRunPainter())
                else if (!paragraph.measuresRuns && current is MeasuredRunPainter) setGlyphPainter(null)
            }
            super.checkPainter()
        }

        private fun enclosingParagraph(): WrappingParagraphView? {
            var view = parent
            while (view != null && view !is WrappingParagraphView) view = view.parent
            return view as? WrappingParagraphView
        }

        private fun isShaped(): Boolean {
            checkPainter()
            return glyphPainter?.javaClass?.simpleName == SHAPED_PAINTER
        }

        /** The run's caret stops, or null for a run its painter measures without shaping. */
        private fun caretStops(): CaretStops? {
            if (!isShaped()) return null
            val painter = glyphPainter
            stops?.let { if (stopsPainter === painter) return it }
            val computed = runCatching { measureStops() }.getOrNull()
            stops = computed
            stopsPainter = painter
            return computed
        }

        private fun measureStops(): CaretStops {
            val text = document.getText(startOffset, endOffset - startOffset)
            val boundaries = intArrayOf(startOffset) +
                graphemeBoundary.boundariesAfterStart(text).map { startOffset + it }
            // Wide enough for the run with generous room to spare, rather than an extreme value that
            // risks its own numerical surprises in whatever native text-layout code answers this.
            val alloc = Rectangle(0, 0, (super.getPreferredSpan(X_AXIS) * 4).toInt().coerceAtLeast(1000), 20)
            val reported = FloatArray(boundaries.size) { i ->
                val offset = boundaries[i]
                // Forward bias at this view's own endOffset asks for the position leaning toward the
                // character that starts the *next* view, which is not this view's to answer; backward
                // bias keeps the query inside the range this view actually owns.
                val bias = if (offset >= endOffset) Position.Bias.Backward else Position.Bias.Forward
                super.modelToView(offset, alloc, bias).bounds2D.x.toFloat()
            }
            val relative = boundaries.map { it - startOffset }.toIntArray()
            val sound = ShapedCarets.areSound(text, relative, reported, readsRightToLeft())
            val spread = if (sound) ShapedCarets.spreadLigatures(boundaries, reported, ::standaloneAdvance) else reported
            return CaretStops(boundaries, reported, spread, sound)
        }

        /** Width of `[from, to)` laid out on its own, in this run's font. */
        private fun standaloneAdvance(from: Int, to: Int): Float =
            font.getStringBounds(document.getText(from, to - from), fontMetrics.fontRenderContext).width.toFloat()

        /** The measured answers, for a shaped run whose own are unsound; null while they are sound. */
        private fun unsound(): MeasuredRunPainter? {
            if (caretStops()?.sound != false) return null
            return measured ?: MeasuredRunPainter().also { measured = it }
        }

        override fun setGlyphPainter(p: GlyphView.GlyphPainter?) {
            super.setGlyphPainter(p)
            stops = null
        }

        override fun changedUpdate(e: DocumentEvent, a: Shape?, f: ViewFactory) {
            stops = null
            super.changedUpdate(e, a, f)
        }

        override fun insertUpdate(e: DocumentEvent, a: Shape?, f: ViewFactory) {
            stops = null
            super.insertUpdate(e, a, f)
        }

        override fun removeUpdate(e: DocumentEvent, a: Shape?, f: ViewFactory) {
            stops = null
            super.removeUpdate(e, a, f)
        }

        override fun getMinimumSpan(axis: Int): Float =
            if (axis == X_AXIS) super.getPreferredSpan(axis) / 4 else super.getMinimumSpan(axis)

        // ---------------------------------------------------------------------------------------
        // Line breaking
        // ---------------------------------------------------------------------------------------

        /**
         * Excellent where a legal break fits, Good where only a cut inside a word would -- which the
         * flow strategy then takes only if nothing earlier on the row offers a legal break, meaning
         * the word began the row and is wider than it -- and Bad where not even one cluster fits.
         */
        override fun getBreakWeight(axis: Int, pos: Float, len: Float): Int {
            if (axis != X_AXIS) return super.getBreakWeight(axis, pos, len)
            val p0 = startOffset
            val fit = runCatching { fittingEnd(p0, pos, len) }.getOrDefault(p0)
            if (fit <= p0) return BadBreakWeight
            val spot = LineBreakOpportunities.lastIn(document, p0, hangingEnd(fit))
            return if (spot > p0) ExcellentBreakWeight else GoodBreakWeight
        }

        /**
         * Ends the fragment at the last legal break that fits, or, when there is none, at the last
         * cluster boundary that fits. The flow strategy only asks for the second after
         * [getBreakWeight] has reported Good and no earlier view on the row offered anything better.
         *
         * A result that still does not fit the room it was given is measured again run by run,
         * whatever the painter said: an overflowing line is exactly the visible defect this class
         * exists to prevent.
         */
        override fun breakView(axis: Int, p0: Int, pos: Float, len: Float): View {
            if (axis != X_AXIS) return super.breakView(axis, p0, pos, len)

            var broken = breakAt(p0, runCatching { fittingEnd(p0, pos, len) }.getOrDefault(p0))
            val fits = runCatching { broken.getPreferredSpan(X_AXIS) }.getOrDefault(0f) <= len + OVERFLOW_TOLERANCE
            if (!fits && glyphPainter !is MeasuredRunPainter) {
                val remeasured = measured ?: MeasuredRunPainter().also { measured = it }
                runCatching { breakAt(p0, safeBreakEnd(p0, remeasured.getBoundedPosition(this, p0, pos, len))) }
                    .getOrNull()?.let { broken = it }
            }
            return broken
        }

        private fun breakAt(p0: Int, fit: Int): View {
            val spot = LineBreakOpportunities.lastIn(document, p0, hangingEnd(fit))
            var end = if (spot > p0) spot else fit
            // Never an empty fragment: a line has to make progress, even if one cluster overflows it.
            if (end <= p0) end = clusterCutsFrom(p0, endOffset).firstOrNull() ?: endOffset
            end = end.coerceAtMost(endOffset)
            return if (p0 == startOffset && end == endOffset) this else createFragment(p0, end)
        }

        /** The largest cluster boundary from [p0] whose text fits in [len]. */
        private fun fittingEnd(p0: Int, pos: Float, len: Float): Int {
            val painter = unsound() ?: run { checkPainter(); glyphPainter }
            return safeBreakEnd(p0, painter.getBoundedPosition(this, p0, pos, len))
        }

        /** [fit] extended over the spaces after it, which hang past the line's end rather than start the next. */
        private fun hangingEnd(fit: Int): Int {
            var end = fit
            while (end < endOffset && Character.getType(document.getText(end, 1)[0]) == Character.SPACE_SEPARATOR.toInt()) end++
            return end
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

        /** Cluster boundaries in `(from, to]`, ascending. */
        private fun clusterCutsFrom(from: Int, to: Int): IntArray {
            if (to <= from) return IntArray(0)
            val text = document.getText(from, to - from)
            return graphemeBoundary.boundariesAfterStart(text).map { from + it }.toIntArray()
        }

        // ---------------------------------------------------------------------------------------
        // Caret and mouse
        // ---------------------------------------------------------------------------------------

        override fun modelToView(pos: Int, a: Shape, b: Position.Bias): Shape {
            val stops = caretStops() ?: return super.modelToView(pos, a, b)
            if (!stops.sound) return unsound()!!.modelToView(this, pos, b, a)
            val i = stops.spreadIndexOf(pos)
            if (i < 0) return super.modelToView(pos, a, b)
            val alloc = a.bounds2D
            return Rectangle2D.Double(alloc.x + stops.spread[i], alloc.y, 1.0, alloc.height)
        }

        override fun modelToView(p0: Int, b0: Position.Bias, p1: Int, b1: Position.Bias, a: Shape): Shape {
            (glyphPainter as? MeasuredRunPainter)?.let { return it.rangeShape(this, p0, p1, a) }
            val stops = caretStops() ?: return super.modelToView(p0, b0, p1, b1, a)
            if (!stops.sound) return unsound()!!.rangeShape(this, p0, p1, a)
            if (stops.spreadIndexOf(p0) < 0 && stops.spreadIndexOf(p1) < 0) return super.modelToView(p0, b0, p1, b1, a)
            // A run is unidirectional, so a range inside it is the span between its two carets.
            val from = modelToView(p0, a, b0).bounds2D.x
            val to = modelToView(p1, a, b1).bounds2D.x
            val alloc = a.bounds2D
            return Rectangle2D.Double(minOf(from, to), alloc.y, abs(to - from), alloc.height)
        }

        override fun viewToModel(x: Float, y: Float, a: Shape, biasReturn: Array<Position.Bias>): Int {
            val stops = caretStops() ?: return super.viewToModel(x, y, a, biasReturn)
            if (!stops.sound) return unsound()!!.viewToModel(this, x, y, a, biasReturn)
            // The nearest cluster boundary, from the same carets modelToView draws, so a click on a
            // caret always lands back on that caret's position. The standard painter's own answer is
            // not used even where it would be right: it can only name a ligature's edges, and it
            // moves a click at the end of a view that starts the document one position early (it
            // compares a view-relative index with an absolute offset).
            val along = x - a.bounds2D.x.toFloat()
            val last = if (endsWithNewline()) stops.boundaries.size - 2 else stops.boundaries.size - 1
            var nearest = 0
            var nearestDistance = Float.MAX_VALUE
            for (i in 0..last.coerceAtLeast(0)) {
                val distance = abs(stops.spread[i] - along)
                if (distance < nearestDistance) { nearest = i; nearestDistance = distance }
            }
            val offset = stops.boundaries[nearest]
            // At a view's end the caret belongs to this row, not to the start of whatever follows.
            biasReturn[0] = if (offset == endOffset && offset > startOffset) Position.Bias.Backward else Position.Bias.Forward
            return offset
        }

        private fun endsWithNewline(): Boolean =
            endOffset > startOffset && document.getText(endOffset - 1, 1) == "\n"

        /**
         * The standard step, except that it also stops at every cluster boundary inside a ligature,
         * which the layout on its own treats as no caret position at all and steps straight over.
         */
        override fun getNextVisualPositionFrom(
            pos: Int, b: Position.Bias, a: Shape, direction: Int, biasRet: Array<Position.Bias>,
        ): Int {
            checkPainter()
            unsound()?.let { return it.getNextVisualPositionFrom(this, pos, b, a, direction, biasRet) }
            val next = super.getNextVisualPositionFrom(pos, b, a, direction, biasRet)
            if (pos < 0 || next < 0 || (direction != EAST && direction != WEST)) return next
            val stops = caretStops() ?: return next
            val skipped = stops.boundaries.filter { it in (minOf(pos, next) + 1) until maxOf(pos, next) }
            if (skipped.isEmpty()) return next
            biasRet[0] = Position.Bias.Forward
            return if (next > pos) skipped.first() else skipped.last()
        }
    }

    private companion object {
        /** `GlyphPainter2` is package-private in the JDK, so it can only be recognised by name. */
        const val SHAPED_PAINTER = "GlyphPainter2"

        /** `TextLayoutStrategy`, the flow strategy Swing gives a paragraph of bidi text; package-private too. */
        const val SHAPED_STRATEGY = "TextLayoutStrategy"

        /** Slack allowed before a break result is treated as overflowing the room it was given. */
        const val OVERFLOW_TOLERANCE = 2f
    }

    /**
     * A paragraph that fills the pane's width, and that stops relying on its own shaped layout when
     * that layout is unsound.
     *
     * For bidi text Swing lays a paragraph out with `TextLayoutStrategy`, which measures the whole
     * paragraph as one `TextLayout` to decide where each row ends. When that layout's hit-testing is
     * broken -- past one ligature, every later character reported at the same point -- the row
     * decisions are broken with it, and no view below can undo them: a word the layout believes is as
     * wide as the rest of the line gets cut wherever the measurement ran out. The font-fallback pass
     * avoids this whenever a configured font shapes the text soundly. For the paragraph where none
     * does, [MeasuredFlowStrategy] takes over: rows are built from the views' own break weights, in
     * logical order and then reordered for display, and each run is measured by a [MeasuredRunPainter].
     * The paragraph goes back to the standard strategy as soon as its layout is sound again.
     */
    class WrappingParagraphView(elem: Element) : ParagraphView(elem) {

        private val standardStrategy: FlowStrategy = strategy
        private val measuredStrategy = MeasuredFlowStrategy()

        /** True while this paragraph is laid out by the last-resort strategy. */
        internal var measuresRuns = false
            private set

        override fun loadChildren(f: ViewFactory?) {
            super.loadChildren(f)
            chooseStrategy()
        }

        override fun insertUpdate(changes: DocumentEvent, a: Shape?, f: ViewFactory?) {
            super.insertUpdate(changes, a, f)
            chooseStrategy()
        }

        override fun removeUpdate(changes: DocumentEvent, a: Shape?, f: ViewFactory?) {
            super.removeUpdate(changes, a, f)
            chooseStrategy()
        }

        override fun changedUpdate(changes: DocumentEvent, a: Shape?, f: ViewFactory?) {
            super.changedUpdate(changes, a, f)
            chooseStrategy()
        }

        private fun chooseStrategy() {
            val pool = layoutPool ?: return
            // Only a paragraph Swing lays out as one shaped layout can be misled by one.
            val shaped = standardStrategy.javaClass.simpleName == SHAPED_STRATEGY
            val measure = shaped && !runCatching { shapesSoundly() }.getOrDefault(true)
            if (measure == measuresRuns) return
            measuresRuns = measure
            strategy = if (measure) measuredStrategy else standardStrategy
            // Each run picks the painter that goes with the new strategy the next time it is asked.
            for (i in 0 until pool.viewCount) (pool.getView(i) as? GlyphView)?.glyphPainter = null
            // Brings a TextLayoutStrategy back in step with the text it last saw.
            strategy.insertUpdate(this, null, null)
            layoutChanged(X_AXIS)
            layoutChanged(Y_AXIS)
        }

        /** Whether this paragraph, laid out with its runs' own fonts, has sound hit-testing throughout. */
        private fun shapesSoundly(): Boolean {
            val doc = document as? StyledDocument ?: return true
            val start = startOffset
            var end = minOf(endOffset, doc.length)
            if (end > start && doc.getText(end - 1, 1) == "\n") end--
            if (end - start <= 1) return true
            val text = doc.getText(start, end - start)
            val attributed = AttributedString(text)
            val paragraph = element
            for (i in 0 until paragraph.elementCount) {
                val run = paragraph.getElement(i)
                val from = maxOf(run.startOffset, start) - start
                val to = minOf(run.endOffset, end) - start
                if (to > from) attributed.addAttribute(TextAttribute.FONT, doc.getFont(run.attributes), from, to)
            }
            val sample = doc.getFont(paragraph.getElement(0).attributes)
            val renderContext = container?.getFontMetrics(sample)?.fontRenderContext ?: FontRenderContext(null, true, true)
            val layout = TextLayout(attributed.iterator, renderContext)
            return ShapedCarets.layoutIsSound(text, layout, 0, text.length)
        }

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

    /**
     * Fills rows from the views' own break weights, as the plain flow strategy does, with the two
     * things `TextLayoutStrategy` would otherwise have supplied: no view spans two bidi levels, and
     * each finished row is put into visual order.
     */
    private class MeasuredFlowStrategy : FlowView.FlowStrategy() {

        override fun createView(fv: FlowView, startOffset: Int, spanLeft: Int, rowIndex: Int): View? {
            val logical = getLogicalView(fv)
            val v = logical.getView(logical.getViewIndex(startOffset, Position.Bias.Forward)) ?: return null
            var end = v.endOffset
            val doc = fv.document
            if (doc is AbstractDocument) {
                val bidi = doc.bidiRootElement
                end = minOf(end, bidi.getElement(bidi.getElementIndex(startOffset)).endOffset)
            }
            return if (startOffset == v.startOffset && end == v.endOffset) v else v.createFragment(startOffset, end)
        }

        override fun layoutRow(fv: FlowView, rowIndex: Int, pos: Int): Int {
            val next = super.layoutRow(fv, rowIndex, pos)
            val row = fv.getView(rowIndex)
            val doc = fv.document as? AbstractDocument ?: return next
            val count = row.viewCount
            if (count > 1) {
                val bidi = doc.bidiRootElement
                val levels = ByteArray(count) { i ->
                    val level = StyleConstants.getBidiLevel(bidi.getElement(bidi.getElementIndex(row.getView(i).startOffset)).attributes)
                    level.toByte()
                }
                val views = Array<Any>(count) { row.getView(it) }
                Bidi.reorderVisually(levels, 0, views, 0, count)
                row.replace(0, count, Array(count) { views[it] as View })
            }
            return next
        }
    }
}
