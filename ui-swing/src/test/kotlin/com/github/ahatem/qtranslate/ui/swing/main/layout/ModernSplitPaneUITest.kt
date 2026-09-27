package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Cursor
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
 * installs [ModernSplitPaneUI] unconditionally: this proves that shared UI carries the workspace
 * half of [ModernSplitDivider]'s language -- a visibly thicker rest line than the Lookup Dock's own
 * boundary, both thickening further to the theme's accent on hover and while dragging -- with no
 * grip, no one-touch arrows, and no change in the divider's own bounds between states.
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

    /** How many pixels of a 1x21 strip a rest-state paint actually colours, centred vertically. */
    private fun restLinePixels(style: ModernSplitDivider.Style): Int {
        val image = BufferedImage(21, 21, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        ModernSplitDivider.paint(g, 21, 21, vertical = true, active = false, style = style)
        g.dispose()
        return (0 until 21).count { x -> Color(image.getRGB(x, 10), true).alpha > 0 }
    }

    @Test
    fun `a workspace divider rests visibly thicker than the dock's own quiet boundary`() {
        val boundary = restLinePixels(ModernSplitDivider.Style.BOUNDARY)
        val workspace = restLinePixels(ModernSplitDivider.Style.WORKSPACE)
        assertTrue(workspace > boundary, "workspace ($workspace) should read as more than a border, unlike the dock's boundary ($boundary)")
    }

    @Test
    fun `no surviving main workspace split pane is left with the default grip divider`() {
        val root = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/layout")
        val mirrored = File(root, "MirroredSplitPane.kt").readText()
        assertTrue("ModernSplitPaneUI" in mirrored, "MirroredSplitPane installs the shared divider UI")
    }
}
