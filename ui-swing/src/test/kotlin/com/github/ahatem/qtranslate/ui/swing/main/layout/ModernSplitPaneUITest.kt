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
 * [WorkspaceGripDivider] -- an empty gutter with a small two-stroke grip at its centre, a compact
 * accent surface behind it on hover and while dragging, and never a line along the gutter -- with no
 * one-touch arrows and no change in the divider's own bounds between states. The Lookup Dock's
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

    @Test
    fun `at rest a horizontal gutter paints only a small centred grip of two parallel strokes`() {
        val image = gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.REST)
        val bounds = paintedBounds(image)!!
        // No line along the gutter: everything painted sits in a short span around the centre.
        assertTrue(bounds.width <= UIScale.scale(22), "grip spans ${bounds.width}px, not the gutter")
        assertTrue(bounds.width >= UIScale.scale(16), "grip spans ${bounds.width}px, too short to read as a grip")
        assertTrue(kotlin.math.abs(bounds.centerX - 200) <= 1.0, "the grip is centred along the gutter")
        assertFalse(painted(image, 10, gutterThickness / 2), "the gutter's ends stay empty")
        // Two strokes with a gap between them, across the gutter at its centre.
        val column = (0 until gutterThickness).map { painted(image, 200, it) }
        val strokes = column.indices.count { it > 0 && column[it] && !column[it - 1] } + if (column[0]) 1 else 0
        assertEquals(2, strokes, "two parallel strokes, got $column")
    }

    @Test
    fun `a left to right gutter paints the same grip turned a quarter`() {
        val horizontal = paintedBounds(gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.HOVER))!!
        val vertical = paintedBounds(gutter(gutterThickness, 400, vertical = true, state = WorkspaceGripDivider.State.HOVER))!!
        assertEquals(horizontal.width, vertical.height)
        assertEquals(horizontal.height, vertical.width)
    }

    @Test
    fun `hover adds a compact surface around the grip, never a band along the gutter`() {
        val rest = gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.REST)
        val hover = gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.HOVER)
        val bounds = paintedBounds(hover)!!
        assertTrue(bounds.width <= UIScale.scale(36), "hover surface spans ${bounds.width}px")
        assertTrue(bounds.height < gutterThickness, "the surface leaves the gutter's edges clear of both panes")
        assertFalse(painted(hover, 10, gutterThickness / 2), "the gutter's ends stay empty on hover")
        assertTrue(paintedBounds(rest)!!.width < bounds.width, "hover shows a surface the rest state does not")
    }

    @Test
    fun `drag is stronger than hover and paints the same geometry`() {
        val hover = gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.HOVER)
        val drag = gutter(400, gutterThickness, vertical = false, state = WorkspaceGripDivider.State.DRAG)
        assertEquals(paintedBounds(hover), paintedBounds(drag), "no geometry change between hover and drag")
        val surfaceX = 200 + UIScale.scale(14)
        val surfaceY = gutterThickness / 2
        val hoverAlpha = Color(hover.getRGB(surfaceX, surfaceY), true).alpha
        val dragAlpha = Color(drag.getRGB(surfaceX, surfaceY), true).alpha
        assertTrue(dragAlpha > hoverAlpha, "the drag surface ($dragAlpha) is stronger than hover ($hoverAlpha)")
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
        assertTrue(paintedBounds(hover)!!.width > paintedBounds(rest)!!.width, "hovering shows the grip's surface")
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
