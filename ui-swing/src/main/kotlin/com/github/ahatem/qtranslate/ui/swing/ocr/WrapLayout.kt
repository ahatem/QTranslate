package com.github.ahatem.qtranslate.ui.swing.ocr

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * FlowLayout reports a one-row preferred height, so a toolbar that wraps at runtime is clipped
 * by its BorderLayout slot. This variant reports the rows it will actually need, which keeps
 * every workspace control reachable at the compact default window size.
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
            val insets = target.insets
            var maxWidth = target.width - insets.left - insets.right
            if (maxWidth <= 0) maxWidth = Int.MAX_VALUE

            var x = 0
            var y = 0
            var rowHeight = 0
            var widestRow = 0
            target.components.filter { it.isVisible }.forEach { component ->
                val size = if (preferred) component.preferredSize else component.minimumSize
                if (x > 0 && x + size.width + hgap > maxWidth) {
                    widestRow = maxOf(widestRow, x)
                    x = 0
                    y += rowHeight + vgap
                    rowHeight = 0
                }
                x += size.width + hgap
                rowHeight = maxOf(rowHeight, size.height)
            }
            widestRow = maxOf(widestRow, x)
            return Dimension(
                widestRow + insets.left + insets.right,
                y + rowHeight + insets.top + insets.bottom,
            )
        }
    }
}
