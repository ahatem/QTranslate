package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
