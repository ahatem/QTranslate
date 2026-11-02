package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle

/**
 * The union of every connected display, in virtual-screen coordinates.
 *
 * A display to the left of or above the primary one has a negative origin, so this rectangle
 * keeps those origins rather than normalising them to zero. Returns null for an empty list,
 * which is how an unreadable graphics environment is reported.
 */
internal fun virtualDesktopBounds(screens: List<Rectangle>): Rectangle? =
    screens.reduceOrNull { desktop, screen -> desktop.union(screen) }

/**
 * Keeps a window's proposed location inside [desktop], preserving [edgeInsets] from the
 * desktop's leading and trailing edges.
 *
 * The desktop origin is part of the calculation. Clamping from zero would pull a window on a
 * monitor at negative coordinates back to the primary display (issue #412).
 */
internal fun constrainToDesktop(
    x: Int,
    y: Int,
    componentSize: Dimension,
    desktop: Rectangle,
    edgeInsets: Insets,
): Point {
    val minX = desktop.x + edgeInsets.left
    val minY = desktop.y + edgeInsets.top
    val maxX = (desktop.x + desktop.width - componentSize.width - edgeInsets.right)
        .coerceAtLeast(minX)
    val maxY = (desktop.y + desktop.height - componentSize.height - edgeInsets.bottom)
        .coerceAtLeast(minY)

    return Point(
        x.coerceIn(minX, maxX),
        y.coerceIn(minY, maxY),
    )
}
