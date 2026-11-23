package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.FeatureHost
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.SecondResultPlacement
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
        /**
         * The split that holds the second result: under everything (the default), beside or under
         * the result, or the one that gives it a column. Which child holds the panel is found when
         * the panel is shown or hidden, so the same code serves every placement.
         */
        val extraSplit: JSplitPane,
        /**
         * Optional layout-owned boundary above the extra panel (Comparison
         * only). Toggled together with the panel so a hidden Extra Output
         * leaves no stray separation behind.
         */
        val extraBoundary: JComponent? = null,
        /**
         * Told after every show or hide, for a structure whose other panes change size with it:
         * the three columns give each pane a third while the second result shows and a half
         * while it does not.
         */
        val onExtraVisibility: (Boolean) -> Unit = {},
    ) : LayoutComponentRefs {
        override fun updateExtraOutputVisibility(visible: Boolean, extraPanel: JComponent) {
            SwingUtilities.invokeLater {
                val wasContinuous = extraSplit.isContinuousLayout
                extraSplit.isContinuousLayout = false
                extraPanel.isVisible = visible
                extraBoundary?.isVisible = visible
                // Whatever holds the panel in the split goes with it: a hidden panel inside a visible
                // holder still claims the holder's minimum size, and leaves an empty band.
                val holder = listOfNotNull(extraSplit.leftComponent, extraSplit.rightComponent)
                    .firstOrNull { it === extraPanel || SwingUtilities.isDescendingFrom(extraPanel, it) }
                holder?.isVisible = visible
                if (visible) {
                    extraSplit.dividerSize = UISpacing.DIVIDER_SIZE
                    val beside = extraSplit as? MirroredSplitPane
                    if (beside != null && extraSplit.orientation == JSplitPane.HORIZONTAL_SPLIT) {
                        // Two panes side by side share the width evenly, whatever they last measured.
                        // Now if the pane has a size, otherwise when it first gets one.
                        if (!beside.applyLeadingProportion(0.5)) beside.setLeadingProportion(0.5)
                    } else {
                        extraSplit.resetToPreferredSizes()
                    }
                } else {
                    extraSplit.dividerSize = 0
                    // The hidden side collapses: the first slot to the start, the second to the end.
                    extraSplit.setDividerLocation(if (holder != null && holder === extraSplit.leftComponent) 0.0 else 1.0)
                }
                onExtraVisibility(visible)
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

/**
 * One chrome row as built: the regions it holds (several when they share it) and the container
 * that holds them. The window attaches the row's context menu to [container].
 */
data class RegionRow(val regions: List<WindowRegion>, val container: JComponent)

data class ArrangedLayout(
    val rootComponent: JComponent,
    val componentRefs: LayoutComponentRefs,
    /** The chrome rows that were built, top to bottom within each slot. */
    val rows: List<RegionRow> = emptyList(),
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

    /** The language row. The slot it sits in supplies the space above and below it. */
    fun wrapLanguageBar(languageBar: JComponent): JComponent {
        return JPanel(BorderLayout()).apply {
            add(languageBar, BorderLayout.CENTER)
        }
    }

    /** The service selector's row. The slot it sits in supplies the space around it. */
    fun serviceRow(translatorSelector: JComponent): JComponent {
        return JPanel(BorderLayout()).apply {
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

    /**
     * The rows of one slot, top to bottom, each filling the width. Null when the slot is empty.
     *
     * The slot is the only place that spaces its rows: [rowGap] between two of them, [topInset] and
     * [bottomInset] between the first and last and whatever the slot sits next to, and
     * [horizontalInset] at the sides. A row never pads itself, so every row sits the same distance
     * from its neighbours whatever it holds.
     */
    fun slotColumn(
        rows: List<JComponent>,
        horizontalInset: Int = 0,
        rowGap: Int = 0,
        topInset: Int = 0,
        bottomInset: Int = 0,
    ): JComponent? {
        if (rows.isEmpty()) return null
        return JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(topInset, horizontalInset, bottomInset, horizontalInset)
            val gbc = GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                anchor = GridBagConstraints.LINE_START
                fill = GridBagConstraints.HORIZONTAL
            }
            rows.forEachIndexed { index, row ->
                gbc.gridy = index
                gbc.insets = Insets(if (index == 0) 0 else rowGap, 0, 0, 0)
                add(row, gbc)
            }
        }
    }

    /** The footer: the bottom slot's rows, if any, above the status bar. */
    fun createBottomBar(bottomSlot: JComponent?, statusBar: JComponent, gap: Int = UIScale.scale(4)): JComponent {
        return JPanel(BorderLayout(0, gap)).apply {
            bottomSlot?.let { add(it, BorderLayout.NORTH) }
            add(statusBar, BorderLayout.SOUTH)
        }
    }

    fun createVerticalSplit(
        top: JComponent,
        bottom: JComponent,
        resizeWeight: Double = 0.5,
        topMinHeight: Int? = UISpacing.MIN_PANEL_HEIGHT,
        bottomMinHeight: Int? = UISpacing.MIN_PANEL_HEIGHT
    ): MirroredSplitPane {
        // A null minimum leaves the component's own, which for a nested split is the sum of what it
        // holds: the outer split must not let it be squeezed below that.
        topMinHeight?.let { top.minimumSize = Dimension(0, it) }
        bottomMinHeight?.let { bottom.minimumSize = Dimension(0, it) }
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
 * | ABOVE_RESULTS | between input and output | a full-width row directly above both columns, below any TOP rows | above the board |
 * | BOTTOM | below the panes, above the status bar | same | same |
 *
 * The second result (the panel under the extra output type) follows [WindowLayout.effectiveSecondResult]:
 *
 * | | BELOW | WITH_RESULT | OWN_COLUMN |
 * |---|---|---|---|
 * | Stacked | full width under the panes | beside the result, under the input | builds as BELOW |
 * | Side by side | full width under both columns | under the result, in its column | a third column |
 * | Comparison | under the board | builds as BELOW | builds as BELOW |
 *
 * ABOVE_RESULTS rows sit above the whole results area: the result and, when they share it, the
 * second result with it.
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
        listOf(components.historyBar, components.languageBar, components.translatorSelector)
            .forEach { (it as? DensityAware)?.density = layout.density }

        val spacing = UISpacing.chrome(layout.density)
        // A vertical split puts its divider directly above the results section, so the row that
        // heads that section is spaced from the input by the divider and from the output by the
        // same amount again. Beside each other there is no divider above it.
        val dividerAbove = effectivePanes != PaneLayout.SIDE_BY_SIDE
        val rows = mutableListOf<RegionRow>()
        val top = slot(plan, layout, effectivePanes, RegionSlot.TOP, components, rows, spacing)
        val aboveResults = slot(
            plan, layout, effectivePanes, RegionSlot.ABOVE_RESULTS, components, rows, spacing,
            bottomInset = if (dividerAbove) UISpacing.DIVIDER_SIZE else spacing.V_GAP,
        )
        // The footer sits outside the padded content, so its rows bring the window's side padding.
        // The content's bottom padding is the space above them; the status bar follows at half a gap.
        val bottom = slot(
            plan, layout, effectivePanes, RegionSlot.BOTTOM, components, rows, spacing,
            horizontalInset = spacing.PADDING, bottomInset = spacing.TIGHT_GAP,
        )

        val secondResult = layout.effectiveSecondResult(effectivePanes)
        val (panes, refs) = when (effectivePanes) {
            PaneLayout.STACKED -> stacked(components, aboveResults, secondResult, isRtl)
            PaneLayout.SIDE_BY_SIDE -> sideBySide(components, aboveResults, secondResult, isRtl)
            PaneLayout.COMPARISON -> comparison(components, aboveResults)
        }

        val contentPanel = JPanel(BorderLayout(0, spacing.V_GAP)).apply {
            border = BorderFactory.createEmptyBorder(
                spacing.PADDING,
                spacing.PADDING,
                spacing.V_GAP,
                spacing.PADDING
            )
            top?.let { add(it, BorderLayout.NORTH) }
            add(panes, BorderLayout.CENTER)
        }
        val root = JPanel(BorderLayout()).apply {
            add(contentPanel, BorderLayout.CENTER)
            add(LayoutBuilders.createBottomBar(bottom, components.statusBar, spacing.TIGHT_GAP), BorderLayout.SOUTH)
        }
        refs.syncExtraOutputState(components.extraOutputPanel)
        return ArrangedLayout(root, refs, rows)
    }

    private fun slot(
        plan: Map<RegionSlot, List<List<WindowRegion>>>,
        layout: WindowLayout,
        effectivePanes: PaneLayout,
        slot: RegionSlot,
        components: ComponentRegistry,
        rowsOut: MutableList<RegionRow>,
        spacing: ChromeSpacing,
        horizontalInset: Int = 0,
        bottomInset: Int = 0,
    ): JComponent? {
        // The notification button goes to the end of whichever row holds history.
        val bell = components.notificationButton.takeIf {
            layout.requiredHosts(effectivePanes)[WindowFeature.NOTIFICATIONS] == FeatureHost.HistoryRowEnd
        }
        val rows = plan[slot].orEmpty().map { row ->
            val trailing = bell.takeIf { WindowRegion.HISTORY in row }
            val built = if (row.size == 1) {
                when (row.single()) {
                    WindowRegion.HISTORY -> LayoutBuilders.historyRow(components.historyBar, trailing)
                    WindowRegion.LANGUAGES -> LayoutBuilders.wrapLanguageBar(components.languageBar)
                    WindowRegion.SERVICES -> LayoutBuilders.serviceRow(components.translatorSelector)
                }
            } else {
                LayoutBuilders.sharedRow(row.map { it to components.componentOf(it) }, trailing)
            }
            rowsOut += RegionRow(row, built)
            built
        }
        return LayoutBuilders.slotColumn(rows, horizontalInset, rowGap = spacing.V_GAP, bottomInset = bottomInset)
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
            // The panes above keep what they need, whatever that adds up to; the second result
            // is the one that gives way in a short window.
            topMinHeight = null,
            bottomMinHeight = UISpacing.MIN_EXTRA_HEIGHT
        )

    /** [content] under [header] in one holder, so the holder's place never depends on the header. */
    private fun section(header: JComponent?, content: JComponent): JComponent = JPanel(BorderLayout()).apply {
        header?.let { add(it, BorderLayout.NORTH) }
        add(content, BorderLayout.CENTER)
    }

    /** Two panes side by side in reading order, [leading] first, mirrored in a right-to-left window. */
    private fun columns(leading: JComponent, trailing: JComponent, isRtl: Boolean): MirroredSplitPane {
        leading.minimumSize = Dimension(UISpacing.MIN_PANEL_WIDTH, 0)
        trailing.minimumSize = Dimension(UISpacing.MIN_PANEL_WIDTH, 0)
        return MirroredSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, leading, trailing).apply {
            leadingResizeWeight = 0.5
            dividerSize = UISpacing.DIVIDER_SIZE
            clearBorder()
            isMirrored = isRtl
            setLeadingProportion(0.5)
        }
    }

    private fun stacked(
        components: ComponentRegistry,
        aboveResults: JComponent?,
        secondResult: SecondResultPlacement,
        isRtl: Boolean,
    ): Pair<JComponent, LayoutComponentRefs> {
        if (secondResult == SecondResultPlacement.WITH_RESULT) {
            // The output and the second result share the area under the input, side by side.
            val beside = columns(components.outputPanel, components.extraOutputPanel, isRtl)
            val mainSplit = LayoutBuilders.createVerticalSplit(
                top = components.inputPanel,
                bottom = section(aboveResults, beside),
                resizeWeight = 0.5,
                topMinHeight = UISpacing.MIN_INPUT_HEIGHT,
            )
            return mainSplit to LayoutComponentRefs.WithSplitPanes(mainSplit, beside)
        }
        val mainSplit = LayoutBuilders.createVerticalSplit(
            top = components.inputPanel,
            bottom = section(aboveResults, components.outputPanel),
            resizeWeight = 0.5,
            topMinHeight = UISpacing.MIN_INPUT_HEIGHT,
        )
        val extra = extraSplit(mainSplit, components.extraOutputPanel)
        return extra to LayoutComponentRefs.WithSplitPanes(mainSplit, extra)
    }

    private fun sideBySide(
        components: ComponentRegistry,
        aboveResults: JComponent?,
        secondResult: SecondResultPlacement,
        isRtl: Boolean,
    ): Pair<JComponent, LayoutComponentRefs> {
        // Above results is a full-width row directly above both columns, below any TOP rows.
        return when (secondResult) {
            SecondResultPlacement.WITH_RESULT -> {
                // The result column is split: the output on top, the second result under it. The
                // input keeps the full height.
                val column = LayoutBuilders.createVerticalSplit(
                    top = components.outputPanel,
                    bottom = components.extraOutputPanel,
                    resizeWeight = 0.8,
                    bottomMinHeight = UISpacing.MIN_EXTRA_HEIGHT
                )
                val pair = ResponsivePairSplit(
                    leading = components.inputPanel,
                    trailing = column,
                    isRtl = isRtl,
                    trailingMinHeight = UISpacing.MIN_PANEL_HEIGHT + UISpacing.MIN_EXTRA_HEIGHT + UISpacing.DIVIDER_SIZE,
                )
                section(aboveResults, pair.split) to LayoutComponentRefs.WithSplitPanes(pair.split, column)
            }
            SecondResultPlacement.OWN_COLUMN -> {
                // Three columns: the input, then the output and the second result, which share what
                // the input leaves. Narrow, the input goes above the other two.
                val results = columns(components.outputPanel, components.extraOutputPanel, isRtl)
                val pair = ResponsivePairSplit(
                    leading = components.inputPanel,
                    trailing = results,
                    isRtl = isRtl,
                    trailingMinWidth = 2 * UISpacing.MIN_PANEL_WIDTH + UISpacing.DIVIDER_SIZE,
                    sideBySideShare = THIRD,
                    // Three columns need more room than two: until they have it, the input sits
                    // above the two results instead of squeezing all three.
                    breakpoint = UISpacing.THREE_COLUMN_BREAKPOINT,
                )
                section(aboveResults, pair.split) to LayoutComponentRefs.WithSplitPanes(
                    pair.split, results,
                    onExtraVisibility = { shown -> pair.setSideBySideShare(if (shown) THIRD else 0.5) },
                )
            }
            SecondResultPlacement.BELOW -> {
                val mainSplit = ResponsivePairSplit(
                    leading = components.inputPanel,
                    trailing = components.outputPanel,
                    isRtl = isRtl
                ).split
                val extra = extraSplit(section(aboveResults, mainSplit), components.extraOutputPanel)
                extra to LayoutComponentRefs.WithSplitPanes(mainSplit, extra)
            }
        }
    }

    /** The input's share of the width while it has two panes beside it. */
    private const val THIRD = 1.0 / 3.0

    private fun comparison(components: ComponentRegistry, aboveResults: JComponent?): Pair<JComponent, LayoutComponentRefs> {
        // One logical results viewport: the board owns the primary provider and every comparison
        // below it, so results never split into disconnected scroll regions.
        val mainSplit = LayoutBuilders.createVerticalSplit(
            top = components.inputPanel,
            bottom = section(
                aboveResults,
                LayoutBuilders.wrapScrollable(components.compareBoard).apply {
                    minimumSize = Dimension(0, UISpacing.MIN_BOARD_HEIGHT)
                },
            ),
            resizeWeight = 0.28,
            topMinHeight = UISpacing.MIN_INPUT_HEIGHT,
            // The section's own minimum: its controls and the board's, not a flat figure that a
            // short window could not honour next to the second result.
            bottomMinHeight = null
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
