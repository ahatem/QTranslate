package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicSplitPaneDivider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The main-workspace [JSplitPane] divider: dragging still follows the pointer in a mirrored
 * (right to left) pane, and the real divider switches to its stronger hover grip.
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

    private fun opaquePixels(image: BufferedImage): Int =
        (0 until image.height).sumOf { y -> (0 until image.width).count { x -> painted(image, x, y) } }

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
}
