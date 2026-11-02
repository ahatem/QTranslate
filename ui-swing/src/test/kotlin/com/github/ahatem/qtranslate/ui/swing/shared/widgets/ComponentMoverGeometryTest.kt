package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Dragging is clamped in virtual-screen coordinates.
 *
 * A display left of or above the primary one has negative coordinates. Clamping from zero is
 * what pulled the Quick Translate popup back to the primary display when it was dragged on any
 * other screen (issue #412). These cases pin the desktop origin, the union used for multi-monitor
 * arrangements, and the existing edge-inset and oversized-window behaviour.
 */
class ComponentMoverGeometryTest {

    private val primary = Rectangle(0, 0, 1920, 1080)
    private val leftOfPrimary = Rectangle(-1920, 0, 1920, 1080)
    private val rightOfPrimary = Rectangle(1920, 0, 1920, 1080)
    private val abovePrimary = Rectangle(0, -1080, 1920, 1080)
    private val belowPrimary = Rectangle(0, 1080, 1920, 1080)
    private val windowSize = Dimension(800, 600)
    private val noInsets = Insets(0, 0, 0, 0)

    private fun clamp(
        x: Int,
        y: Int,
        desktop: Rectangle = primary,
        size: Dimension = windowSize,
        insets: Insets = noInsets,
    ): Point = constrainToDesktop(x, y, size, desktop, insets)

    @Test
    fun `primary display at zero keeps the existing clamp`() {
        assertEquals(Point(100, 100), clamp(100, 100))
        assertEquals(Point(0, 0), clamp(-100, -100))
        assertEquals(Point(1120, 480), clamp(5000, 5000))
    }

    @Test
    fun `a display left of primary is a valid negative x coordinate space`() {
        val desktop = virtualDesktopBounds(listOf(primary, leftOfPrimary))!!

        assertEquals(Rectangle(-1920, 0, 3840, 1080), desktop)
        assertEquals(
            Point(-1500, 200),
            clamp(-1500, 200, desktop),
            "the popup must not snap from a negative monitor to x=0",
        )
        assertEquals(Point(-1920, 200), clamp(-5000, 200, desktop))
        assertEquals(Point(1120, 200), clamp(5000, 200, desktop))
    }

    @Test
    fun `a display right of primary is a valid positive x coordinate space`() {
        val desktop = virtualDesktopBounds(listOf(primary, rightOfPrimary))!!

        assertEquals(Rectangle(0, 0, 3840, 1080), desktop)
        assertEquals(Point(2500, 200), clamp(2500, 200, desktop))
        assertEquals(Point(3040, 200), clamp(5000, 200, desktop))
        assertEquals(Point(0, 200), clamp(-5000, 200, desktop))
    }

    @Test
    fun `a display above primary is a valid negative y coordinate space`() {
        val desktop = virtualDesktopBounds(listOf(primary, abovePrimary))!!

        assertEquals(Rectangle(0, -1080, 1920, 2160), desktop)
        assertEquals(
            Point(100, -900),
            clamp(100, -900, desktop),
            "the popup must not snap from an upper monitor to y=0",
        )
        assertEquals(Point(100, -1080), clamp(100, -5000, desktop))
        assertEquals(Point(100, 480), clamp(100, 5000, desktop))
    }

    @Test
    fun `a display below primary extends the positive y coordinate space`() {
        val desktop = virtualDesktopBounds(listOf(primary, belowPrimary))!!

        assertEquals(Rectangle(0, 0, 1920, 2160), desktop)
        assertEquals(Point(100, 1500), clamp(100, 1500, desktop))
        assertEquals(Point(100, 1560), clamp(100, 5000, desktop))
    }

    @Test
    fun `edge insets are measured from the virtual desktop edges`() {
        val desktop = virtualDesktopBounds(listOf(primary, leftOfPrimary))!!
        val insets = Insets(10, 20, 30, 40) // top, left, bottom, right

        assertEquals(Point(-1900, 10), clamp(-5000, -5000, desktop, insets = insets))
        assertEquals(Point(1080, 450), clamp(5000, 5000, desktop, insets = insets))
        assertEquals(Point(-1000, 100), clamp(-1000, 100, desktop, insets = insets))
    }

    @Test
    fun `a window larger than the desktop stays at the leading edge`() {
        val oversized = Dimension(3000, 2000)
        val insets = Insets(5, 5, 5, 5)

        assertEquals(Point(5, 5), clamp(100, 100, size = oversized, insets = insets))
    }

    @Test
    fun `an unreadable screen list has no desktop bounds`() {
        assertNull(virtualDesktopBounds(emptyList()))
    }
}
