package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.Color
import javax.swing.UIManager

/**
 * The theme colours the workspace's two kinds of draggable seam share: the quiet colour a seam rests
 * in, and the accent it takes while the pointer is on it or dragging it.
 *
 * The seams share this and their active-state conventions, not their geometry: [BoundaryDivider] is
 * a line that marks where the workspace ends, [WorkspaceGripDivider] a grip inside an otherwise empty
 * gutter.
 */
internal object DividerTheme {

    fun rest(): Color =
        UIManager.getColor("Separator.foreground")
            ?: UIManager.getColor("Component.borderColor")
            ?: Color.GRAY

    /** The focus accent the dock boundary takes: as light as a focus ring, which a full-length line needs. */
    fun accent(): Color =
        UIManager.getColor("Component.focusColor")
            ?: UIManager.getColor("Component.accentColor")
            ?: UIManager.getColor("Component.focusedBorderColor")
            ?: Color(0x2D, 0x7D, 0xF6)

    /**
     * The saturated theme accent, for a small control such as the workspace grip: a couple of pixels
     * of the lighter focus accent barely separate from the gutter behind them.
     */
    fun controlAccent(): Color = UIManager.getColor("Component.accentColor") ?: accent()

    /** [color] at [alpha] (0 to 1) of its own opacity. */
    fun translucent(color: Color, alpha: Float): Color =
        Color(color.red, color.green, color.blue, (color.alpha * alpha).toInt().coerceIn(0, 255))
}
