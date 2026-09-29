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

    /**
     * The gutter between two panes of an internal workspace split. Wide enough to grab easily; only
     * [WorkspaceGripDivider]'s small grip marks are painted in it.
     */
    val DIVIDER_SIZE get() = UIScale.scale(10)
    val MIN_PANEL_HEIGHT get() = UIScale.scale(150)
    val MIN_PANEL_WIDTH get() = UIScale.scale(200)
    val MIN_EXTRA_HEIGHT get() = UIScale.scale(100)

    /** Width a text pane keeps to stay comfortable to read and edit next to another one. */
    val SIDE_BY_SIDE_PANEL_WIDTH get() = UIScale.scale(320)

    /** Below this width Side By Side stacks its Input and Output instead of setting them side by side. */
    val SIDE_BY_SIDE_BREAKPOINT get() = 2 * SIDE_BY_SIDE_PANEL_WIDTH + DIVIDER_SIZE

    /**
     * The comfortable width the translation workspace wants while a lookup dock is beside it: enough
     * for the language bar and the translator selector to stay whole, which is what a stacked layout
     * needs at its narrowest. Used only to decide whether the main window should grow before a dock
     * opens; below it the dock still opens, at [WORKSPACE_HARD_MIN_WIDTH] instead.
     */
    val WORKSPACE_COMFORTABLE_WIDTH get() = UIScale.scale(440)

    /** The comfortable width a lookup dock wants: a search field, its button and a readable result. */
    val LOOKUP_DOCK_COMFORTABLE_WIDTH get() = UIScale.scale(280)

    /**
     * The least width the workspace is ever squeezed to. A screen too small for
     * [WORKSPACE_COMFORTABLE_WIDTH] and [LOOKUP_DOCK_COMFORTABLE_WIDTH] together still opens a
     * requested dock; both sides give up comfort for room rather than the dock refusing to show.
     */
    val WORKSPACE_HARD_MIN_WIDTH get() = UIScale.scale(240)

    /** The least width the lookup dock is ever squeezed to, still enough for its search field. */
    val LOOKUP_DOCK_HARD_MIN_WIDTH get() = UIScale.scale(200)

    /** The width the dock first opens at, as a share of the window, before the user has resized it. */
    const val LOOKUP_DOCK_DEFAULT_SHARE = 0.36

    /** The draggable strip between the workspace and the dock; the line drawn in it is thinner. */
    val DOCK_DIVIDER_HIT_WIDTH get() = UIScale.scale(8)
}
