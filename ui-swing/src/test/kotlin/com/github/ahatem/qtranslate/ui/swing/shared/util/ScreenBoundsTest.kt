package com.github.ahatem.qtranslate.ui.swing.shared.util

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * That a saved window position is judged against the displays that exist now.
 *
 * Positions outlive the desktop they were saved on. Restoring one onto a display that has since
 * been unplugged gives a window that is running, focusable and invisible, which looks exactly like
 * the application having failed to start. The rule is deliberately permissive: a window someone
 * pushed half off the edge should stay where they put it.
 */
class ScreenBoundsTest {

    private val primary = Rectangle(0, 0, 1920, 1080)

    /** A display arranged to the left of the primary one, which is where negatives come from. */
    private val leftOfPrimary = Rectangle(-1920, 0, 1920, 1080)

    @Test
    fun `a window on the primary display is reachable`() {
        assertTrue(isPositionReachable(Rectangle(100, 100, 800, 600), listOf(primary)))
    }

    @Test
    fun `a window at negative coordinates is reachable when that display is attached`() {
        // The whole point of dropping the non-negative requirement on Position.
        assertTrue(isPositionReachable(Rectangle(-1500, 200, 800, 600), listOf(primary, leftOfPrimary)))
    }

    @Test
    fun `the same window is not reachable once that display is gone`() {
        assertFalse(isPositionReachable(Rectangle(-1500, 200, 800, 600), listOf(primary)))
    }

    @Test
    fun `a window deliberately pushed half off the edge is left alone`() {
        // Overlaps the primary by 1520px of its width. A stricter "fully visible" rule would
        // yank this back and undo something the user did on purpose.
        assertTrue(isPositionReachable(Rectangle(1520, 400, 800, 600), listOf(primary)))
    }

    @Test
    fun `a sliver too small to grab does not count as reachable`() {
        // Two pixels of overlap is visible in the arithmetic and useless to a person.
        assertFalse(isPositionReachable(Rectangle(1918, 400, 800, 600), listOf(primary)))
    }

    @Test
    fun `a window entirely below the desktop is not reachable`() {
        assertFalse(isPositionReachable(Rectangle(100, 4000, 800, 600), listOf(primary)))
    }

    @Test
    fun `unknown displays mean the saved position is trusted`() {
        // Headless, or a graphics environment that would not answer. Overriding on no information
        // would move windows for people whose setup is perfectly fine.
        assertTrue(isPositionReachable(Rectangle(-9000, -9000, 800, 600), emptyList()))
    }

    @Test
    fun `a window smaller than the minimum is judged on its own size`() {
        // A window narrower than the grabbable minimum can still be fully visible, and must not be
        // declared unreachable for being small.
        val tiny = Rectangle(10, 10, 60, 20)
        assertTrue(isPositionReachable(tiny, listOf(primary)))
    }

    @Test
    fun `reading the real displays never throws`() {
        // Returns empty rather than failing when there is no display, which is what CI is.
        connectedScreenBounds()
    }
}
