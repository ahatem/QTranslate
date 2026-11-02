package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The capture overlay paints a screenshot across a panel. Robot pixels and Swing panel pixels are
 * not guaranteed to be the same size on HiDPI, and all selection/toolbar values are panel-local.
 * These tests pin the conversions without constructing a window.
 */
class CaptureGeometryTest {

    @Test
    fun `a same-size panel maps the selection one to one`() {
        val screenshot = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        assertEquals(
            Rectangle(20, 10, 50, 30),
            CaptureGeometry.selectionToImage(
                Rectangle(20, 10, 50, 30),
                screenshot,
                Dimension(200, 100),
            ),
        )
    }

    @Test
    fun `a hidpi screenshot maps panel selection into device pixels`() {
        val screenshot = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        assertEquals(
            Rectangle(40, 20, 100, 60),
            CaptureGeometry.selectionToImage(
                Rectangle(20, 10, 50, 30),
                screenshot,
                Dimension(200, 100),
            ),
        )
    }

    @Test
    fun `fractional display scaling rounds outward without dropping edge pixels`() {
        val screenshot = BufferedImage(300, 150, BufferedImage.TYPE_INT_RGB)
        assertEquals(
            Rectangle(15, 15, 75, 45),
            CaptureGeometry.selectionToImage(
                Rectangle(10, 10, 50, 30),
                screenshot,
                Dimension(200, 100),
            ),
        )
    }

    @Test
    fun `drag clamping keeps a move inside the panel and handles negative drags`() {
        assertEquals(
            Rectangle(0, 0, 10, 10),
            CaptureGeometry.clampSelection(Rectangle(-10, -10, 20, 20), Dimension(200, 100)),
        )
        assertEquals(
            Rectangle(180, 80, 20, 20),
            CaptureGeometry.clampSelection(Rectangle(180, 80, 50, 50), Dimension(200, 100)),
        )
    }

    @Test
    fun `a selection at the panel edge is clamped into the source image`() {
        val screenshot = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        assertEquals(
            Rectangle(190, 90, 10, 10),
            CaptureGeometry.selectionToImage(
                Rectangle(190, 90, 50, 50),
                screenshot,
                Dimension(200, 100),
            ),
        )
    }

    @Test
    fun `toolbar uses a fitting display when the selection display is too narrow or short`() {
        val displays = listOf(Rectangle(-200, -100, 200, 600), Rectangle(200, -100, 1000, 600))
        val toolbar = Dimension(300, 40)
        val origin = Point(-200, -100)
        val position = CaptureGeometry.toolbarPosition(Rectangle(50, 100, 40, 30), toolbar, Dimension(1400, 600), displays, origin)
        val onDesktop = Rectangle(position.x + origin.x, position.y + origin.y, toolbar.width, toolbar.height)
        assertTrue(displays[1].contains(onDesktop))
        val shortDisplays = listOf(Rectangle(0, 0, 400, 20), Rectangle(500, 100, 500, 300))
        val second = CaptureGeometry.toolbarPosition(Rectangle(10, 0, 40, 10), toolbar, Dimension(1000, 400), shortDisplays, Point())
        assertTrue(shortDisplays[1].contains(Rectangle(second, toolbar)))
    }

    @Test
    fun `empty selections do not produce an image crop`() {
        val screenshot = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        assertNull(CaptureGeometry.selectionToImage(Rectangle(1, 1, 0, 4), screenshot, Dimension(200, 100)))
        assertNull(CaptureGeometry.selectionToImage(Rectangle(1, 1, 4, 0), screenshot, Dimension(200, 100)))
    }

    @Test
    fun `toolbar prefers below then flips above the selection`() {
        val toolbar = Dimension(100, 30)

        assertEquals(
            Point(65, 52),
            CaptureGeometry.toolbarPosition(Rectangle(100, 10, 30, 30), toolbar, Dimension(300, 200)),
        )
        assertEquals(
            Point(65, 18),
            CaptureGeometry.toolbarPosition(Rectangle(100, 60, 30, 30), toolbar, Dimension(300, 100)),
        )
    }

    @Test
    fun `toolbar stays inside the panel at every edge`() {
        val toolbar = Dimension(100, 30)
        assertEquals(
            Point(0, 0),
            CaptureGeometry.toolbarPosition(Rectangle(0, 0, 10, 10), toolbar, Dimension(120, 20)),
        )
        assertEquals(
            Point(220, 70),
            CaptureGeometry.toolbarPosition(Rectangle(300, 300, 20, 20), toolbar, Dimension(320, 100)),
        )
    }

    @Test
    fun `toolbar placement uses panel-local coordinates, not a virtual desktop origin`() {
        // A panel that starts on a monitor at x = -1920 still has local coordinates from zero.
        // Nothing in the placement API accepts a virtual-screen origin, which is the point.
        assertEquals(
            Point(0, 52),
            CaptureGeometry.toolbarPosition(Rectangle(0, 10, 30, 30), Dimension(90, 30), Dimension(300, 200)),
        )
    }

    @Test
    fun `toolbar stays on a side by side monitor instead of the virtual union`() {
        val displays = listOf(
            Rectangle(0, 0, 1920, 1040),
            Rectangle(1920, 0, 1920, 1040),
        )
        val toolbar = Dimension(300, 40)
        assertToolbarOnRealDisplay(
            selection = Rectangle(2500, 100, 120, 60),
            toolbar = toolbar,
            panel = Dimension(3840, 1080),
            displays = displays,
            origin = Point(0, 0),
        )
    }

    @Test
    fun `toolbar placement handles a monitor at negative x`() {
        val displays = listOf(
            Rectangle(-1920, 0, 1920, 1040),
            Rectangle(0, 0, 1920, 1040),
        )
        val position = CaptureGeometry.toolbarPosition(
            selection = Rectangle(120, 100, 120, 60),
            toolbarSize = Dimension(300, 40),
            panelSize = Dimension(3840, 1040),
            displays = displays,
            virtualOrigin = Point(-1920, 0),
        )
        assertToolbarOnRealDisplay(
            selection = Rectangle(120, 100, 120, 60),
            toolbar = Dimension(300, 40),
            panel = Dimension(3840, 1040),
            displays = displays,
            origin = Point(-1920, 0),
        )
        assertEquals(30, position.x)
    }

    @Test
    fun `toolbar placement handles a monitor above the primary`() {
        val displays = listOf(
            Rectangle(0, -1080, 1920, 1080),
            Rectangle(0, 0, 1920, 1080),
        )
        assertToolbarOnRealDisplay(
            selection = Rectangle(500, 80, 120, 60),
            toolbar = Dimension(300, 40),
            panel = Dimension(1920, 2160),
            displays = displays,
            origin = Point(0, -1080),
        )
    }

    @Test
    fun `toolbar placement handles a monitor below the primary`() {
        val displays = listOf(
            Rectangle(0, 0, 1920, 1080),
            Rectangle(0, 1080, 1920, 1080),
        )
        assertToolbarOnRealDisplay(
            selection = Rectangle(500, 2000, 120, 60),
            toolbar = Dimension(300, 40),
            panel = Dimension(1920, 2160),
            displays = displays,
            origin = Point(0, 0),
        )
    }

    @Test
    fun `toolbar placement avoids the gap in a staggered diagonal arrangement`() {
        val displays = listOf(
            Rectangle(0, 0, 1920, 1080),
            Rectangle(1920, 1080, 1920, 1080),
        )
        assertToolbarOnRealDisplay(
            selection = Rectangle(1800, 1000, 200, 200),
            toolbar = Dimension(300, 40),
            panel = Dimension(3840, 2160),
            displays = displays,
            origin = Point(0, 0),
        )
    }

    @Test
    fun `toolbar placement handles unequal monitor heights`() {
        val displays = listOf(
            Rectangle(0, 0, 1920, 1440),
            Rectangle(1920, 0, 1920, 900),
        )
        assertToolbarOnRealDisplay(
            selection = Rectangle(2000, 800, 120, 80),
            toolbar = Dimension(300, 40),
            panel = Dimension(3840, 1440),
            displays = displays,
            origin = Point(0, 0),
        )
    }

    @Test
    fun `a selection in a virtual desktop gap moves onto a real display`() {
        val displays = listOf(
            Rectangle(0, 0, 1920, 1080),
            Rectangle(1920, 1080, 1920, 1080),
        )
        val toolbar = Dimension(300, 40)
        val position = CaptureGeometry.toolbarPosition(
            selection = Rectangle(2000, 1000, 100, 40),
            toolbarSize = toolbar,
            panelSize = Dimension(3840, 2160),
            displays = displays,
            virtualOrigin = Point(0, 0),
        )
        val panelRect = Rectangle(position, toolbar)
        val desktopRect = Rectangle(panelRect.x, panelRect.y, panelRect.width, panelRect.height)
        assertTrue(
            displays.any { it.contains(desktopRect) },
            "toolbar $desktopRect must be inside a real display, not the virtual-desktop gap",
        )
    }

    @Test
    fun `usable display insets are respected`() {
        val displays = listOf(
            Rectangle(0, 24, 1920, 1016),
            Rectangle(1920, 0, 1920, 1040),
        )
        assertToolbarOnRealDisplay(
            selection = Rectangle(100, 100, 120, 60),
            toolbar = Dimension(300, 40),
            panel = Dimension(3840, 1080),
            displays = displays,
            origin = Point(0, 0),
        )
    }

    @Test
    fun `a selection crossing a monitor boundary uses the display it overlaps most`() {
        val displays = listOf(
            Rectangle(0, 0, 1920, 1080),
            Rectangle(1920, 0, 1920, 1080),
        )
        val toolbar = Dimension(300, 40)
        val position = CaptureGeometry.toolbarPosition(
            selection = Rectangle(1800, 100, 300, 100),
            toolbarSize = toolbar,
            panelSize = Dimension(3840, 1080),
            displays = displays,
            virtualOrigin = Point(0, 0),
        )
        val panelRect = Rectangle(position, toolbar)
        assertTrue(displays[1].contains(panelRect), "the right monitor owns 180px of the selection")
    }

    private fun assertToolbarOnRealDisplay(
        selection: Rectangle,
        toolbar: Dimension,
        panel: Dimension,
        displays: List<Rectangle>,
        origin: Point,
    ) {
        val position = CaptureGeometry.toolbarPosition(
            selection = selection,
            toolbarSize = toolbar,
            panelSize = panel,
            displays = displays,
            virtualOrigin = origin,
        )
        val panelRect = Rectangle(position, toolbar)
        val desktopRect = Rectangle(
            panelRect.x + origin.x,
            panelRect.y + origin.y,
            panelRect.width,
            panelRect.height,
        )
        assertTrue(
            displays.any { it.contains(desktopRect) },
            "toolbar $desktopRect must be fully contained by a real usable display $displays",
        )
    }

    @Test
    fun `fit scale never enlarges past actual size`() {
        assertEquals(1.0, CaptureGeometry.fitScale(Dimension(100, 100), Dimension(400, 400)))
        assertEquals(0.5, CaptureGeometry.fitScale(Dimension(400, 200), Dimension(200, 200)))
    }

    @Test
    fun `image rectangles and view points round trip through a zoom transform`() {
        val offset = Point(15, 20)
        val view = CaptureGeometry.imageRectToView(
            rect = Rectangle(10, 20, 30, 40),
            imageOffset = offset,
            scale = 2.0,
        )!!
        assertEquals(Rectangle(35, 60, 60, 80), view)
        assertEquals(
            Point(10, 20),
            CaptureGeometry.viewPointToImage(Point(35, 60), offset, 2.0),
        )
    }
}
