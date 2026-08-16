package com.github.ahatem.qtranslate.ui.swing.shared.util

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * A [FlowLayout] subclass that correctly reports [preferredLayoutSize] when items
 * wrap to multiple rows.
 *
 * Standard [FlowLayout.preferredLayoutSize] always returns a single-row height,
 * so its containing GridBagLayout row never grows tall enough to show wrapped
 * items — they simply disappear below the allocated space.
 *
 * This class recalculates preferred height by simulating the actual row breaks
 * for the current container width, matching what the layout engine will actually
 * produce at paint time.
 */
class WrapLayout(
    align: Int = LEADING,
    hgap: Int  = 5,
    vgap: Int  = 5
) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension =
        computeSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension =
        computeSize(target, preferred = false).also { it.width -= (hgap + 1) }

    private fun computeSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            // Use the actual current width if available; fall back to "infinite"
            // on the very first pass (before the component has been sized).
            val containerWidth = target.size.width.takeIf { it > 0 } ?: Int.MAX_VALUE
            val insets = target.insets
            val horizontalInsets = insets.left + insets.right + hgap * 2
            val maxRowWidth = containerWidth - horizontalInsets

            var rowWidth  = 0
            var rowHeight = 0
            var totalWidth  = 0
            var totalHeight = insets.top + insets.bottom + vgap * 2

            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                // Would this component exceed the row limit?
                if (rowWidth > 0 && rowWidth + hgap + d.width > maxRowWidth) {
                    totalWidth   = maxOf(totalWidth, rowWidth)
                    totalHeight += rowHeight + vgap
                    rowWidth  = 0
                    rowHeight = 0
                }
                if (rowWidth > 0) rowWidth += hgap
                rowWidth  += d.width
                rowHeight  = maxOf(rowHeight, d.height)
            }
            // Flush the last row
            totalWidth   = maxOf(totalWidth, rowWidth)
            totalHeight += rowHeight

            return Dimension(totalWidth + horizontalInsets, totalHeight)
        }
    }
}
