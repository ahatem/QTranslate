package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.util.UIScale
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D

/**
 * The grip every internal workspace split is resized by: Input / Output, the main pane / Extra
 * Output, the Side By Side pair, and Comparison / Extra Output.
 *
 * These splits separate two panes of the same region, so unlike [BoundaryDivider] they draw no line
 * at all. The gutter between the panes stays empty, and only a small grip is painted at its centre:
 * two short parallel strokes that read as "grab here", not as a separator.
 *
 * - Rest: the two strokes alone, in the quiet separator colour.
 * - Hover: a small capsule of the theme's accent appears behind the grip, and the strokes take the
 *   accent. It is a control's hover surface, sized to the grip, never a band along the gutter.
 * - Drag: the capsule stays and deepens, and the strokes are the full accent.
 *
 * Every state paints the same geometry in the same place, so hovering or dragging never moves
 * anything. The grip lies along the gutter: horizontal strokes for a top/bottom split, the same grip
 * turned a quarter for a left/right one.
 */
internal object WorkspaceGripDivider {

    enum class State { REST, HOVER, DRAG }

    // Logical pixels along and across the gutter, for a top/bottom split.
    private const val STROKE_LENGTH = 18f
    private const val STROKE_THICKNESS = 1.5f
    private const val STROKE_GAP = 2.5f
    private const val SURFACE_LENGTH = 34f
    private const val SURFACE_THICKNESS = 8f

    /**
     * @param vertical true for the vertical gutter between side-by-side panes; false for the
     *   horizontal gutter between stacked ones.
     */
    fun paint(g: Graphics, width: Int, height: Int, vertical: Boolean, state: State) {
        if (width <= 0 || height <= 0) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val centreX = width / 2f
            val centreY = height / 2f
            val accent = DividerTheme.controlAccent()

            if (state != State.REST) {
                g2.color = DividerTheme.translucent(accent, if (state == State.DRAG) 0.24f else 0.12f)
                g2.fill(centred(centreX, centreY, SURFACE_LENGTH, SURFACE_THICKNESS, vertical))
            }

            g2.color = when (state) {
                State.REST -> DividerTheme.rest()
                State.HOVER -> DividerTheme.translucent(accent, 0.7f)
                State.DRAG -> accent
            }
            val offset = UIScale.scale((STROKE_GAP + STROKE_THICKNESS) / 2f)
            for (side in listOf(-1, 1)) {
                val x = if (vertical) centreX + side * offset else centreX
                val y = if (vertical) centreY else centreY + side * offset
                g2.fill(centred(x, y, STROKE_LENGTH, STROKE_THICKNESS, vertical))
            }
        } finally {
            g2.dispose()
        }
    }

    /**
     * A fully rounded bar centred on ([x], [y]), [length] along the gutter and [thickness] across it, in
     * logical pixels, turned a quarter when the gutter is [vertical].
     */
    private fun centred(x: Float, y: Float, length: Float, thickness: Float, vertical: Boolean): RoundRectangle2D.Float {
        val along = UIScale.scale(length)
        val across = UIScale.scale(thickness)
        val w = if (vertical) across else along
        val h = if (vertical) along else across
        return RoundRectangle2D.Float(x - w / 2f, y - h / 2f, w, h, across, across)
    }
}
