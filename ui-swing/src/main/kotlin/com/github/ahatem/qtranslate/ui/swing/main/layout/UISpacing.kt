package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.util.UIScale

/**
 * Layout metrics, authored against a 100% display and scaled to the one in use.
 *
 * These are handed straight to `EmptyBorder`, `Dimension` and `dividerSize`, none of which scale
 * on their own — unlike the look-and-feel's own metrics, which FlatLaf already scales. Left
 * unscaled they would come out at half their intended size on a 200% display, next to text and
 * icons drawn at full density.
 *
 * Getters rather than constants so the scale factor is read after the look and feel has resolved
 * it, not whenever this object first happens to be touched.
 */
object UISpacing {
    /** Padding for the main window frame. */
    val PADDING get() = UIScale.scale(12)

    /** Horizontal gap for content. */
    val H_GAP get() = UIScale.scale(12)

    /** Vertical gap between stacked components. */
    val V_GAP get() = UIScale.scale(8)

    val DIVIDER_SIZE get() = UIScale.scale(8)
    val MIN_PANEL_HEIGHT get() = UIScale.scale(150)
    val MIN_PANEL_WIDTH get() = UIScale.scale(200)
    val MIN_EXTRA_HEIGHT get() = UIScale.scale(100)
}
