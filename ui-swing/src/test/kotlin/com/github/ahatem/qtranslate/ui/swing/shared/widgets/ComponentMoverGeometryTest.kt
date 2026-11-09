package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.util.usableBounds
import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Dragging is clamped on the usable virtual desktop.
 *
 * A display left of or above the primary one has negative coordinates. Clamping from zero is
 * what pulled the Quick Translate popup back to the primary display when it was dragged on any
 * other screen (issue #412). These cases pin the desktop origin, the union used for multi-monitor
 * arrangements, the OS-reserved screen insets, and the existing edge-inset and oversized-window
 * behaviour.
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
        screens: List<Rectangle> = listOf(primary),
        size: Dimension = windowSize,
        insets: Insets = noInsets,
    ): Point = constrainToScreens(x, y, size, screens, insets)

    @Test
    fun `primary display at zero keeps the existing clamp`() {
        assertEquals(Point(100, 100), clamp(100, 100))
        assertEquals(Point(0, 0), clamp(-100, -100))
        assertEquals(Point(1120, 480), clamp(5000, 5000))
    }

    @Test
    fun `a display left of primary is a valid negative x coordinate space`() {
        val screens = listOf(primary, leftOfPrimary)

        assertEquals(Rectangle(-1920, 0, 3840, 1080), virtualDesktopBounds(screens))
        assertEquals(
            Point(-1500, 200),
            clamp(-1500, 200, screens),
            "the popup must not snap from a negative monitor to x=0",
        )
        assertEquals(Point(-1920, 200), clamp(-5000, 200, screens))
        assertEquals(Point(1120, 200), clamp(5000, 200, screens))
    }

    @Test
    fun `a display right of primary is a valid positive x coordinate space`() {
        val screens = listOf(primary, rightOfPrimary)

        assertEquals(Rectangle(0, 0, 3840, 1080), virtualDesktopBounds(screens))
        assertEquals(Point(2500, 200), clamp(2500, 200, screens))
        assertEquals(Point(3040, 200), clamp(5000, 200, screens))
        assertEquals(Point(0, 200), clamp(-5000, 200, screens))
    }

    @Test
    fun `a display above primary is a valid negative y coordinate space`() {
        val screens = listOf(primary, abovePrimary)

        assertEquals(Rectangle(0, -1080, 1920, 2160), virtualDesktopBounds(screens))
        assertEquals(
            Point(100, -900),
            clamp(100, -900, screens),
            "the popup must not snap from an upper monitor to y=0",
        )
        assertEquals(Point(100, -1080), clamp(100, -5000, screens))
        assertEquals(Point(100, 480), clamp(100, 5000, screens))
    }

    @Test
    fun `a display below primary extends the positive y coordinate space`() {
        val screens = listOf(primary, belowPrimary)

        assertEquals(Rectangle(0, 0, 1920, 2160), virtualDesktopBounds(screens))
        assertEquals(Point(100, 1500), clamp(100, 1500, screens))
        assertEquals(Point(100, 1560), clamp(100, 5000, screens))
    }

    @Test
    fun `edge insets are measured from the virtual desktop edges`() {
        val screens = listOf(primary, leftOfPrimary)
        val insets = Insets(10, 20, 30, 40) // top, left, bottom, right

        assertEquals(Point(-1900, 10), clamp(-5000, -5000, screens, insets = insets))
        assertEquals(Point(1080, 450), clamp(5000, 5000, screens, insets = insets))
        assertEquals(Point(-1000, 100), clamp(-1000, 100, screens, insets = insets))
    }

    @Test
    fun `a window larger than the desktop stays at the leading edge`() {
        val oversized = Dimension(3000, 2000)
        val insets = Insets(5, 5, 5, 5)

        assertEquals(Point(5, 5), clamp(100, 100, size = oversized, insets = insets))
    }

    @Test
    fun `usable bounds subtract OS insets and keep the negative origin`() {
        val screen = Rectangle(-1920, -100, 1920, 1080)

        assertEquals(
            Rectangle(-1900, -90, 1860, 1040),
            usableBounds(screen, Insets(10, 20, 30, 40)),
        )
    }

    @Test
    fun `a taskbar is not draggable space on any monitor`() {
        val taskbar = Insets(0, 0, 40, 0) // bottom taskbar
        val screens = listOf(
            usableBounds(primary, taskbar),
            usableBounds(leftOfPrimary, taskbar),
        )

        assertEquals(
            Point(100, 440),
            clamp(100, 900, screens),
            "the popup must stop above the taskbar instead of using the raw screen bottom",
        )
        assertEquals(
            Point(-1500, 440),
            clamp(-1500, 900, screens),
            "the negative monitor's taskbar must be respected too",
        )
    }

    @Test
    fun `a gap in an irregular arrangement is not draggable space`() {
        val farRight = Rectangle(2420, 0, 1920, 1080) // 500px gap after the primary
        val screens = listOf(primary, farRight)
        val smallWindow = Dimension(200, 200)

        assertEquals(Rectangle(0, 0, 4340, 1080), virtualDesktopBounds(screens))
        assertEquals(
            Point(2420, 100),
            clamp(2100, 100, screens, smallWindow),
            "a window entirely in the gap must be moved to the nearest real display",
        )
        assertEquals(
            Point(1900, 100),
            clamp(1900, 100, screens, smallWindow),
            "a window still touching a display must not jump across the gap",
        )
    }

    @Test
    fun `an unreadable screen list has no desktop bounds`() {
        assertNull(virtualDesktopBounds(emptyList()))
    }
}
