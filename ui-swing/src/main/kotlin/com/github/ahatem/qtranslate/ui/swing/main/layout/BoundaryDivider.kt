package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.util.UIScale
import java.awt.Graphics

/**
 * [WorkspaceDockHost]'s boundary between the workspace and the Lookup Dock.
 *
 * It marks where the workspace ends, so it is a line along the whole seam: a hairline at rest that
 * reads as an ordinary border, thickening to the theme's accent on hover and while dragging. The
 * line is painted inside the same fixed strip in every state -- the strip is wider than the line for
 * an easy grab -- so hovering never moves or resizes either side.
 */
internal object BoundaryDivider {

    /**
     * @param vertical true for the vertical line that separates side-by-side regions; false for the
     *   horizontal line that separates stacked ones.
     */
    fun paint(g: Graphics, width: Int, height: Int, vertical: Boolean, active: Boolean) {
        if (width <= 0 || height <= 0) return
        val thickness = UIScale.scale(if (active) 2 else 1).coerceAtLeast(1)
        g.color = if (active) DividerTheme.accent() else DividerTheme.rest()
        if (vertical) {
            g.fillRect(((width - thickness) / 2).coerceAtLeast(0), 0, thickness, height)
        } else {
            g.fillRect(0, ((height - thickness) / 2).coerceAtLeast(0), width, thickness)
        }
    }
}
