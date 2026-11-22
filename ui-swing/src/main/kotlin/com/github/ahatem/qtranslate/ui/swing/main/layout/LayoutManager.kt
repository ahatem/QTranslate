package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.HistoryPlacement
import com.github.ahatem.qtranslate.core.settings.data.LanguagePlacement
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.ServicePlacement
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

class LayoutManager(
    private val components: ComponentRegistry,
    private val container: JPanel
) {
    private var currentLayout: ArrangedLayout? = null
    private var currentLayoutId: String? = null

    companion object {
        private val strategies = listOf(ClassicLayout, SideBySideLayout, ComparisonLayout)
        fun getAvailableLayouts(): List<LayoutStrategy> = strategies
    }

    fun getLayoutById(id: String): LayoutStrategy {
        val resolved = LayoutPresetIds.resolve(id)
        return strategies.first { it.id == resolved }
    }

    private var currentIsRtl: Boolean = false
    private var currentHistoryInline: Boolean = false

    fun switchLayout(
        layoutId: String,
        isRtl: Boolean = currentIsRtl,
        historyInline: Boolean = currentHistoryInline
    ) {
        val rtlChanged = isRtl != currentIsRtl
        val inlineChanged = historyInline != currentHistoryInline
        if (layoutId == currentLayoutId && !rtlChanged && !inlineChanged) return
        currentIsRtl = isRtl
        currentHistoryInline = historyInline
        SwingUtilities.invokeLater {
            detachAll()
            container.removeAll()

            val strategy = getLayoutById(layoutId)
            val arranged = strategy.arrange(components, isRtl, historyInline)
            container.add(arranged.rootComponent, BorderLayout.CENTER)
            currentLayout = arranged
            currentLayoutId = layoutId
            arranged.componentRefs.syncExtraOutputState(components.extraOutputPanel)
            container.revalidate()
            container.repaint()
        }
    }

    private fun detachAll() {
        listOf(
            components.historyBar,
            components.inputPanel,
            components.languageBar,
            components.outputPanel,
            components.compareBoard,
            components.extraOutputPanel,
            components.translatorSelector,
            components.statusBar
        ).forEach { it.parent?.remove(it) }
    }

    fun updateVisibility(config: Configuration) {
        SwingUtilities.invokeLater {
            if (currentLayoutId == null) return@invokeLater

            // The window is built from the per-region values, so each region follows its own
            // choice rather than a shared enum or the older switches they were migrated from.
            // The resolved form applies the one documented rule (COMBINED with a relocated
            // language row becomes a top history row), so visibility agrees with arrangement.
            val layout = config.workspaceLayout.effective

            // RELOCATED hides the row but the commands are mounted in the input pane, so the
            // feature stays reachable; COMBINED keeps them in the language row itself.
            components.historyBar.isVisible = layout.history != HistoryPlacement.RELOCATED
            components.translatorSelector.isVisible =
                layout.panes != PaneLayout.COMPARISON && layout.services != ServicePlacement.RELOCATED
            components.languageBar.isVisible = layout.languages == LanguagePlacement.ROW
            components.statusBar.isVisible = layout.showStatusBar

            val showExtra = config.extraOutputType != ExtraOutputType.None
            currentLayout?.componentRefs?.updateExtraOutputVisibility(showExtra, components.extraOutputPanel)
        }
    }
}
