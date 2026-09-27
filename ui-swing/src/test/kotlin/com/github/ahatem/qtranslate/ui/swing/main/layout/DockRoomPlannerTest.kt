package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DockRoomPlannerTest {

    private val workArea = Rectangle(0, 0, 1920, 1040)

    @Test
    fun `a frame already wide enough is left alone`() {
        val current = Rectangle(100, 100, 900, 600)
        assertNull(DockRoomPlanner.plan(current, workArea, wantedWidth = 800, isMaximized = false))
    }

    @Test
    fun `a narrow resizable frame grows to the wanted width, keeping its height and position`() {
        val current = Rectangle(100, 100, 600, 500)
        val plan = DockRoomPlanner.plan(current, workArea, wantedWidth = 900, isMaximized = false)
        assertEquals(Rectangle(100, 100, 900, 500), plan)
    }

    @Test
    fun `a maximized frame is never resized`() {
        val current = Rectangle(0, 0, 1920, 1040)
        assertNull(DockRoomPlanner.plan(current, workArea, wantedWidth = 2400, isMaximized = true))
    }

    @Test
    fun `growth clamps to the monitor's work area instead of refusing to grow`() {
        val current = Rectangle(1400, 100, 500, 500)
        val plan = DockRoomPlanner.plan(current, workArea, wantedWidth = 900, isMaximized = false)
        assertEquals(1920, plan!!.x + plan.width, "the frame never grows past the work area's edge")
        assertEquals(1020, plan.x, "it shifts left just enough to stay on screen")
    }

    @Test
    fun `a monitor too small for the wanted width still grows to what is available`() {
        val current = Rectangle(0, 0, 500, 500)
        val small = Rectangle(0, 0, 700, 500)
        val plan = DockRoomPlanner.plan(current, small, wantedWidth = 1200, isMaximized = false)
        assertEquals(700, plan!!.width, "grows to fill the monitor rather than the full wanted width")
    }

    @Test
    fun `a frame already filling a small monitor is left alone`() {
        val current = Rectangle(0, 0, 700, 500)
        val small = Rectangle(0, 0, 700, 500)
        assertNull(DockRoomPlanner.plan(current, small, wantedWidth = 1200, isMaximized = false))
    }

    @Test
    fun `growth never moves the frame to a different monitor's side`() {
        val current = Rectangle(50, 100, 600, 500)
        val plan = DockRoomPlanner.plan(current, workArea, wantedWidth = 900, isMaximized = false)
        assertEquals(50, plan!!.x, "no need to move: it already fits without shifting")
    }
}
