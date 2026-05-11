package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class LayoutManager(
    private val components: ComponentRegistry,
    private val container: JPanel
) {
    private var currentLayout: ArrangedLayout? = null
    private var currentLayoutId: String? = null
    private var compactStrokes: Triple<KeyStroke?, KeyStroke?, KeyStroke?> = Triple(null, null, null)

    companion object {
        private val strategies = listOf(ClassicLayout, SideBySideLayout, CompactLayout)
        fun getAvailableLayouts(): List<LayoutStrategy> = strategies
    }

    fun getLayoutById(id: String): LayoutStrategy {
        return strategies.find { it.id == id } ?: ClassicLayout
    }

    private var currentIsRtl: Boolean = false

    fun switchLayout(layoutId: String, isRtl: Boolean = currentIsRtl) {
        currentIsRtl = isRtl
        if (layoutId == currentLayoutId) return
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
            components.extraOutputPanel,
            components.translatorSelector,
            components.statusBar
        ).forEach { it.parent?.remove(it) }
    }

    /**
     * Binds (or rebinds) the focus-panel shortcuts on the Compact layout's [JTabbedPane].
     * Also updates the tab tooltip text to show the configured shortcut key (1-E).
     *
     * No-op when the current layout is not Compact. Must be called on the EDT.
     *
     * @param strokes  Keystroke for [input, output, extra] — null means disabled/unbound.
     * @param tooltips Tooltip suffix strings e.g. "(Alt+1)" shown on each tab header.
     * @param actions  Lambdas that switch to the tab AND request focus in the pane.
     */
    fun updateCompactShortcuts(
        strokes: Triple<KeyStroke?, KeyStroke?, KeyStroke?>,
        tooltips: Triple<String, String, String>,
        actions: Triple<() -> Unit, () -> Unit, () -> Unit>
    ) {
        val tabs = (currentLayout?.componentRefs as? LayoutComponentRefs.WithTabs)?.tabbedPane
            ?: return

        // Remove previously registered compact focus strokes before rebinding.
        val im = tabs.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        listOf(compactStrokes.first, compactStrokes.second, compactStrokes.third).forEach { old ->
            old?.let { im.remove(it) }
        }

        val actionList = listOf(actions.first, actions.second, actions.third)
        val strokeList = listOf(strokes.first, strokes.second, strokes.third)
        strokeList.forEachIndexed { i, stroke ->
            val key = "compact-focus-$i"
            val fn  = actionList[i]
            tabs.actionMap.put(key, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    // Switch to the correct tab first, then request focus in the text pane.
                    if (i < tabs.tabCount) tabs.selectedIndex = i
                    fn()
                }
            })
            stroke?.let { im.put(it, key) }
        }
        compactStrokes = strokes

        // Tab tooltips (1-E): show the action name + shortcut hint so it's discoverable.
        val tooltipList = listOf(tooltips.first, tooltips.second, tooltips.third)
        tooltipList.forEachIndexed { i, tip ->
            if (i < tabs.tabCount) tabs.setToolTipTextAt(i, tip)
        }
    }

    /**
     * Selects the tab at [index] in the Compact layout's JTabbedPane.
     * No-op when the current layout is Classic or Side-by-Side (no JTabbedPane).
     * Called by MainContentView.switchToAndFocusInput/Output/ExtraOutput() so the rootPane's
     * LOCAL hotkey binding can switch to the correct tab before requesting focus.
     */
    fun selectCompactTab(index: Int) {
        val tabs = (currentLayout?.componentRefs as? LayoutComponentRefs.WithTabs)?.tabbedPane
            ?: return
        if (index < tabs.tabCount) tabs.selectedIndex = index
    }

    fun updateVisibility(config: Configuration) {
        SwingUtilities.invokeLater {
            if (currentLayoutId == null) return@invokeLater

            components.historyBar.isVisible = config.toolbarVisibility.isHistoryBarVisible
            components.translatorSelector.isVisible = config.toolbarVisibility.isServicesPanelVisible
            components.languageBar.isVisible = config.toolbarVisibility.isLanguageBarVisible
            components.statusBar.isVisible = config.toolbarVisibility.isStatusBarVisible

            val showExtra = config.extraOutputType != ExtraOutputType.None
            currentLayout?.componentRefs?.updateExtraOutputVisibility(showExtra, components.extraOutputPanel)
        }
    }
}