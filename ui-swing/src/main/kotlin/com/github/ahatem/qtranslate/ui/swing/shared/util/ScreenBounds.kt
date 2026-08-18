package com.github.ahatem.qtranslate.ui.swing.shared.util

import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/**
 * Whether a window restored to [bounds] would land somewhere the user can still reach it.
 *
 * Saved positions outlive the desktop they were saved on. A window put on a second display and
 * closed there has a position that means nothing once that display is unplugged, and restoring it
 * anyway produces a window that is running, focusable and completely invisible, which reads as the
 * application having failed to start.
 *
 * "Reachable" is deliberately weaker than "fully visible": a window the user deliberately pushed
 * half off the edge should stay where they put it. All this asks is that enough of it overlaps a
 * display to be seen and dragged back.
 *
 * @param screens the connected displays. Empty means they could not be read, in which case the
 *   saved position is trusted rather than second-guessed, since overriding on no information would
 *   move windows for people whose setup is fine.
 */
fun isPositionReachable(bounds: Rectangle, screens: List<Rectangle>): Boolean {
    if (screens.isEmpty()) return true
    return screens.any { screen ->
        val visible = screen.intersection(bounds)
        // Rectangle.intersection returns a negative-sized rectangle when there is no overlap,
        // which isEmpty covers.
        !visible.isEmpty &&
            visible.width >= minOf(MIN_VISIBLE_WIDTH, bounds.width) &&
            visible.height >= minOf(MIN_VISIBLE_HEIGHT, bounds.height)
    }
}

/**
 * The bounds of every connected display, or empty if they cannot be read.
 *
 * Empty rather than an exception on a headless or otherwise unusual environment: the callers are
 * all restoring a window position, and none of them has anything useful to do with a failure.
 */
fun connectedScreenBounds(): List<Rectangle> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .map { it.defaultConfiguration.bounds }
}.getOrDefault(emptyList())

/** Roughly a grabbable corner: enough to see the window and drag it somewhere better. */
private const val MIN_VISIBLE_WIDTH = 120
private const val MIN_VISIBLE_HEIGHT = 40
