package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HistoryPlacement
import com.github.ahatem.qtranslate.core.settings.data.LanguagePlacement
import com.github.ahatem.qtranslate.core.settings.data.ServicePlacement
import com.github.ahatem.qtranslate.core.settings.data.withWorkspaceLayout
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

    private fun visibilities(workspace: com.github.ahatem.qtranslate.core.settings.data.WorkspaceLayout): List<Boolean> {
        val leaves = List(8) { JPanel() }
        onEdt {
            val container = JPanel(BorderLayout())
            val registry = ComponentRegistry(
                historyBar = leaves[0], inputPanel = leaves[1], languageBar = leaves[2],
                outputPanel = leaves[3], compareBoard = leaves[4], extraOutputPanel = leaves[5],
                translatorSelector = leaves[6], statusBar = leaves[7]
            )
            val manager = LayoutManager(registry, container)
            manager.switchLayout(workspace.panes.presetId)
            manager.updateVisibility(Configuration.DEFAULT.withWorkspaceLayout(workspace))
        }
        // Both switchLayout and updateVisibility queue invokeLater work; drain it from outside.
        onEdt { }
        return onEdt { listOf(leaves[0].isVisible, leaves[2].isVisible, leaves[6].isVisible, leaves[7].isVisible) }
    }

    @Test
    fun `relocated history hides its bar while the other regions keep their rows`() {
        val v = visibilities(
            com.github.ahatem.qtranslate.core.settings.data.WorkspaceLayout(
                history = HistoryPlacement.RELOCATED
            )
        )
        assertEquals(listOf(false, true, true, true), v, "history bar, language, selector, status")
    }

    @Test
    fun `relocated languages hide the language row`() {
        val v = visibilities(
            com.github.ahatem.qtranslate.core.settings.data.WorkspaceLayout(
                languages = LanguagePlacement.RELOCATED
            )
        )
        assertEquals(listOf(true, false, true, true), v)
    }

    @Test
    fun `relocated services hide the selector`() {
        val v = visibilities(
            com.github.ahatem.qtranslate.core.settings.data.WorkspaceLayout(
                services = ServicePlacement.RELOCATED
            )
        )
        assertEquals(listOf(true, true, false, true), v)
    }
}
