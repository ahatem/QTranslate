package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.util.UIScale
import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Cursor
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JLabel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicSplitPaneDivider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every surviving main-workspace [JSplitPane] gets its divider through [MirroredSplitPane], which
 * installs [ModernSplitPaneUI] unconditionally: this proves that UI resizes through a
 * [WorkspaceGripDivider] -- an empty gutter with three small centred marks, recoloured to the accent
 * on hover and while dragging, with no surface behind them and never a line along the gutter -- with
 * no one-touch arrows and no change in the divider's own bounds between states. The Lookup Dock's
 * [BoundaryDivider] is a different role and keeps its full-length hairline.
 */
class ModernSplitPaneUITest {

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun split(orientation: Int = JSplitPane.HORIZONTAL_SPLIT): JSplitPane = onEdt {
        MirroredSplitPane(orientation, true, JLabel("a"), JLabel("b")).apply {
            setSize(400, 300)
            doLayout()
        }
    }

    private fun divider(pane: JSplitPane): BasicSplitPaneDivider =
        pane.components.filterIsInstance<BasicSplitPaneDivider>().single()

    @Test
    fun `a main workspace split pane installs the shared modern divider`() {
        val pane = split()
        assertTrue(pane.ui is ModernSplitPaneUI, "MirroredSplitPane must use ModernSplitPaneUI")
    }

    @Test
    fun `one touch expand is switched off`() {
        val pane = split()
        assertFalse(pane.isOneTouchExpandable)
    }

    @Test
    fun `a vertical split shows a horizontal resize cursor`() {
        val pane = split(JSplitPane.HORIZONTAL_SPLIT)
        assertEquals(Cursor.E_RESIZE_CURSOR, divider(pane).cursor.type)
    }

    @Test
    fun `a horizontal split shows a vertical resize cursor`() {
        val pane = split(JSplitPane.VERTICAL_SPLIT)
        assertEquals(Cursor.S_RESIZE_CURSOR, divider(pane).cursor.type)
    }

    @Test
    fun `hovering and dragging repaint without changing the divider's bounds`() {
        val pane = split()
        val d = divider(pane)
        val before = d.bounds
        onEdt {
            d.dispatchEvent(java.awt.event.MouseEvent(d, java.awt.event.MouseEvent.MOUSE_ENTERED, 0L, 0, 2, 5, 0, false))
        }
        assertEquals(before, d.bounds, "a hover state must never resize or move the divider")
        onEdt {
            d.dispatchEvent(java.awt.event.MouseEvent(d, java.awt.event.MouseEvent.MOUSE_PRESSED, 0L, 0, 2, 5, 1, false))
        }
        assertEquals(before, d.bounds, "a drag state must never resize or move the divider")
    }

    @Test
    fun `right to left dragging still moves the divider with the pointer`() {
        val pane = onEdt {
            MirroredSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, JLabel("a"), JLabel("b")).apply {
                applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
                isMirrored = true
                setSize(400, 300)
                doLayout()
                leadingResizeWeight = 0.5
                setLeadingProportion(0.5)
            }
        }
        onEdt { pane.doLayout() }
        val before = onEdt { pane.leadingProportion }
        val d = divider(pane)
        onEdt {
            d.dispatchEvent(java.awt.event.MouseEvent(d, java.awt.event.MouseEvent.MOUSE_PRESSED, 0L, 0, 3, 5, 1, false))
            d.dispatchEvent(java.awt.event.MouseEvent(d, java.awt.event.MouseEvent.MOUSE_DRAGGED, 0L, 0, 43, 5, 1, false))
        }
        val after = onEdt { pane.leadingProportion }
        assertTrue(after != before, "dragging must still move the divider in a mirrored pane")
    }

    private fun gutter(width: Int, height: Int, vertical: Boolean, state: WorkspaceGripDivider.State): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        WorkspaceGripDivider.paint(g, width, height, vertical, state)
        g.dispose()
        return image
    }

    private fun painted(image: BufferedImage, x: Int, y: Int) = Color(image.getRGB(x, y), true).alpha > 0

    /** The smallest rectangle holding every painted pixel, or null if nothing was painted. */
    private fun paintedBounds(image: BufferedImage): Rectangle? {
        var bounds: Rectangle? = null
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (!painted(image, x, y)) continue
            bounds = bounds?.apply { add(Rectangle(x, y, 1, 1)) } ?: Rectangle(x, y, 1, 1)
        }
        return bounds
    }

    private val gutterThickness get() = UISpacing.DIVIDER_SIZE

    /** Painted runs along a line: how many separate marks it crosses. */
    private fun marksAlong(image: BufferedImage, horizontal: Boolean, at: Int): Int {
        val length = if (horizontal) image.width else image.height
        var marks = 0
        var inside = false
        for (i in 0 until length) {
            val hit = if (horizontal) painted(image, i, at) else painted(image, at, i)
            if (hit && !inside) marks++
            inside = hit
        }
        return marks
    }

    private fun opaquePixels(image: BufferedImage): Int =
        (0 until image.height).sumOf { y -> (0 until image.width).count { x -> painted(image, x, y) } }

    @Test
    fun `at rest a horizontal gutter paints only three small centred marks in a row`() {
        val image = gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.REST)
        val bounds = paintedBounds(image)!!
        assertEquals(3, marksAlong(image, horizontal = true, at = gutterThickness / 2), "three marks across the gutter")
        assertEquals(1, marksAlong(image, horizontal = false, at = 200), "one row of marks, not stacked strokes")
        // Small marks, compactly spaced, centred, and nothing along the rest of the gutter.
        assertTrue(bounds.height <= UIScale.scale(4), "marks are ${bounds.height}px tall")
        assertTrue(bounds.width <= UIScale.scale(14), "the grip spans ${bounds.width}px, not the gutter")
        assertTrue(kotlin.math.abs(bounds.centerX - 200) <= 1.0, "the grip is centred along the gutter")
        assertTrue(kotlin.math.abs(bounds.centerY - gutterThickness / 2.0) <= 1.0, "the grip is centred across the gutter")
        assertFalse(painted(image, 10, gutterThickness / 2), "the gutter's ends stay empty")
    }

    @Test
    fun `a left to right gutter stacks the same three marks in a column`() {
        val image = gutter(gutterThickness, 400, vertical = true, state = WorkspaceGripDivider.State.REST)
        assertEquals(3, marksAlong(image, horizontal = false, at = gutterThickness / 2), "three marks down the gutter")
        assertEquals(1, marksAlong(image, horizontal = true, at = 200))
        val horizontal = paintedBounds(gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.REST))!!
        val vertical = paintedBounds(image)!!
        assertEquals(horizontal.width, vertical.height)
        assertEquals(horizontal.height, vertical.width)
    }

    @Test
    fun `hover recolours the marks to the accent without any surface behind them`() {
        val rest = gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.REST)
        val hover = gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.HOVER)
        assertEquals(3, marksAlong(hover, horizontal = true, at = gutterThickness / 2), "still three separate marks: no capsule joins them")
        val bounds = paintedBounds(hover)!!
        val restBounds = paintedBounds(rest)!!
        // At most a pixel of growth each side, about the same centres.
        assertTrue(bounds.width - restBounds.width <= 2 * UIScale.scale(1), "hover grows the marks only slightly")
        assertEquals(restBounds.centerX, bounds.centerX, 1.0)
        assertFalse(painted(hover, 200 + UIScale.scale(14), gutterThickness / 2), "nothing is painted beyond the marks")
        assertTrue(Color(hover.getRGB(200, gutterThickness / 2), true) != Color(rest.getRGB(200, gutterThickness / 2), true), "the marks change colour")
    }

    @Test
    fun `drag paints the same marks as hover in a stronger accent`() {
        val hover = gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.HOVER)
        val drag = gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.DRAG)
        assertEquals(paintedBounds(hover), paintedBounds(drag), "no geometry change between hover and drag")
        val centre = gutterThickness / 2
        assertTrue(Color(drag.getRGB(200, centre), true).alpha > Color(hover.getRGB(200, centre), true).alpha, "drag is the full accent")
    }

    @Test
    fun `the real divider paints the grip and switches to its hover state under the pointer`() {
        val pane = split(JSplitPane.VERTICAL_SPLIT)
        val d = divider(pane)
        fun snapshot(): BufferedImage = onEdt {
            BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_ARGB).also { image ->
                val g = image.createGraphics()
                d.paint(g)
                g.dispose()
            }
        }
        val rest = snapshot()
        assertTrue(paintedBounds(rest)!!.width < d.width / 4, "at rest the divider paints a grip, not a line")
        onEdt { d.dispatchEvent(java.awt.event.MouseEvent(d, java.awt.event.MouseEvent.MOUSE_ENTERED, 0L, 0, 2, 5, 0, false)) }
        val hover = snapshot()
        assertTrue(opaquePixels(hover) > opaquePixels(rest), "hovering strengthens the grip")
        assertEquals(paintedBounds(rest)!!.centerX, paintedBounds(hover)!!.centerX, 1.0)
    }

    @Test
    fun `the lookup dock boundary keeps its full length hairline at rest`() {
        val image = BufferedImage(21, 200, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        BoundaryDivider.paint(g, 21, 200, vertical = true, active = false)
        g.dispose()
        val bounds = paintedBounds(image)!!
        assertEquals(200, bounds.height, "the boundary runs the whole seam")
        assertEquals(UIScale.scale(1), bounds.width, "a hairline at rest")
    }

    @Test
    fun `no surviving main workspace split pane is left with the default grip divider`() {
        val root = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/layout")
        val mirrored = File(root, "MirroredSplitPane.kt").readText()
        assertTrue("ModernSplitPaneUI" in mirrored, "MirroredSplitPane installs the shared divider UI")
    }
}
