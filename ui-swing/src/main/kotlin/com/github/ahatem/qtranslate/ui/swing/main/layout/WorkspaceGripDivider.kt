package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.util.UIScale
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D

/**
 * The grip every internal workspace split is resized by: Input / Output, the main pane / Extra
 * Output, the Side By Side pair, and Comparison / Extra Output.
 *
 * These splits separate two panes of the same region, so unlike [BoundaryDivider] they draw no line
 * at all. The gutter between the panes stays empty and only three small marks are painted at its
 * centre, in a row along the gutter: across a top/bottom split, down a left/right one. It is the
 * same small centred ellipsis the rest of the desktop chrome uses, painted directly rather than
 * drawn as text.
 *
 * - Rest: the three marks alone, in the muted disabled foreground.
 * - Hover: the marks take the theme's accent and grow slightly about their own centres.
 * - Drag: the same marks in the full accent.
 *
 * There is never a surface behind the marks, and their centres never move, so hovering or dragging
 * changes nothing but their colour and a fraction of a pixel of size.
 */
internal object WorkspaceGripDivider {

    enum class State { REST, HOVER, DRAG }

    // Logical pixels.
    private const val MARK_SIZE = 2.5f
    private const val ACTIVE_MARK_SIZE = 3f
    private const val MARK_PITCH = 5f

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
            g2.color = when (state) {
                State.REST -> DividerTheme.muted()
                State.HOVER -> DividerTheme.translucent(DividerTheme.controlAccent(), 0.8f)
                State.DRAG -> DividerTheme.controlAccent()
            }
            val size = UIScale.scale(if (state == State.REST) MARK_SIZE else ACTIVE_MARK_SIZE)
            val pitch = UIScale.scale(MARK_PITCH)
            val centreX = width / 2f
            val centreY = height / 2f
            for (step in -1..1) {
                val x = if (vertical) centreX else centreX + step * pitch
                val y = if (vertical) centreY + step * pitch else centreY
                g2.fill(Ellipse2D.Float(x - size / 2f, y - size / 2f, size, size))
            }
        } finally {
            g2.dispose()
        }
    }
}
