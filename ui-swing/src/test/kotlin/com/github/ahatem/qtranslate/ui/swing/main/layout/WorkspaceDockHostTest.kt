package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.ComponentOrientation
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The dock host arranges the workspace and an optional dock, and nothing else. */
class WorkspaceDockHostTest {

    private class Fixture(rtl: Boolean = false, val hostWidth: Int = 1200) {
        val workspace = JPanel()
        val dock = JPanel()
        val host = WorkspaceDockHost(workspace, dock)

        init {
            SwingUtilities.invokeAndWait {
                host.applyComponentOrientation(
                    if (rtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
                )
                host.setSize(hostWidth, 600)
                host.doLayout()
            }
        }

        fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait {
            block()
            host.doLayout()
        }

        fun resize(width: Int) = edt { host.setSize(width, 600) }

        fun show(visible: Boolean = true) = edt { host.isDockVisible = visible }

        val divider get() = host.dividerForTest()

        private fun mouse(id: Int, xInDivider: Int) = MouseEvent(divider, id, 0L, 0, xInDivider, 10, 1, false)

        /** Presses inside the strip at [grab] and drags the pointer by [delta] pixels along the window. */
        fun drag(delta: Int, grab: Int = 3) = edt {
            divider.dispatchEvent(mouse(MouseEvent.MOUSE_PRESSED, grab))
            divider.dispatchEvent(mouse(MouseEvent.MOUSE_DRAGGED, grab + delta))
        }
    }

    // 7
    @Test
    fun `a hidden dock gives all the width to the workspace`() {
        val f = Fixture()
        assertEquals(f.hostWidth, f.workspace.width)
        assertFalse(f.dock.isVisible)
        assertFalse(f.divider.isVisible)
    }

    // 8
    @Test
    fun `a shown dock leaves both regions their useful width`() {
        val f = Fixture()
        f.show()
        assertTrue(f.dock.isVisible)
        assertTrue(f.workspace.width >= UISpacing.WORKSPACE_MIN_WIDTH)
        assertTrue(f.dock.width >= UISpacing.LOOKUP_DOCK_MIN_WIDTH)
        assertEquals(f.hostWidth, f.workspace.width + f.divider.width + f.dock.width, "nothing is left over or overlapping")
    }

    // 9
    @Test
    fun `dragging the divider changes the dock width`() {
        val f = Fixture()
        f.show()
        val before = f.dock.width
        f.drag(-80)
        assertEquals(before + 80, f.dock.width, "dragging toward the workspace widens the dock")
        assertEquals(before + 80, f.host.rememberedDockWidth)
    }

    // 10
    @Test
    fun `the dock width is clamped at both ends`() {
        val f = Fixture()
        f.show()
        f.drag(-5000)
        assertEquals(f.hostWidth - f.divider.width - UISpacing.WORKSPACE_MIN_WIDTH, f.dock.width, "the workspace keeps its minimum")
        f.drag(5000)
        assertEquals(UISpacing.LOOKUP_DOCK_MIN_WIDTH, f.dock.width, "the dock keeps its minimum")
    }

    // 11
    @Test
    fun `hiding and showing restores the width the user chose`() {
        val f = Fixture()
        f.show()
        f.drag(-100)
        val chosen = f.dock.width
        f.show(false)
        assertFalse(f.dock.isVisible)
        f.show(true)
        assertEquals(chosen, f.dock.width)
    }

    @Test
    fun `a width chosen wide is given back after the window shrinks and grows again`() {
        val f = Fixture()
        f.show()
        f.drag(-150)
        val chosen = f.dock.width
        f.resize(900)
        assertTrue(f.dock.width < chosen, "squeezed while the window is small")
        assertTrue(f.workspace.width >= UISpacing.WORKSPACE_MIN_WIDTH)
        f.resize(1200)
        assertEquals(chosen, f.dock.width)
    }

    // 12
    @Test
    fun `left to right puts the dock at the trailing right edge`() {
        val f = Fixture(rtl = false)
        f.show()
        assertFalse(f.host.isDockOnLeft)
        assertEquals(0, f.workspace.x)
        assertEquals(f.hostWidth, f.dock.x + f.dock.width)
        assertTrue(f.divider.x >= f.workspace.x + f.workspace.width)
    }

    // 13
    @Test
    fun `right to left puts the dock at the trailing left edge`() {
        val f = Fixture(rtl = true)
        f.show()
        assertTrue(f.host.isDockOnLeft)
        assertEquals(0, f.dock.x)
        assertEquals(f.hostWidth, f.workspace.x + f.workspace.width)
        assertEquals(f.dock.width, f.divider.x)
    }

    // 14
    @Test
    fun `the divider follows the pointer in both directions`() {
        val ltr = Fixture(rtl = false)
        ltr.show()
        val ltrBefore = ltr.dock.width
        ltr.drag(+40)
        assertEquals(ltrBefore - 40, ltr.dock.width, "left to right: dragging right shrinks the dock on the right")

        val rtl = Fixture(rtl = true)
        rtl.show()
        val rtlBefore = rtl.dock.width
        rtl.drag(+40)
        assertEquals(rtlBefore + 40, rtl.dock.width, "right to left: dragging right grows the dock on the left")
        rtl.drag(-40)
        assertEquals(rtlBefore, rtl.dock.width, "and dragging back returns it")
    }

    @Test
    fun `the divider is a wide strip with the resize cursor and a thin line`() {
        val f = Fixture()
        f.show()
        assertEquals(UISpacing.DOCK_DIVIDER_HIT_WIDTH, f.divider.width)
        assertEquals(Cursor.E_RESIZE_CURSOR, f.divider.cursor.type)
        assertTrue(f.divider.width >= 6, "a strip wide enough to hit, not just the 1px line")
        assertTrue(f.divider.isVisible && f.divider.height == 600)
    }

    // 16
    @Test
    fun `nothing of the divider remains while the dock is hidden`() {
        val f = Fixture()
        f.show()
        f.show(false)
        assertFalse(f.divider.isVisible)
        assertEquals(f.hostWidth, f.workspace.width)
        assertEquals(1, f.host.components.count { it.isVisible }, "only the workspace is laid out")
    }

    // 17
    @Test
    fun `a window too narrow for both asks for floating and keeps the workspace whole`() {
        val f = Fixture()
        val changes = mutableListOf<Boolean>()
        f.host.onDockabilityChanged = { changes += it }
        f.show()
        assertTrue(f.host.canDock)

        f.resize(f.host.dockingThreshold() - 1)
        SwingUtilities.invokeAndWait { }
        assertFalse(f.host.canDock)
        assertFalse(f.host.isDockPresented)
        assertFalse(f.dock.isVisible, "the dock is not squeezed in")
        assertEquals(f.host.dockingThreshold() - 1, f.workspace.width, "the workspace keeps the whole window")
        assertEquals(listOf(false), changes)

        f.resize(f.host.dockingThreshold() + 200)
        SwingUtilities.invokeAndWait { }
        assertTrue(f.host.canDock)
        assertEquals(listOf(false, true), changes)
        assertTrue(f.dock.isVisible, "and it returns with room")
    }

    @Test
    fun `the threshold is derived from what each side needs`() {
        val f = Fixture()
        assertEquals(
            UISpacing.WORKSPACE_MIN_WIDTH + UISpacing.DOCK_DIVIDER_HIT_WIDTH + UISpacing.LOOKUP_DOCK_MIN_WIDTH,
            f.host.dockingThreshold()
        )
    }

    // 15
    @Test
    fun `the outer dock no longer uses a split pane`() {
        val root = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main")
        val content = File(root, "MainContentView.kt").readText()
        assertFalse("JSplitPane" in content, "MainContentView has no split pane")
        assertFalse("MirroredSplitPane" in content)
        assertTrue("WorkspaceDockHost" in content)
        assertNotNull(File(root, "layout/WorkspaceDockHost.kt").takeIf { it.isFile })
        assertFalse("JSplitPane" in File(root, "layout/WorkspaceDockHost.kt").readText())
    }
}
