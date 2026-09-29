package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.main.mvi.LookupTool
import com.github.ahatem.qtranslate.ui.swing.main.lookup.LookupDock
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import java.awt.BorderLayout
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The outer dock and the inner arrangement are separate concerns and must not fight: the dock is
 * always at the logical trailing edge, and Side By Side keeps its own reading order inside the room
 * the dock leaves, in both directions.
 */
class WorkspaceDirectionMatrixTest {

    private class Rig(layoutId: String, val rtl: Boolean, hostWidth: Int, dockWidth: Int? = null) {
        val leaves = List(8) { JPanel() }
        val input get() = leaves[1]
        val output get() = leaves[3]
        val wrapper = JPanel(BorderLayout())
        val dockContent = JPanel()
        val dock: LookupDock
        val host: WorkspaceDockHost

        init {
            val registry = ComponentRegistry(
                leaves[0], leaves[1], leaves[2], leaves[3], leaves[4], leaves[5], leaves[6], leaves[7]
            )
            val manager = LayoutManager(registry, wrapper)
            var built: LookupDock? = null
            SwingUtilities.invokeAndWait {
                built = LookupDock(dockContent, JPanel(), TestIcons.iconManager(), {}, {})
            }
            dock = built!!
            host = WorkspaceDockHost(wrapper, dock)
            SwingUtilities.invokeAndWait {
                host.applyComponentOrientation(
                    if (rtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
                )
                manager.switchLayout(layoutId, rtl)
            }
            settle()
            SwingUtilities.invokeAndWait {
                dockWidth?.let { host.setDockWidth(it) }
                host.isDockVisible = true
                host.setSize(hostWidth, 800)
            }
            settle()
        }

        /** Lays everything out, then lets the resize events that follow do their work, and lays out again. */
        fun settle() {
            repeat(3) {
                SwingUtilities.invokeAndWait { layoutTree(host) }
                SwingUtilities.invokeAndWait { }
            }
        }

        fun xIn(component: Component) = SwingUtilities.convertPoint(component, 0, 0, host).x
        fun yIn(component: Component) = SwingUtilities.convertPoint(component, 0, 0, host).y
    }

    private companion object {
        fun layoutTree(root: Container) {
            root.doLayout()
            root.components.forEach { if (it is Container) layoutTree(it) }
        }
    }

    private val wide = 2400

    @Test
    fun `the dock is at the trailing edge in every layout and direction`() {
        listOf("classic", "side_by_side", "comparison").forEach { layout ->
            listOf(false, true).forEach { rtl ->
                val rig = Rig(layout, rtl, wide)
                assertTrue(rig.host.isDockPresented, "$layout rtl=$rtl: there is room, so the dock is shown")
                if (rtl) {
                    assertTrue(rig.xIn(rig.dock) < rig.xIn(rig.wrapper), "$layout: right to left puts the dock on the left")
                } else {
                    assertTrue(rig.xIn(rig.dock) > rig.xIn(rig.wrapper), "$layout: left to right puts the dock on the right")
                }
            }
        }
    }

    @Test
    fun `wide side by side keeps its reading order inside the room the dock leaves`() {
        val ltr = Rig("side_by_side", rtl = false, hostWidth = wide)
        assertTrue(ltr.xIn(ltr.input) < ltr.xIn(ltr.output), "left to right: Input then Output")
        assertTrue(ltr.xIn(ltr.output) < ltr.xIn(ltr.dock), "and both before the dock")

        val rtl = Rig("side_by_side", rtl = true, hostWidth = wide)
        assertTrue(rtl.xIn(rtl.output) < rtl.xIn(rtl.input), "right to left: Output is on the left of Input (output=${rtl.xIn(rtl.output)} input=${rtl.xIn(rtl.input)} out.w=${rtl.output.width} in.w=${rtl.input.width} wrapper=${rtl.wrapper.width})")
        assertTrue(rtl.xIn(rtl.dock) < rtl.xIn(rtl.output), "with the dock further left still")
    }

    @Test
    fun `a dock that leaves too little room stacks side by side without moving the dock`() {
        listOf(false, true).forEach { rtl ->
            val hostWidth = 1200
            val dockWidth = 560
            val rig = Rig("side_by_side", rtl, hostWidth, dockWidth)
            val room = rig.wrapper.width
            assertTrue(room < UISpacing.SIDE_BY_SIDE_BREAKPOINT + 2 * UISpacing.PADDING, "the workspace is narrow ($room)")
            assertTrue(rig.yIn(rig.input) < rig.yIn(rig.output), "rtl=$rtl: stacked, Input above Output")
            if (rtl) assertTrue(rig.xIn(rig.dock) < rig.xIn(rig.wrapper))
            else assertTrue(rig.xIn(rig.dock) > rig.xIn(rig.wrapper))
        }
    }

    @Test
    fun `the dictionary and images tabs sit in the same trailing dock`() {
        listOf(false, true).forEach { rtl ->
            val rig = Rig("classic", rtl, wide)
            val before = rig.xIn(rig.dock)
            SwingUtilities.invokeAndWait { rig.dock.showTool(LookupTool.IMAGES) }
            rig.settle()
            assertEquals(LookupTool.IMAGES, rig.dock.selectedTool)
            assertEquals(before, rig.xIn(rig.dock), "switching tools does not move the dock")
            SwingUtilities.invokeAndWait { rig.dock.showTool(LookupTool.DICTIONARY) }
            assertEquals(LookupTool.DICTIONARY, rig.dock.selectedTool)
        }
    }
}
