package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.Rectangle

/**
 * Decides whether the main frame should grow before a lookup docks, and to what bounds.
 *
 * Pure geometry, kept separate from [javax.swing.JFrame] so the monitor-work-area and maximized
 * edge cases can be proven without a live window. The frame owns applying the result.
 */
object DockRoomPlanner {

    /**
     * The new frame bounds to grow into, or null when nothing should change: the frame is
     * maximized, or [current] already has [wantedWidth].
     *
     * Only width changes; height is untouched. Growth never crosses [workArea], so it clamps to
     * whatever the monitor actually has room for rather than refusing to grow at all on a small
     * screen. The frame's leading edge stays put unless growing there would run off the monitor, in
     * which case it shifts left just enough to stay inside — never further, and never right.
     */
    fun plan(current: Rectangle, workArea: Rectangle, wantedWidth: Int, isMaximized: Boolean): Rectangle? {
        if (isMaximized) return null
        val newWidth = wantedWidth.coerceAtMost(workArea.width)
        if (newWidth <= current.width) return null

        val maxX = workArea.x + workArea.width - newWidth
        val x = current.x.coerceIn(workArea.x, maxX.coerceAtLeast(workArea.x))
        return Rectangle(x, current.y, newWidth, current.height)
    }
}
