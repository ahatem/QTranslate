package com.github.ahatem.qtranslate.ui.swing.main.lookup

import com.github.ahatem.qtranslate.core.main.mvi.LookupTool
import com.github.ahatem.qtranslate.ui.swing.main.layout.ComponentRegistry
import com.github.ahatem.qtranslate.ui.swing.main.layout.LayoutManager
import com.github.ahatem.qtranslate.ui.swing.main.layout.WorkspaceDockHost
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import java.awt.BorderLayout
import java.awt.ComponentOrientation
import java.io.File
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

    private fun titles(dock: LookupDock) = (0 until dock.tabsForTest().tabCount).map { dock.tabsForTest().getTitleAt(it) }

    @Test
    fun `it offers exactly the dictionary and the images`() {
        val dock = dock()
        assertEquals(listOf("Dictionary", "Images"), titles(dock))
        assertEquals(LookupTool.DICTIONARY, dock.selectedTool)
    }

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
    fun `every layout is arranged inside the same host`() {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/MainContentView.kt").readText()
        // One host wraps whatever layout is showing, so no layout has a docking path of its own.
        assertTrue("private val dockHost = WorkspaceDockHost(contentWrapper, lookupDock)" in source)
        assertEquals(1, Regex("WorkspaceDockHost\\(").findAll(source).count())

        val layouts = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/layout/Layouts.kt").readText()
        assertFalse("LookupDock" in layouts || "dock" in layouts.lowercase().replace("dockhost", ""), "layouts know nothing of the dock")

        val leaves = List(8) { JPanel() }
        val registry = ComponentRegistry(
            leaves[0], leaves[1], leaves[2], leaves[3], leaves[4], leaves[5], leaves[6], leaves[7]
        )
        val wrapper = JPanel(BorderLayout())
        val manager = LayoutManager(registry, wrapper)
        listOf("classic", "side_by_side", "comparison").forEach { id ->
            onEdt { manager.switchLayout(id) }
            onEdt { }
            assertEquals(1, wrapper.componentCount, "$id fills the one wrapper the host holds")
        }
    }

    // 23
    @Test
    fun `a window without room routes lookups to the floating popups`() {
        val main = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/MainContentView.kt").readText()
        val images = main.substringAfter("fun openImages(").substringBefore("fun openDictionary(")
        assertTrue("if (!dockHost.canDock)" in images && "MainIntent.ShowImageSearch" in images, "images fall back to the popup")
        val dictionary = main.substringAfter("fun openDictionary(").substringBefore("fun toggleDictionary(")
        assertTrue("if (!dockHost.canDock)" in dictionary && "onOpenFloatingDictionary" in dictionary, "the dictionary falls back to its popup")
        assertTrue("MainIntent.OpenLookupDock(LookupTool.IMAGES)" in images)
        assertTrue("MainIntent.OpenLookupDock(LookupTool.DICTIONARY)" in dictionary)
    }

    @Test
    fun `dictionary and images actions select their own tool`() {
        val main = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/MainContentView.kt").readText()
        assertTrue("onSearchImages = { word -> showImagesForWord(word) }" in main, "Search Images from the editors")
        assertTrue("onFindInDictionary = { word -> showDictionaryWithWord(word) }" in main, "Find in Dictionary from the editors")
        val frame = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/MainAppFrame.kt").readText()
        assertTrue("mainContentView.openImages(term, language)" in frame, "the menu's Image Search")
        assertTrue("mainContentView.toggleDictionary(initialWord)" in frame, "the menu's Dictionary")
    }

    @Test
    fun `tabs and the close control mirror in a right to left interface`() {
        val dock = dock()
        onEdt { dock.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT) }
        assertFalse(dock.tabsForTest().componentOrientation.isLeftToRight, "the tab strip mirrors, so the close control at its end does too")
    }
}
