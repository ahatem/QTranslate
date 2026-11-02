package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle

/**
 * The usable part of a display after subtracting the OS-reserved insets.
 *
 * [screen] is in virtual-screen coordinates, so its origin may be negative. Insets such as a
 * Windows taskbar or a macOS menu bar are removed before the rectangle is used for dragging.
 */
internal fun usableBounds(screen: Rectangle, insets: Insets): Rectangle = Rectangle(
    screen.x + insets.left,
    screen.y + insets.top,
    (screen.width - insets.left - insets.right).coerceAtLeast(0),
    (screen.height - insets.top - insets.bottom).coerceAtLeast(0),
)

/**
 * The union of every usable display, in virtual-screen coordinates.
 *
 * Returns null for an empty list, which is how an unreadable graphics environment is reported.
 */
internal fun virtualDesktopBounds(screens: List<Rectangle>): Rectangle? =
    screens.reduceOrNull { desktop, screen -> desktop.union(screen) }

/**
 * Keeps a window's proposed location on the usable desktop, preserving [edgeInsets] from the
 * desktop's leading and trailing edges.
 *
 * The desktop origin is part of the calculation. Clamping from zero would pull a window on a
 * monitor at negative coordinates back to the primary display (issue #412). The envelope is
 * only the first clamp: if the resulting window would sit entirely in a gap between irregularly
 * arranged displays, it is moved to the nearest usable display instead of being left off-screen.
 */
internal fun constrainToScreens(
    x: Int,
    y: Int,
    componentSize: Dimension,
    screens: List<Rectangle>,
    edgeInsets: Insets,
): Point {
    val desktop = virtualDesktopBounds(screens) ?: return Point(x, y)
    val candidate = clampWindowTo(x, y, componentSize, desktop, edgeInsets)
    val window = Rectangle(candidate, componentSize)

    if (screens.any { screen -> !screen.intersection(window).isEmpty }) return candidate

    val nearest = screens.minByOrNull { screen -> squaredDistance(window, screen) } ?: return candidate
    return clampWindowTo(candidate.x, candidate.y, componentSize, nearest, edgeInsets)
}

private fun clampWindowTo(
    x: Int,
    y: Int,
    componentSize: Dimension,
    bounds: Rectangle,
    edgeInsets: Insets,
): Point {
    val minX = bounds.x + edgeInsets.left
    val minY = bounds.y + edgeInsets.top
    val maxX = (bounds.x + bounds.width - componentSize.width - edgeInsets.right)
        .coerceAtLeast(minX)
    val maxY = (bounds.y + bounds.height - componentSize.height - edgeInsets.bottom)
        .coerceAtLeast(minY)

    return Point(
        x.coerceIn(minX, maxX),
        y.coerceIn(minY, maxY),
    )
}

private fun squaredDistance(window: Rectangle, screen: Rectangle): Long {
    val dx = maxOf(
        0,
        screen.x - window.x - window.width,
        window.x - screen.x - screen.width,
    ).toLong()
    val dy = maxOf(
        0,
        screen.y - window.y - window.height,
        window.y - screen.y - screen.height,
    ).toLong()
    return dx * dx + dy * dy
}
