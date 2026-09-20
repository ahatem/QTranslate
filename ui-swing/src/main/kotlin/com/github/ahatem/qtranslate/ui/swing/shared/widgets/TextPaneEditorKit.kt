package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.textpane.GraphemeBoundary
import javax.swing.text.AbstractDocument
import javax.swing.text.BoxView
import javax.swing.text.ComponentView
import javax.swing.text.Element
import javax.swing.text.IconView
import javax.swing.text.LabelView
import javax.swing.text.ParagraphView
import javax.swing.text.StyleConstants
import javax.swing.text.StyledEditorKit
import javax.swing.text.View
import javax.swing.text.ViewFactory

/**
 * The pane's view layer: a [StyledEditorKit] whose views break lines without splitting a grapheme
 * cluster and let a paragraph fill the width it is given.
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

    private class SafeLabelView(elem: Element) : LabelView(elem) {

        private val graphemeBoundary = GraphemeBoundary()

        override fun getMinimumSpan(axis: Int): Float =
            if (axis == X_AXIS) super.getPreferredSpan(axis) / 4 else super.getMinimumSpan(axis)

        override fun getBreakWeight(axis: Int, pos: Float, len: Float): Int =
            if (axis == X_AXIS) GoodBreakWeight else super.getBreakWeight(axis, pos, len)

        /**
         * The painter's choice can land on a code unit in the middle of a grapheme cluster, so the
         * end it returns is moved back to a cluster boundary before it is used.
         */
        override fun breakView(axis: Int, p0: Int, pos: Float, len: Float): View? {
            if (axis != X_AXIS) return super.breakView(axis, p0, pos, len)

            val standard = super.breakView(axis, p0, pos, len) ?: return null

            if (standard === this) return this

            val end = standard.endOffset
            val safeEnd = safeBreakEnd(p0, end)
            if (safeEnd == end) return standard
            return if (safeEnd > p0) createFragment(p0, safeEnd) else null
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
