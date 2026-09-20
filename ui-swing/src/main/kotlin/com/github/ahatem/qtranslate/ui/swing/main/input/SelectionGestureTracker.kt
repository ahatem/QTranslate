package com.github.ahatem.qtranslate.ui.swing.main.input

import java.awt.Point

/**
 * Pure press/drag/release bookkeeping for the floating selection-icon gesture.
 *
 * A gesture counts only once the pointer has travelled [minDistancePx] while the primary
 * button is held; a click alone is not a selection. The release location is owned by the
 * caller (it reports where the pointer was released for popup placement); this tracker only
 * answers whether the press turned into a drag.
 */
class SelectionGestureTracker(
    private val minDistancePx: Double = DEFAULT_MIN_DISTANCE_PX,
) {

    private var start: Point? = null
    private var dragged = false

    fun onPressed(point: Point) {
        start = point
        dragged = false
    }

    fun onMoved(point: Point) {
        val origin = start ?: return
        if (origin.distance(point) >= minDistancePx) dragged = true
    }

    /**
     * Ends the gesture. Returns true when the press had become a drag; always resets, so a
     * press without a matching release cannot leak held state into the next gesture.
     */
    fun onReleased(): Boolean {
        val wasDrag = start != null && dragged
        start = null
        dragged = false
        return wasDrag
    }

    companion object {
        const val DEFAULT_MIN_DISTANCE_PX = 5.0
    }
}
