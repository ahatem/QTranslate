package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.util.UIScale
import java.awt.Color
import java.awt.Graphics
import javax.swing.UIManager

/**
 * The one divider look every draggable seam in the main workspace shares: [WorkspaceDockHost]'s own
 * boundary and every surviving [javax.swing.JSplitPane] (see [ModernSplitPaneUI]).
 *
 * At rest it is a single hairline, the same colour as an ordinary border, so it reads as part of
 * the layout rather than a control. On hover and while dragging it thickens to the theme's accent
 * colour. Both states paint inside the same fixed strip — the caller's hit region is wider than the
 * line for an easy grab, but painting a thicker line never changes that strip's bounds, so hovering
 * never moves or resizes either side.
 */
object ModernSplitDivider {

    /**
     * @param vertical true for the vertical line that separates side-by-side regions; false for the
     *   horizontal line that separates stacked ones.
     */
    fun paint(g: Graphics, width: Int, height: Int, vertical: Boolean, active: Boolean) {
        if (width <= 0 || height <= 0) return
        val thickness = UIScale.scale(if (active) 2 else 1).coerceAtLeast(1)
        g.color = if (active) accentColor() else restColor()
        if (vertical) {
            g.fillRect(((width - thickness) / 2).coerceAtLeast(0), 0, thickness, height)
        } else {
            g.fillRect(0, ((height - thickness) / 2).coerceAtLeast(0), width, thickness)
        }
    }

    private fun restColor(): Color =
        UIManager.getColor("Separator.foreground")
            ?: UIManager.getColor("Component.borderColor")
            ?: Color.GRAY

    private fun accentColor(): Color =
        UIManager.getColor("Component.focusColor")
            ?: UIManager.getColor("Component.accentColor")
            ?: UIManager.getColor("Component.focusedBorderColor")
            ?: Color(0x2D, 0x7D, 0xF6)
}
