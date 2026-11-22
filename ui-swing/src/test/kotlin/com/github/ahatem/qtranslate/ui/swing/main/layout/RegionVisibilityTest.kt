package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every region-placement choice must be reflected in the mounted window, never silently ignored. */
class RegionVisibilityTest {

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Visibility of the history bar, language bar, selector and status bar, and whether each is mounted. */
    private fun visibilities(layout: WindowLayout): List<Boolean> {
        val leaves = List(8) { JPanel() }
        onEdt {
            val container = JPanel(BorderLayout())
            val registry = ComponentRegistry(
                historyBar = leaves[0], inputPanel = leaves[1], languageBar = leaves[2],
                outputPanel = leaves[3], compareBoard = leaves[4], extraOutputPanel = leaves[5],
                translatorSelector = leaves[6], statusBar = leaves[7]
            )
            LayoutManager(registry, container).switchLayout(layout)
        }
        // switchLayout queues invokeLater work; drain it from outside.
        onEdt { }
        return onEdt {
            listOf(0, 2, 6, 7).map { leaves[it].isVisible && (it == 7 || leaves[it].parent != null) }
        }
    }

    @Test
    fun `hidden history hides its bar while the other regions keep their rows`() {
        val v = visibilities(WindowLayout(history = RegionSlot.HIDDEN))
        assertEquals(listOf(false, true, true, true), v, "history bar, language, selector, status")
    }

    @Test
    fun `hidden languages hide the language row`() {
        assertEquals(listOf(true, false, true, true), visibilities(WindowLayout(languages = RegionSlot.HIDDEN)))
    }

    @Test
    fun `hidden services hide the selector`() {
        assertEquals(listOf(true, true, false, true), visibilities(WindowLayout(services = RegionSlot.HIDDEN)))
    }

    @Test
    fun `a comparison board never draws the selector, wherever it is placed`() {
        RegionSlot.entries.forEach { slot ->
            val v = visibilities(WindowLayout(panes = PaneLayout.COMPARISON, services = slot))
            assertEquals(listOf(true, true, false, true), v, "services at $slot under comparison")
        }
    }

    @Test
    fun `the status bar follows its switch`() {
        assertEquals(listOf(true, true, true, false), visibilities(WindowLayout(showStatusBar = false)))
    }

    @Test
    fun `every region hidden leaves only the panes`() {
        val hidden = RegionSlot.HIDDEN
        val v = visibilities(WindowLayout(history = hidden, languages = hidden, services = hidden, showStatusBar = false))
        assertEquals(listOf(false, false, false, false), v)
    }
}
