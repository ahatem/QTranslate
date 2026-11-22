package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.FeatureHost
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.WindowFeature
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import java.awt.*
import javax.swing.*

data class ComponentRegistry(
    val historyBar: JComponent,
    val inputPanel: JComponent,
    val languageBar: JComponent,
    val outputPanel: JComponent,
    val compareBoard: JComponent,
    val extraOutputPanel: JComponent,
    val translatorSelector: JComponent,
    val statusBar: JComponent,
    /**
     * The notification button, lent to the history row while the status bar is off (see
     * [com.github.ahatem.qtranslate.core.settings.data.FeatureHost.HistoryRowEnd]). Null where the
     * window never hosts it there.
     */
    val notificationButton: JComponent? = null,
)

sealed interface LayoutComponentRefs {
    fun updateExtraOutputVisibility(visible: Boolean, extraPanel: JComponent)
    fun syncExtraOutputState(extraPanel: JComponent)

    data class WithSplitPanes(
        val mainSplit: JSplitPane,
        val extraSplit: JSplitPane,
        /**
         * Optional layout-owned boundary above the extra panel (Comparison
         * only). Toggled together with the panel so a hidden Extra Output
         * leaves no stray separation behind.
         */
        val extraBoundary: JComponent? = null
    ) : LayoutComponentRefs {
        override fun updateExtraOutputVisibility(visible: Boolean, extraPanel: JComponent) {
            SwingUtilities.invokeLater {
                val wasContinuous = extraSplit.isContinuousLayout
                extraSplit.isContinuousLayout = false
                extraPanel.isVisible = visible
                extraBoundary?.isVisible = visible
                // Whatever holds the panel in the split goes with it: a hidden panel inside a visible
                // holder still claims the holder's minimum height, and leaves an empty band.
                extraSplit.bottomComponent?.isVisible = visible
                if (visible) {
                    extraSplit.dividerSize = UISpacing.DIVIDER_SIZE
                    extraSplit.resetToPreferredSizes()
                } else {
                    extraSplit.dividerSize = 0
                    extraSplit.setDividerLocation(1.0)
                }
                extraSplit.isContinuousLayout = wasContinuous
                extraSplit.revalidate()
                extraSplit.repaint()
            }
        }

        override fun syncExtraOutputState(extraPanel: JComponent) {
            updateExtraOutputVisibility(extraPanel.isVisible, extraPanel)
        }
    }
}

data class ArrangedLayout(
    val rootComponent: JComponent,
    val componentRefs: LayoutComponentRefs
)

/** The pane arrangements the View menu offers, with the words it names them by. */
enum class LayoutType(val localizeId: String, val id: String, val panes: PaneLayout) {
    CLASSIC("layout_preset_classic", LayoutPresetIds.CLASSIC, PaneLayout.STACKED),
    SIDE_BY_SIDE("layout_preset_side_by_side", LayoutPresetIds.SIDE_BY_SIDE, PaneLayout.SIDE_BY_SIDE),
    COMPARISON("layout_preset_comparison", LayoutPresetIds.COMPARISON, PaneLayout.COMPARISON);

    companion object {
        /** The entry for [panes]. */
        fun of(panes: PaneLayout): LayoutType = entries.first { it.panes == panes }

        /** The entry a stored id stands for; retired and unknown ids mean Classic. */
        fun fromId(id: String): LayoutType = of(PaneLayout.fromPresetId(id))
    }
}

object LayoutBuilders {
    fun wrapScrollable(component: JComponent): JComponent = JScrollPane(
        component,
        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    ).apply {
        border = BorderFactory.createEmptyBorder()
        isFocusable = false
        verticalScrollBar.isFocusable = false
        viewport.isOpaque = false
    }

    /**
     * The history row: the bar fills the width, its own groups sit at either end. [trailing], when
     * given, is the row's last component at LINE_END.
     */
    fun historyRow(historyBar: JComponent, trailing: JComponent? = null): JComponent {
        if (trailing != null) {
            return JPanel(BorderLayout(UIScale.scale(2), 0)).apply {
                add(historyBar, BorderLayout.CENTER)
                add(trailing, BorderLayout.LINE_END)
            }
        }
        return JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            }
            add(historyBar, gbc)
        }
    }

    fun wrapLanguageBar(languageBar: JComponent): JComponent {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(UISpacing.V_GAP, 0, UISpacing.V_GAP, 0)
            add(languageBar, BorderLayout.CENTER)
        }
    }

    /** The service selector's row. The slot it sits in supplies any side padding. */
    fun serviceRow(translatorSelector: JComponent): JComponent {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(UIScale.scale(2), 0, UIScale.scale(2), 0)
            add(translatorSelector, BorderLayout.CENTER)
        }
    }

    /**
     * Several regions in one row, in the fixed order history, languages, services.
     *
     * The language controls take the middle of the row when they are in it, otherwise the history
     * bar does; a member before that one starts the row at LINE_START and a member after it ends
     * the row at LINE_END. Only LINE_START and LINE_END are used, so a right-to-left window mirrors
     * the row from its structure alone.
     *
     * [trailing], when given, is the row's last component at LINE_END, after every member, so a
     * button that belongs at the end of the row never ends up in its middle.
     */
    fun sharedRow(members: List<Pair<WindowRegion, JComponent>>, trailing: JComponent? = null): JComponent {
        val stretch = members.firstOrNull { it.first == WindowRegion.LANGUAGES }
            ?: members.firstOrNull { it.first == WindowRegion.HISTORY }
            ?: members.first()
        val stretchIndex = members.indexOf(stretch)
        return JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
            border = BorderFactory.createEmptyBorder(UISpacing.V_GAP, 0, UISpacing.V_GAP, 0)
            members.forEachIndexed { index, (_, component) ->
                add(
                    component,
                    when {
                        index == stretchIndex -> BorderLayout.CENTER
                        index < stretchIndex -> BorderLayout.LINE_START
                        else -> BorderLayout.LINE_END
                    }
                )
            }
            trailing?.let { add(it, BorderLayout.LINE_END) }
        }
    }

    /** The rows of one slot, top to bottom, each filling the width. Null when the slot is empty. */
    fun slotColumn(rows: List<JComponent>, horizontalInset: Int = 0): JComponent? {
        if (rows.isEmpty()) return null
        return JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, horizontalInset, 0, horizontalInset)
            val gbc = GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                anchor = GridBagConstraints.LINE_START
                fill = GridBagConstraints.HORIZONTAL
            }
            rows.forEachIndexed { index, row ->
                gbc.gridy = index
                add(row, gbc)
            }
        }
    }

    /** The footer: the bottom slot's rows, if any, above the status bar. */
    fun createBottomBar(bottomSlot: JComponent?, statusBar: JComponent): JComponent {
        return JPanel(BorderLayout(0, UIScale.scale(4))).apply {
            bottomSlot?.let { add(it, BorderLayout.NORTH) }
            add(statusBar, BorderLayout.SOUTH)
        }
    }

    fun createVerticalSplit(
        top: JComponent,
        bottom: JComponent,
        resizeWeight: Double = 0.5,
        topMinHeight: Int = UISpacing.MIN_PANEL_HEIGHT,
        bottomMinHeight: Int = UISpacing.MIN_PANEL_HEIGHT
    ): MirroredSplitPane {
        top.minimumSize = Dimension(0, topMinHeight)
        bottom.minimumSize = Dimension(0, bottomMinHeight)
        return MirroredSplitPane(JSplitPane.VERTICAL_SPLIT, true, top, bottom).apply {
            leadingResizeWeight = resizeWeight
            dividerSize = UISpacing.DIVIDER_SIZE
            clearBorder()
        }
    }
}

/**
 * Builds the main window from a [WindowLayout]: three slot containers and the pane split.
 *
 * Which rows exist and what each holds comes from [WindowLayout.rowPlan] alone, and a slot means
 * the same place whatever the panes:
 *
 * | slot | Stacked | Side by side | Comparison |
 * |---|---|---|---|
 * | TOP | above the input pane | above both columns | above the input pane |
 * | ABOVE_RESULTS | between input and output | above the output column only | above the board |
 * | BOTTOM | below the panes, above the status bar | same | same |
 *
 * A region absent from the plan (hidden, or the services region under Comparison) is not mounted
 * and is made invisible; the window hosts its commands elsewhere, as [WindowLayout.requiredHosts]
 * says.
 */
object SlotLayout {

    fun arrange(
        components: ComponentRegistry,
        layout: WindowLayout,
        effectivePanes: PaneLayout = layout.panes,
        isRtl: Boolean = false,
    ): ArrangedLayout {
        val plan = layout.rowPlan(effectivePanes)
        val placed = plan.values.flatten().flatten().toSet()
        components.historyBar.isVisible = WindowRegion.HISTORY in placed
        components.languageBar.isVisible = WindowRegion.LANGUAGES in placed
        components.translatorSelector.isVisible = WindowRegion.SERVICES in placed
        components.statusBar.isVisible = layout.showStatusBar

        val top = slot(plan, layout, effectivePanes, RegionSlot.TOP, components)
        val aboveResults = slot(plan, layout, effectivePanes, RegionSlot.ABOVE_RESULTS, components)
        // The footer sits outside the padded content, so its rows bring the window's side padding.
        val bottom = slot(plan, layout, effectivePanes, RegionSlot.BOTTOM, components, horizontalInset = UISpacing.PADDING)

        val (panes, refs) = when (effectivePanes) {
            PaneLayout.STACKED -> stacked(components, aboveResults)
            PaneLayout.SIDE_BY_SIDE -> sideBySide(components, aboveResults, isRtl)
            PaneLayout.COMPARISON -> comparison(components, aboveResults)
        }

        val contentPanel = JPanel(BorderLayout(0, UISpacing.V_GAP)).apply {
            border = BorderFactory.createEmptyBorder(
                UISpacing.PADDING,
                UISpacing.PADDING,
                UISpacing.V_GAP,
                UISpacing.PADDING
            )
            top?.let { add(it, BorderLayout.NORTH) }
            add(panes, BorderLayout.CENTER)
        }
        val root = JPanel(BorderLayout()).apply {
            add(contentPanel, BorderLayout.CENTER)
            add(LayoutBuilders.createBottomBar(bottom, components.statusBar), BorderLayout.SOUTH)
        }
        refs.syncExtraOutputState(components.extraOutputPanel)
        return ArrangedLayout(root, refs)
    }

    private fun slot(
        plan: Map<RegionSlot, List<List<WindowRegion>>>,
        layout: WindowLayout,
        effectivePanes: PaneLayout,
        slot: RegionSlot,
        components: ComponentRegistry,
        horizontalInset: Int = 0,
    ): JComponent? {
        // The notification button goes to the end of whichever row holds history.
        val bell = components.notificationButton.takeIf {
            layout.requiredHosts(effectivePanes)[WindowFeature.NOTIFICATIONS] == FeatureHost.HistoryRowEnd
        }
        val rows = plan[slot].orEmpty().map { row ->
            val trailing = bell.takeIf { WindowRegion.HISTORY in row }
            if (row.size == 1) {
                when (row.single()) {
                    WindowRegion.HISTORY -> LayoutBuilders.historyRow(components.historyBar, trailing)
                    WindowRegion.LANGUAGES -> LayoutBuilders.wrapLanguageBar(components.languageBar)
                    WindowRegion.SERVICES -> LayoutBuilders.serviceRow(components.translatorSelector)
                }
            } else {
                LayoutBuilders.sharedRow(row.map { it to components.componentOf(it) }, trailing)
            }
        }
        return LayoutBuilders.slotColumn(rows, horizontalInset)
    }

    private fun ComponentRegistry.componentOf(region: WindowRegion): JComponent = when (region) {
        WindowRegion.HISTORY -> historyBar
        WindowRegion.LANGUAGES -> languageBar
        WindowRegion.SERVICES -> translatorSelector
    }

    private fun extraSplit(top: JComponent, bottom: JComponent): MirroredSplitPane =
        LayoutBuilders.createVerticalSplit(
            top = top,
            bottom = bottom,
            resizeWeight = 0.8,
            bottomMinHeight = UISpacing.MIN_EXTRA_HEIGHT
        )

    /** [content] under [header] in one holder, so the holder's place never depends on the header. */
    private fun section(header: JComponent?, content: JComponent): JComponent = JPanel(BorderLayout()).apply {
        header?.let { add(it, BorderLayout.NORTH) }
        add(content, BorderLayout.CENTER)
    }

    private fun stacked(components: ComponentRegistry, aboveResults: JComponent?): Pair<JComponent, LayoutComponentRefs> {
        val mainSplit = LayoutBuilders.createVerticalSplit(
            top = components.inputPanel,
            bottom = section(aboveResults, components.outputPanel),
            resizeWeight = 0.5
        )
        val extra = extraSplit(mainSplit, components.extraOutputPanel)
        return extra to LayoutComponentRefs.WithSplitPanes(mainSplit, extra)
    }

    private fun sideBySide(
        components: ComponentRegistry,
        aboveResults: JComponent?,
        isRtl: Boolean,
    ): Pair<JComponent, LayoutComponentRefs> {
        // Above results is above the output column only; the input column starts at the top.
        val mainSplit = ResponsivePairSplit(
            leading = components.inputPanel,
            trailing = section(aboveResults, components.outputPanel),
            isRtl = isRtl
        ).split
        val extra = extraSplit(mainSplit, components.extraOutputPanel)
        return extra to LayoutComponentRefs.WithSplitPanes(mainSplit, extra)
    }

    private fun comparison(components: ComponentRegistry, aboveResults: JComponent?): Pair<JComponent, LayoutComponentRefs> {
        // One logical results viewport: the board owns the primary provider and every comparison
        // below it, so results never split into disconnected scroll regions.
        val mainSplit = LayoutBuilders.createVerticalSplit(
            top = components.inputPanel,
            bottom = section(aboveResults, LayoutBuilders.wrapScrollable(components.compareBoard)),
            resizeWeight = 0.28,
            topMinHeight = UISpacing.MIN_PANEL_HEIGHT,
            bottomMinHeight = UISpacing.MIN_PANEL_HEIGHT
        ).apply {
            // Input takes roughly the top 28%; extra height favours the results.
            setLeadingProportion(0.28)
        }
        // Layout-owned boundary between the comparison results and the derived Extra Output: a
        // plain theme-aware separator, not a panel border and not a box around either region.
        val extraBoundary = JSeparator(SwingConstants.HORIZONTAL)
        val extraSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(extraBoundary, BorderLayout.NORTH)
            add(components.extraOutputPanel, BorderLayout.CENTER)
        }
        val extra = extraSplit(mainSplit, extraSection)
        return extra to LayoutComponentRefs.WithSplitPanes(mainSplit, extra, extraBoundary)
    }
}
