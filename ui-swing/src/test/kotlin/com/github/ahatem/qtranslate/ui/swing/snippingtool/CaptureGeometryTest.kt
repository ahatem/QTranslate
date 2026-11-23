package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure conversions between panel-local selection, screenshot pixels and the virtual desktop. */
class CaptureGeometryTest {

    @Test
    fun `selection maps into image pixels`() {
        class Case(val label: String, val image: Dimension, val selection: Rectangle, val expected: Rectangle)

        val panel = Dimension(200, 100)
        val cases = listOf(
            Case("same size", Dimension(200, 100), Rectangle(20, 10, 50, 30), Rectangle(20, 10, 50, 30)),
            Case("hidpi", Dimension(400, 200), Rectangle(20, 10, 50, 30), Rectangle(40, 20, 100, 60)),
            Case("fractional scale rounds outward", Dimension(300, 150), Rectangle(10, 10, 50, 30), Rectangle(15, 15, 75, 45)),
            Case("edge is clamped into the image", Dimension(200, 100), Rectangle(190, 90, 50, 50), Rectangle(190, 90, 10, 10)),
        )
        for (case in cases) {
            val screenshot = BufferedImage(case.image.width, case.image.height, BufferedImage.TYPE_INT_RGB)
            assertEquals(case.expected, CaptureGeometry.selectionToImage(case.selection, screenshot, panel), case.label)
        }
    }

    @Test
    fun `empty selections do not produce an image crop`() {
        val screenshot = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        assertNull(CaptureGeometry.selectionToImage(Rectangle(1, 1, 0, 4), screenshot, Dimension(200, 100)))
        assertNull(CaptureGeometry.selectionToImage(Rectangle(1, 1, 4, 0), screenshot, Dimension(200, 100)))
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
    fun `toolbar lands fully inside a real display for every monitor arrangement`() {
        class Case(
            val label: String,
            val displays: List<Rectangle>,
            val origin: Point,
            val selection: Rectangle,
            val panel: Dimension,
            val onDisplay: Int? = null,
            val expectedX: Int? = null,
        )

        val toolbar = Dimension(300, 40)
        val sideBySide = listOf(Rectangle(0, 0, 1920, 1040), Rectangle(1920, 0, 1920, 1040))
        val staggered = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(1920, 1080, 1920, 1080))
        val shortDisplays = listOf(Rectangle(0, 0, 400, 20), Rectangle(500, 100, 500, 300))
        val cases = listOf(
            Case("side by side", sideBySide, Point(0, 0), Rectangle(2500, 100, 120, 60), Dimension(3840, 1080)),
            Case(
                "monitor at negative x",
                listOf(Rectangle(-1920, 0, 1920, 1040), Rectangle(0, 0, 1920, 1040)),
                Point(-1920, 0), Rectangle(120, 100, 120, 60), Dimension(3840, 1040),
                expectedX = 30,
            ),
            Case(
                "monitor above",
                listOf(Rectangle(0, -1080, 1920, 1080), Rectangle(0, 0, 1920, 1080)),
                Point(0, -1080), Rectangle(500, 80, 120, 60), Dimension(1920, 2160),
            ),
            Case(
                "monitor below",
                listOf(Rectangle(0, 0, 1920, 1080), Rectangle(0, 1080, 1920, 1080)),
                Point(0, 0), Rectangle(500, 2000, 120, 60), Dimension(1920, 2160),
            ),
            Case("staggered diagonal", staggered, Point(0, 0), Rectangle(1800, 1000, 200, 200), Dimension(3840, 2160)),
            Case("selection in the desktop gap", staggered, Point(0, 0), Rectangle(2000, 1000, 100, 40), Dimension(3840, 2160)),
            Case(
                "unequal heights",
                listOf(Rectangle(0, 0, 1920, 1440), Rectangle(1920, 0, 1920, 900)),
                Point(0, 0), Rectangle(2000, 800, 120, 80), Dimension(3840, 1440),
            ),
            Case(
                "usable insets",
                listOf(Rectangle(0, 24, 1920, 1016), Rectangle(1920, 0, 1920, 1040)),
                Point(0, 0), Rectangle(100, 100, 120, 60), Dimension(3840, 1080),
            ),
            Case(
                "selection crossing a boundary uses the display it overlaps most",
                sideBySide, Point(0, 0), Rectangle(1800, 100, 300, 100), Dimension(3840, 1080),
                onDisplay = 1,
            ),
            Case(
                "narrow selection display falls back to a fitting one",
                listOf(Rectangle(-200, -100, 200, 600), Rectangle(200, -100, 1000, 600)),
                Point(-200, -100), Rectangle(50, 100, 40, 30), Dimension(1400, 600),
                onDisplay = 1,
            ),
            Case(
                "short selection display falls back to a fitting one",
                shortDisplays, Point(0, 0), Rectangle(10, 0, 40, 10), Dimension(1000, 400),
                onDisplay = 1,
            ),
        )
        for (case in cases) {
            val position = CaptureGeometry.toolbarPosition(
                selection = case.selection,
                toolbarSize = toolbar,
                panelSize = case.panel,
                displays = case.displays,
                virtualOrigin = case.origin,
            )
            val desktopRect = Rectangle(position.x + case.origin.x, position.y + case.origin.y, toolbar.width, toolbar.height)
            val owners = case.onDisplay?.let { listOf(case.displays[it]) } ?: case.displays
            assertTrue(owners.any { it.contains(desktopRect) }, "${case.label}: toolbar $desktopRect outside $owners")
            case.expectedX?.let { assertEquals(it, position.x, "${case.label}: x") }
        }
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
