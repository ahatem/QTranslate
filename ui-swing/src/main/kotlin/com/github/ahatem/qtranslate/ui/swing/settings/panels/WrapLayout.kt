package com.github.ahatem.qtranslate.ui.swing.settings.panels

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A [FlowLayout] that reports the height it actually needs once it has wrapped.
 *
 * `FlowLayout` lays children out in rows but computes its preferred size as if they were all on one
 * line, so a grid of tiles inside a scroll pane asked for the width of every tile in a row and the
 * pane happily provided a horizontal scrollbar instead of wrapping. This measures the rows at the
 * target width and answers with their total height.
 *
 * The width it wraps to is the viewport's when it is inside a scroll pane — the container it is
 * installed on is laid out at the viewport's width, so the wrapping it does on screen is the same
 * wrapping it reports. Geometry only: every gap is passed in already scaled, and nothing here knows
 * about colours.
 */
internal class WrapLayout(
    align: Int = LEADING,
    hgap: Int = 0,
    vgap: Int = 0,
) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension = layoutSize(target, preferred = false)

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            var maxWidth = 0
            var rowWidth = 0
            var rowHeight = 0
            var totalHeight = 0
            val hgap = hgap
            val vgap = vgap

            val width = wrapWidth(target)
            for (component in target.components) {
                if (!component.isVisible) continue
                val size = if (preferred) component.preferredSize else component.minimumSize
                // A tile that does not fit on the current row starts a new one, exactly as
                // `FlowLayout` will when it lays the same components out.
                if (rowWidth > 0 && rowWidth + hgap + size.width > width) {
                    maxWidth = maxOf(maxWidth, rowWidth)
                    totalHeight += rowHeight + vgap
                    rowWidth = size.width
                    rowHeight = size.height
                } else {
                    rowWidth = if (rowWidth == 0) size.width else rowWidth + hgap + size.width
                    rowHeight = maxOf(rowHeight, size.height)
                }
            }
            maxWidth = maxOf(maxWidth, rowWidth)
            totalHeight += rowHeight

            val insets = target.insets
            return Dimension(
                maxWidth + insets.left + insets.right + hgap * 2,
                totalHeight + insets.top + insets.bottom + vgap * 2,
            )
        }
    }

    /**
     * The width to wrap at: the viewport's, when this layout is on a scroll pane's view.
     *
     * Falls back to the target's own width, and to "one long row" before the component has been
     * laid out at all — which is what `FlowLayout` itself would report.
     */
    private fun wrapWidth(target: Container): Int {
        val viewport = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
        val viewWidth = (viewport as? JScrollPane)?.viewport?.extentSize?.width ?: 0
        val own = target.width
        return when {
            viewWidth > 0 -> viewWidth
            own > 0 -> own
            else -> Int.MAX_VALUE
        }
    }
}
