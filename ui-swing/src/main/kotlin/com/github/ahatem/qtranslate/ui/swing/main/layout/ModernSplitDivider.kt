package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.util.UIScale
import java.awt.Color
import java.awt.Graphics
import javax.swing.UIManager

/**
 * The two divider looks the main workspace's draggable seams share, so every one of them reads as
 * one calm interaction language even though they are not all painted identically.
 *
 * [Style.BOUNDARY] is [WorkspaceDockHost]'s own boundary: a hairline that reads as an ordinary
 * border at rest, because that boundary is meant to disappear into the layout until the pointer
 * approaches it. [Style.WORKSPACE] is every surviving internal split (see [ModernSplitPaneUI]):
 * those separate two panes of the *same* region rather than a whole side panel, and a hairline read
 * as invisibly as the dock's would leave them looking undiscoverable, so they rest at a visibly
 * thicker line instead. Both styles thicken further and switch to the theme's accent colour on
 * hover and while dragging, and both paint inside the same fixed strip regardless of state — the
 * caller's hit region is wider than the line for an easy grab, but painting a thicker line never
 * changes that strip's bounds, so hovering never moves or resizes either side.
 */
object ModernSplitDivider {

    enum class Style { BOUNDARY, WORKSPACE }

    /**
     * @param vertical true for the vertical line that separates side-by-side regions; false for the
     *   horizontal line that separates stacked ones.
     */
    fun paint(g: Graphics, width: Int, height: Int, vertical: Boolean, active: Boolean, style: Style) {
        if (width <= 0 || height <= 0) return
        val restPx = if (style == Style.BOUNDARY) 1 else 3
        val activePx = if (style == Style.BOUNDARY) 2 else 5
        val thickness = UIScale.scale(if (active) activePx else restPx).coerceAtLeast(1)
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
