package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.layoutConfig
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

    fun switchLayout(layoutId: String, isRtl: Boolean = currentIsRtl) {
        val rtlChanged = isRtl != currentIsRtl
        if (layoutId == currentLayoutId && !rtlChanged) return
        currentIsRtl = isRtl
        SwingUtilities.invokeLater {
            detachAll()
            container.removeAll()

            val strategy = getLayoutById(layoutId)
            val arranged = strategy.arrange(components, isRtl)
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

            // The footer is already one value of its own in the layout model, so it is read from
            // there. The history row, the language row and the service panel stay on the older
            // booleans for now: they are on their way to becoming one toolbar *style*, and moving
            // them before that style has a renderer would only rename the problem.
            components.historyBar.isVisible = config.toolbarVisibility.isHistoryBarVisible
            components.translatorSelector.isVisible = config.toolbarVisibility.isServicesPanelVisible
            components.languageBar.isVisible = config.toolbarVisibility.isLanguageBarVisible
            components.statusBar.isVisible = config.layoutConfig().showStatusBar

            val showExtra = config.extraOutputType != ExtraOutputType.None
            currentLayout?.componentRefs?.updateExtraOutputVisibility(showExtra, components.extraOutputPanel)
        }
    }
}
