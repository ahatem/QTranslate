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

    /** Width a text pane keeps to stay comfortable to read and edit next to another one. */
    val SIDE_BY_SIDE_PANEL_WIDTH get() = UIScale.scale(320)

    /** Below this width Side By Side stacks its Input and Output instead of setting them side by side. */
    val SIDE_BY_SIDE_BREAKPOINT get() = 2 * SIDE_BY_SIDE_PANEL_WIDTH + DIVIDER_SIZE

    /**
     * The least width the translation workspace keeps while a lookup dock is beside it: enough for
     * the language bar and the translator selector to stay whole, which is what a stacked layout
     * needs at its narrowest.
     */
    val WORKSPACE_MIN_WIDTH get() = UIScale.scale(440)

    /** The least width a lookup dock keeps: a search field, its button and a readable result. */
    val LOOKUP_DOCK_MIN_WIDTH get() = UIScale.scale(280)

    /** The width the dock first opens at, as a share of the window, before the user has resized it. */
    const val LOOKUP_DOCK_DEFAULT_SHARE = 0.36

    /** The draggable strip between the workspace and the dock; the line drawn in it is thinner. */
    val DOCK_DIVIDER_HIT_WIDTH get() = UIScale.scale(8)
}
