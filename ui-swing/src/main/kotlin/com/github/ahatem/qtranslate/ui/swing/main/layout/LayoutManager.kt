package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Mounts the window that [SlotLayout] builds into [container], and rebuilds it when the
 * arrangement changes. The panes themselves are never replaced: each build re-parents the same
 * components.
 */
class LayoutManager(
    private val components: ComponentRegistry,
    private val container: JPanel
) {
    private var currentLayout: ArrangedLayout? = null
    private var currentKey: Key? = null
    private var currentIsRtl: Boolean = false

    /** What a build depends on. Values that only restyle a mounted component are left out. */
    private data class Key(val layout: WindowLayout, val effectivePanes: PaneLayout, val isRtl: Boolean)

    companion object {
        /** The pane arrangements the View menu offers. */
        fun getAvailableLayouts(): List<LayoutType> = LayoutType.entries
    }

    /** The arrangement currently mounted, or null before the first build has run. */
    val arranged: ArrangedLayout? get() = currentLayout

    /**
     * Builds [layout] with [effectivePanes] (the panes actually shown once comparison eligibility
     * is applied) unless that arrangement is already mounted.
     */
    fun switchLayout(
        layout: WindowLayout,
        effectivePanes: PaneLayout = layout.panes,
        isRtl: Boolean = currentIsRtl,
    ) {
        val key = Key(
            // The strip's appearance and the density restyle mounted components; they do not
            // change which component sits where, so they never cost a rebuild.
            layout.copy(
                stripAppearance = WindowLayout.DEFAULT.stripAppearance,
                density = WindowLayout.DEFAULT.density,
            ),
            effectivePanes,
            isRtl,
        )
        if (key == currentKey) return
        currentKey = key
        currentIsRtl = isRtl
        SwingUtilities.invokeLater {
            detachAll()
            container.removeAll()

            val arranged = SlotLayout.arrange(components, layout, effectivePanes, isRtl)
            container.add(arranged.rootComponent, BorderLayout.CENTER)
            currentLayout = arranged
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

    /** Shows or hides Extra Output. Region visibility is part of the build itself. */
    fun updateVisibility(config: Configuration) {
        SwingUtilities.invokeLater {
            val arranged = currentLayout ?: return@invokeLater
            val showExtra = config.extraOutputType != ExtraOutputType.None
            arranged.componentRefs.updateExtraOutputVisibility(showExtra, components.extraOutputPanel)
        }
    }
}
