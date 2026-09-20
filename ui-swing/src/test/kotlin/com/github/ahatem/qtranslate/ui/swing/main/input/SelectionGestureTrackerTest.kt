package com.github.ahatem.qtranslate.ui.swing.main.input

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionGestureTrackerTest {

    @Test
    fun `click without drag is not a selection`() {
        val tracker = SelectionGestureTracker()
        tracker.onPressed(Point(10, 10))
        assertFalse(tracker.onReleased())
    }

    @Test
    fun `drag past the threshold is a selection`() {
        val tracker = SelectionGestureTracker()
        tracker.onPressed(Point(0, 0))
        tracker.onMoved(Point(3, 3))
        assertFalse(tracker.onReleased())
        tracker.onPressed(Point(0, 0))
        tracker.onMoved(Point(4, 4))
        assertTrue(tracker.onReleased())
    }

    @Test
    fun `sparse coalesced positions still cross the threshold`() {
        // Native motion transport collapses floods to latest values; the tracker only ever
        // sees a thin sample plus the exact release. Threshold and release stay correct.
        val tracker = SelectionGestureTracker()
        tracker.onPressed(Point(0, 0))
        tracker.onMoved(Point(60, 0))
        tracker.onMoved(Point(300, 5))
        assertTrue(tracker.onReleased())
    }

    @Test
    fun `release without press and move without press are inert`() {
        val tracker = SelectionGestureTracker()
        tracker.onMoved(Point(50, 50))
        assertFalse(tracker.onReleased())
    }

    @Test
    fun `press resets a previous drag`() {
        val tracker = SelectionGestureTracker()
        tracker.onPressed(Point(0, 0))
        tracker.onMoved(Point(30, 30))
        tracker.onPressed(Point(5, 5))
        assertFalse(tracker.onReleased())
    }
}
