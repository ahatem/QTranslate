package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ScreenCaptureController(
    private var currentState: ScreenCaptureState,
    private val onStateChanged: (newState: ScreenCaptureState) -> Unit
) : MouseAdapter() {

    companion object {
        private const val RESIZE_HANDLE_SIZE = 8
    }

    private var dragStartPoint: Point? = null
    private var dragStartSelection: Rectangle? = null
    private var activeMouseAction = MouseAction.NONE

    fun updateState(newState: ScreenCaptureState) {
        this.currentState = newState
    }

    override fun mousePressed(e: MouseEvent) {
        dragStartPoint = e.point
        val currentSelection = currentState.selection

        when (currentState.mode) {
            CaptureMode.IDLE -> {
                activeMouseAction = MouseAction.CROSSHAIR
                val newState = currentState.copy(
                    mode = CaptureMode.SELECTING,
                    selection = Rectangle(e.x, e.y, 0, 0),
                    showButtons = false
                )
                onStateChanged(newState)
            }

            CaptureMode.SELECTED -> {
                activeMouseAction = determineMouseAction(e.point, currentSelection!!)
                dragStartSelection = Rectangle(currentSelection)
                val newState = currentState.copy(showButtons = false)
                onStateChanged(newState)
            }

            else -> {}
        }
    }

    override fun mouseDragged(e: MouseEvent) {
        val start = dragStartPoint ?: return

        val newSelection = when (activeMouseAction) {
            MouseAction.CROSSHAIR -> createSelectionFromPoints(start, e.point)
            MouseAction.MOVE -> moveSelection(dragStartSelection!!, start, e.point)
            else -> resizeSelection(dragStartSelection!!, start, e.point, activeMouseAction)
        }

        onStateChanged(currentState.copy(selection = newSelection))
    }

    override fun mouseReleased(e: MouseEvent) {
        dragStartPoint = null
        dragStartSelection = null

        val newMode =
            if (currentState.selection != null && currentState.selection!!.width > 0 && currentState.selection!!.height > 0) {
                CaptureMode.SELECTED
            } else {
                CaptureMode.IDLE
            }

        val newMouseAction = determineMouseAction(e.point, currentState.selection)

        onStateChanged(
            currentState.copy(
                mode = newMode,
                mouseAction = newMouseAction,
                showButtons = newMode == CaptureMode.SELECTED,
                buttonsPosition = null
            )
        )
    }

    override fun mouseMoved(e: MouseEvent) {
        if (currentState.mode == CaptureMode.SELECTED) {
            val newMouseAction = determineMouseAction(e.point, currentState.selection)
            if (newMouseAction != currentState.mouseAction) {
                onStateChanged(currentState.copy(mouseAction = newMouseAction))
            }
        }
    }

    private fun determineMouseAction(point: Point, selection: Rectangle?): MouseAction {
        if (selection == null || selection.width <= 0 || selection.height <= 0) {
            return MouseAction.CROSSHAIR
        }
        if (!selection.contains(point)) {
            return MouseAction.NONE
        }

        val onWest = abs(point.x - selection.x) < RESIZE_HANDLE_SIZE
        val onEast = abs(point.x - (selection.x + selection.width)) < RESIZE_HANDLE_SIZE
        val onNorth = abs(point.y - selection.y) < RESIZE_HANDLE_SIZE
        val onSouth = abs(point.y - (selection.y + selection.height)) < RESIZE_HANDLE_SIZE

        return when {
            onNorth && onWest -> MouseAction.RESIZE_NW
            onNorth && onEast -> MouseAction.RESIZE_NE
            onSouth && onWest -> MouseAction.RESIZE_SW
            onSouth && onEast -> MouseAction.RESIZE_SE
            onNorth -> MouseAction.RESIZE_N
            onSouth -> MouseAction.RESIZE_S
            onWest -> MouseAction.RESIZE_W
            onEast -> MouseAction.RESIZE_E
            else -> MouseAction.MOVE
        }
    }

    private fun createSelectionFromPoints(p1: Point, p2: Point): Rectangle {
        val x = min(p1.x, p2.x)
        val y = min(p1.y, p2.y)
        val width = abs(p1.x - p2.x)
        val height = abs(p1.y - p2.y)
        return Rectangle(x, y, width, height)
    }

    private fun moveSelection(original: Rectangle, start: Point, current: Point): Rectangle {
        val dx = current.x - start.x
        val dy = current.y - start.y
        return Rectangle(original.x + dx, original.y + dy, original.width, original.height)
    }

    private fun resizeSelection(original: Rectangle, start: Point, current: Point, action: MouseAction): Rectangle {
        var x = original.x
        var y = original.y
        var width = original.width
        var height = original.height
        val dx = current.x - start.x
        val dy = current.y - start.y

        if (action in listOf(MouseAction.RESIZE_W, MouseAction.RESIZE_NW, MouseAction.RESIZE_SW)) {
            x += dx
            width -= dx
        }
        if (action in listOf(MouseAction.RESIZE_E, MouseAction.RESIZE_NE, MouseAction.RESIZE_SE)) {
            width += dx
        }
        if (action in listOf(MouseAction.RESIZE_N, MouseAction.RESIZE_NW, MouseAction.RESIZE_NE)) {
            y += dy
            height -= dy
        }
        if (action in listOf(MouseAction.RESIZE_S, MouseAction.RESIZE_SW, MouseAction.RESIZE_SE)) {
            height += dy
        }

        if (width < 0) {
            x += width
            width = abs(width)
        }
        if (height < 0) {
            y += height
            height = abs(height)
        }

        return Rectangle(x, y, max(width, 0), max(height, 0))
    }
}