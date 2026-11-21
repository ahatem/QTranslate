package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.ToolbarStyle
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

            // The window is built from the four stored values now, so the chrome follows them
            // rather than the older switches they were migrated from.
            val layout = config.layoutConfig

            // The history bar carries the image and document commands as well as Back and
            // Forward, so it stays on screen for FULL and COMPACT and only goes away when the
            // user asked for no history controls at all.
            components.historyBar.isVisible = layout.toolbarStyle != ToolbarStyle.MINIMAL
            components.translatorSelector.isVisible = layout.isServiceSelectorVisible
            // Every style keeps the language row; it is where the active pair is shown.
            components.languageBar.isVisible = true
            components.statusBar.isVisible = layout.showStatusBar

            val showExtra = config.extraOutputType != ExtraOutputType.None
            currentLayout?.componentRefs?.updateExtraOutputVisibility(showExtra, components.extraOutputPanel)
        }
    }
}
