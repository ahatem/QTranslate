package com.github.ahatem.qtranslate.ui.swing.main.lookup

import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.main.mvi.LookupTool
import com.github.ahatem.qtranslate.ui.swing.main.layout.ComponentRegistry
import com.github.ahatem.qtranslate.ui.swing.main.layout.LayoutManager
import com.github.ahatem.qtranslate.ui.swing.main.layout.WorkspaceDockHost
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The lookup dock: two tools, one at a time, in one region. */
class LookupDockTest {

    private val dictionary = JPanel()
    private val images = JPanel()
    private val selections = mutableListOf<LookupTool>()
    private var closed = 0

    private fun dock(): LookupDock {
        var dock: LookupDock? = null
        SwingUtilities.invokeAndWait {
            dock = LookupDock(dictionary, images, TestIcons.iconManager(), { selections += it }, { closed++ })
                .also { it.setLabels("Dictionary", "Images", "Close") }
        }
        return dock!!
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    // 18, 19
    @Test
    fun `selecting a tool from the state shows it without reporting a click`() {
        val dock = dock()
        onEdt { dock.showTool(LookupTool.IMAGES) }
        assertEquals(LookupTool.IMAGES, dock.selectedTool)
        onEdt { dock.showTool(LookupTool.DICTIONARY) }
        assertEquals(LookupTool.DICTIONARY, dock.selectedTool)
        assertTrue(selections.isEmpty(), "a state-driven change is not the user picking a tab")
    }

    @Test
    fun `the user picking a tab reports which tool`() {
        val dock = dock()
        onEdt { dock.tabsForTest().selectedIndex = 1 }
        assertEquals(listOf(LookupTool.IMAGES), selections)
        onEdt { dock.tabsForTest().selectedIndex = 0 }
        assertEquals(listOf(LookupTool.IMAGES, LookupTool.DICTIONARY), selections)
    }

    // 20
    @Test
    fun `switching tabs reuses the same content instances`() {
        val dock = dock()
        fun contentOf(index: Int) = (dock.tabsForTest().getComponentAt(index) as java.awt.Container).components.single()

        assertSame(dictionary, contentOf(0))
        assertSame(images, contentOf(1))
        onEdt { dock.showTool(LookupTool.IMAGES) }
        onEdt { dock.showTool(LookupTool.DICTIONARY) }
        assertSame(dictionary, contentOf(0), "the dictionary is not rebuilt")
        assertSame(images, contentOf(1), "the pictures are not rebuilt")
    }

    // 21
    @Test
    fun `closing reports once through the one close control`() {
        val dock = dock()
        val close = dock.closeButtonForTest()
        assertEquals("Close", close.toolTipText)
        assertTrue(close.isFocusable, "reachable from the keyboard")
        onEdt { close.doClick(0) }
        assertEquals(1, closed)
    }

    @Test
    fun `closing the dock hides it and leaves the workspace and its layout state alone`() {
        val workspace = JPanel().also { it.name = "workspace" }
        val dockComponent = dock()
        val host = WorkspaceDockHost(workspace, dockComponent)
        onEdt {
            host.setSize(1200, 600)
            host.isDockVisible = true
            host.doLayout()
        }
        val widthWithDock = workspace.width
        assertTrue(dockComponent.isVisible)

        onEdt { host.isDockVisible = false; host.doLayout() }
        assertFalse(dockComponent.isVisible)
        assertEquals(1200, workspace.width, "the workspace takes its width back")
        assertNotNull(workspace.parent, "and is still the same mounted component")
        assertTrue(widthWithDock < 1200)
    }

    // 22
    @Test
    fun `every layout fills the one wrapper the dock host holds`() {
        val leaves = List(8) { JPanel() }
        val registry = ComponentRegistry(
            leaves[0], leaves[1], leaves[2], leaves[3], leaves[4], leaves[5], leaves[6], leaves[7]
        )
        val wrapper = JPanel(BorderLayout())
        val manager = LayoutManager(registry, wrapper)
        listOf("classic", "side_by_side", "comparison").forEach { id ->
            onEdt { manager.switchLayout(WindowLayout(panes = PaneLayout.fromPresetId(id))) }
            onEdt { }
            assertEquals(1, wrapper.componentCount, "$id fills the one wrapper the host holds")
        }
    }
}
