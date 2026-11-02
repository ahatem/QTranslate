package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure coordinate conversion for the capture overlay.
 *
 * The overlay paints the screenshot stretched across the panel. A Robot capture can therefore be
 * at a different pixel size from the Swing/logical panel on HiDPI displays, and the virtual
 * desktop's origin is irrelevant here: selections and toolbar placement are already panel-local.
 * Keeping the arithmetic here gives the transform one testable home instead of scattering scale
 * assumptions through painting and event handling.
 */
internal object CaptureGeometry {

    /** Maps a panel-local selection to source-image pixels, clamped to the image. */
    fun selectionToImage(
        selection: Rectangle,
        screenshot: BufferedImage,
        panelSize: Dimension,
    ): Rectangle? {
        if (selection.width <= 0 || selection.height <= 0) return null
        if (panelSize.width <= 0 || panelSize.height <= 0) return null

        val scaleX = screenshot.width.toDouble() / panelSize.width
        val scaleY = screenshot.height.toDouble() / panelSize.height
        val left = floor(selection.x * scaleX).toInt().coerceIn(0, screenshot.width)
        val top = floor(selection.y * scaleY).toInt().coerceIn(0, screenshot.height)
        val right = ceil((selection.x + selection.width) * scaleX).toInt().coerceIn(0, screenshot.width)
        val bottom = ceil((selection.y + selection.height) * scaleY).toInt().coerceIn(0, screenshot.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return Rectangle(left, top, width, height)
    }

    /** Clamps a drag selection to the panel, preserving a usable positive rectangle. */
    fun clampSelection(selection: Rectangle, panelSize: Dimension): Rectangle {
        val maxX = max(0, panelSize.width)
        val maxY = max(0, panelSize.height)
        val left = selection.x.coerceIn(0, maxX)
        val top = selection.y.coerceIn(0, maxY)
        val right = (selection.x + selection.width).coerceIn(left, maxX)
        val bottom = (selection.y + selection.height).coerceIn(top, maxY)
        return Rectangle(left, top, right - left, bottom - top)
    }

    /**
     * Places the action toolbar beside the selection, in panel coordinates.
     *
     * It prefers below, flips above when there is no room, and finally clamps inside the panel.
     * Because the panel spans the virtual desktop, this also fixes the negative-monitor-origin
     * placement bug: no virtual-screen offset is mixed into panel-local values.
     */
    fun toolbarPosition(
        selection: Rectangle,
        toolbarSize: Dimension,
        panelSize: Dimension,
        padding: Int = 12,
    ): Point {
        val maxX = max(0, panelSize.width - toolbarSize.width)
        val maxY = max(0, panelSize.height - toolbarSize.height)
        var x = selection.x + (selection.width - toolbarSize.width) / 2
        var y = selection.y + selection.height + padding
        x = x.coerceIn(0, maxX)
        if (y > maxY) y = selection.y - toolbarSize.height - padding
        y = y.coerceIn(0, maxY)
        return Point(x, y)
    }

    /** The integer scale used to fit an image inside a viewport, never enlarging past actual size. */
    fun fitScale(image: Dimension, viewport: Dimension): Double {
        if (image.width <= 0 || image.height <= 0) return 1.0
        if (viewport.width <= 0 || viewport.height <= 0) return 1.0
        return min(
            viewport.width.toDouble() / image.width,
            viewport.height.toDouble() / image.height,
        ).coerceAtMost(1.0)
    }

    /** Maps a point in a zoomed/offset image painting back to source-image pixels. */
    fun viewPointToImage(
        point: Point,
        imageOffset: Point,
        scale: Double,
    ): Point? {
        if (scale <= 0.0) return null
        val x = ((point.x - imageOffset.x) / scale)
        val y = ((point.y - imageOffset.y) / scale)
        if (x < 0 || y < 0) return null
        return Point(x.toInt(), y.toInt())
    }

    /**
     * Maps an image-pixel rectangle into the painted viewport.
     * Returns null when the rectangle has no area or scale is invalid.
     */
    fun imageRectToView(
        rect: Rectangle,
        imageOffset: Point,
        scale: Double,
    ): Rectangle? {
        if (scale <= 0.0 || rect.width <= 0 || rect.height <= 0) return null
        return Rectangle(
            imageOffset.x + (rect.x * scale).toInt(),
            imageOffset.y + (rect.y * scale).toInt(),
            max(1, (rect.width * scale).toInt()),
            max(1, (rect.height * scale).toInt()),
        )
    }
}
