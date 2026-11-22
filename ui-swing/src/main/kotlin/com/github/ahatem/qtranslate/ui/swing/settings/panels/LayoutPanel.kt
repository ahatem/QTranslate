package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.LayoutConfig
import com.github.ahatem.qtranslate.core.settings.data.LayoutPreset
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelector
import com.github.ahatem.qtranslate.core.settings.data.ToolbarStyle
import com.github.ahatem.qtranslate.core.settings.data.isComparisonEligible
import com.github.ahatem.qtranslate.core.settings.data.withLayoutConfig
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DisplayValueRenderer
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The main window: how its panes sit, how much chrome sits above them, which widget chooses the
 * service, and whether the footer shows.
 *
 * The four arrangement values are [LayoutConfig]'s own, and this page writes them the only way
 * they are meant to be written: one whole [LayoutConfig] at a time, through
 * [withLayoutConfig]. There is no layout id to keep in sync, no pair of switches that can
 * describe the same window twice, and nothing that can be switched off into an unreachable
 * state — every value below is a value the window can actually build.
 *
 * Read top to bottom, the page is:
 *
 * 1. **What you have** — one line naming the current arrangement in words, so the four values
 *    read back as one sentence before any control is touched.
 * 2. **Starting points** — four cards that write a whole arrangement each. They are actions, not
 *    modes: nothing is highlighted, because the base values are the truth and the cards are
 *    macros over them.
 * 3. **The four values themselves** — panes, toolbar, service selector, status bar. Always
 *    visible, never behind a disclosure: they are the page's subject, not its expert tuning.
 *
 * The title-bar buttons are not among them: what the close and minimise buttons do is behaviour
 * rather than the shape of the window, so they live on General with the other settings that
 * depend on what the machine can offer. See [GeneralPanel].
 *
 * Comparison stays reachable but never silently unusable: with fewer than two usable translators
 * its card and its pane entry say so and take the user to the settings that fix it, exactly as
 * the old preset picker did.
 *
 * Split from the former Window & Layout page, which also held the floating popups. The two were
 * only ever neighbours: chrome is arranged once and left alone, while popup behaviour is tuned
 * repeatedly by anyone the popups annoy. See [PopupsPanel].
 */
class LayoutPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    private val availableTranslatorIds: () -> List<String> = { emptyList() },
    /** Invoked when the user acts on the unavailable-Comparison card, to send them to fix it. */
    private val onOpenServicesSettings: () -> Unit = {},
) : SettingsPanel() {

    /** Whether Comparison may be chosen right now; refreshed on every render. */
    private var comparisonAvailable = true

    /** The four starting points, in the order they are shown. */
    private val presetCards = LinkedHashMap<LayoutPreset, JButton>()

    private lateinit var summaryLabel: JLabel
    private lateinit var panesCombo: JComboBox<PaneLayout>
    private lateinit var toolbarCombo: JComboBox<ToolbarStyle>
    private lateinit var selectorCombo: JComboBox<ServiceSelector>
    private lateinit var statusCheck: JCheckBox

    private fun comparisonUnavailableHint(): String =
        localizationManager.getString("settings_window.layout_comparison_unavailable")

    /** The unavailable Comparison card's own text — an action, not just a disabled label. */
    private fun comparisonConfigureLabel(): String =
        localizationManager.getString("settings_window.layout_comparison_configure")

    /** Why the selector row cannot be used here. */
    private fun selectorAbsentText(): String =
        localizationManager.getString("settings_window.layout_selector_absent")

    init {
        buildUI()
    }

    private fun buildUI() {
        // ── The arrangement, said and then offered ───────────────────────────
        addSeparator(localizationManager.getString("settings_window.layout_group"))

        // The summary is built from state rather than from the controls below it, so the page
        // opens by saying what the window currently is instead of asking the user to read it out
        // of four separate controls.
        summaryLabel = JLabel(summaryOf(store.state.value.workingConfiguration.layoutConfig)).apply {
            font = SettingsVisuals.paragraphFont(font)
            foreground = SettingsVisuals.primaryText()
        }
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(
                UIScale.scale(SettingsPanel.PARAGRAPH_GAP_TOP),
                0,
                UIScale.scale(SettingsPanel.PARAGRAPH_GAP_BOTTOM),
                0,
            )
            .add(summaryLabel)

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .add(presetCardGrid())

        // ── The four values ──────────────────────────────────────────────────
        panesCombo = JComboBox(PaneLayout.entries.toTypedArray()).apply {
            renderer = DisplayValueRenderer<PaneLayout>(
                text = ::paneText,
                // Eligibility splits in two here: the renderer dims the entry that cannot be used
                // right now, and the action below decides what the click does instead of writing
                // an arrangement the window would have to ignore.
                isDisabled = ::isUnavailableComparison,
                tooltip = { if (isUnavailableComparison(it)) comparisonUnavailableHint() else null },
            )
            addActionListener {
                if (isUpdatingFromState) return@addActionListener
                val pane = selectedItem as? PaneLayout ?: return@addActionListener
                if (isUnavailableComparison(pane)) {
                    // A comparison board needs two translators. Writing the arrangement anyway
                    // would be recorded and ignored, so the page sends the user to fix the cause
                    // and leaves the stored arrangement alone.
                    withoutTrigger {
                        selectedItem = store.state.value.workingConfiguration.layoutConfig.paneLayout
                    }
                    onOpenServicesSettings()
                    return@addActionListener
                }
                writeLayout { it.copy(paneLayout = pane) }
            }
        }
        addRow(localizationManager.getString("settings_window.layout_row_panes"), panesCombo)

        // ── Toolbars ─────────────────────────────────────────────────────────
        addSeparator(localizationManager.getString("settings_window.toolbars_group"))

        toolbarCombo = JComboBox(ToolbarStyle.entries.toTypedArray()).apply {
            renderer = DisplayValueRenderer<ToolbarStyle>(text = ::toolbarStyleText)
            addActionListener {
                if (isUpdatingFromState) return@addActionListener
                (selectedItem as? ToolbarStyle)?.let { style -> writeLayout { it.copy(toolbarStyle = style) } }
            }
        }
        addRow(localizationManager.getString("settings_window.layout_row_toolbar"), toolbarCombo)

        // Its own sub-section rather than another peer row: which widget picks the service is
        // subordinate to the panes above it — a comparison board has no selector at all — and the
        // rail says so without hiding the control from anyone.
        addSubSection(localizationManager.getString("settings_window.service_selector_sub")) {
            selectorCombo = JComboBox(ServiceSelector.entries.toTypedArray()).apply {
                renderer = DisplayValueRenderer<ServiceSelector>(text = ::selectorText)
                addActionListener {
                    if (isUpdatingFromState) return@addActionListener
                    (selectedItem as? ServiceSelector)?.let { selector ->
                        writeLayout { it.copy(serviceSelector = selector) }
                    }
                }
            }
            addRow(localizationManager.getString("settings_window.layout_row_selector"), selectorCombo)
        }

        statusCheck = addCheckbox(
            localizationManager.getString("settings_window.layout_row_status"),
            false
        ) { shown ->
            writeLayout { it.copy(showStatusBar = shown) }
        }

        finishLayout()
    }

    /**
     * The four arrangements as buttons in a 2×2 grid.
     *
     * A card writes all four values at once — that is the whole of it. Nothing records which card
     * was last pressed, so a user who then changes one row below is not shown a card claiming to
     * be their arrangement; the summary line above says what the window now is.
     */
    private fun presetCardGrid(): JPanel {
        val grid = JPanel(GridLayout(2, 2, 8, 8)).apply { isOpaque = false }
        LayoutPreset.entries.forEach { preset ->
            val card = JButton(presetCardTitle(preset)).apply {
                toolTipText = presetCardHint(preset)
                // The look and feel's toolbar button is the closest thing to a card it has: FlatLaf
                // paints and hovers it, and a hand-drawn card would be a second visual language on
                // a page made of the first one.
                putClientProperty(
                    FlatClientProperties.BUTTON_TYPE,
                    FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON
                )
                addActionListener { if (!isUpdatingFromState) choosePreset(preset) }
            }
            presetCards[preset] = card
            grid.add(card)
            registerSearchEntry(presetCardTitle(preset), card, hint = presetCardHint(preset))
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                themeAwareBorder(),
                BorderFactory.createEmptyBorder(CARD_GRID_PADDING, CARD_GRID_PADDING, CARD_GRID_PADDING, CARD_GRID_PADDING),
            )
            add(grid, BorderLayout.CENTER)
        }
    }

    /**
     * Writes one of the four starting points, or takes the user somewhere it can be obtained.
     *
     * Comparison is the one card whose config the window cannot build on every installation, and
     * a card that silently does nothing is worse than one that explains itself: it becomes the
     * "set up translators" action, and clicking it opens the page that fixes the cause.
     */
    private fun choosePreset(preset: LayoutPreset) {
        if (preset == LayoutPreset.COMPARISON && !comparisonAvailable) {
            onOpenServicesSettings()
            return
        }
        writeLayout { preset.config }
    }

    /** Every write on this page goes through here: one whole [LayoutConfig], never one field. */
    private fun writeLayout(transform: (LayoutConfig) -> LayoutConfig) {
        applyDraft(store) { configuration ->
            configuration.withLayoutConfig(transform(configuration.layoutConfig))
        }
    }

    private fun isUnavailableComparison(pane: PaneLayout?): Boolean =
        pane == PaneLayout.COMPARISON && !comparisonAvailable

    /** What the window currently is, as one line: the four values in the order of the rows below. */
    private fun summaryOf(layout: LayoutConfig): String = localizationManager.getString(
        "settings_window.layout_summary_format",
        paneText(layout.paneLayout),
        toolbarStyleText(layout.toolbarStyle),
        if (layout.isServiceSelectorVisible) {
            selectorText(layout.serviceSelector)
        } else {
            selectorAbsentText()
        },
        if (layout.showStatusBar) {
            localizationManager.getString("settings_window.layout_status_shown")
        } else {
            localizationManager.getString("settings_window.layout_status_hidden")
        },
    )

    private fun paneText(pane: PaneLayout?): String = when (pane) {
        PaneLayout.STACKED -> localizationManager.getString("settings_window.layout_panes_stacked")
        PaneLayout.SIDE_BY_SIDE -> localizationManager.getString("settings_window.layout_panes_side_by_side")
        PaneLayout.COMPARISON -> localizationManager.getString("settings_window.layout_panes_comparison")
        null -> ""
    }

    private fun toolbarStyleText(style: ToolbarStyle?): String = when (style) {
        ToolbarStyle.FULL -> localizationManager.getString("settings_window.layout_toolbar_full")
        ToolbarStyle.COMPACT -> localizationManager.getString("settings_window.layout_toolbar_compact")
        ToolbarStyle.MINIMAL -> localizationManager.getString("settings_window.layout_toolbar_minimal")
        null -> ""
    }

    private fun selectorText(selector: ServiceSelector?): String = when (selector) {
        ServiceSelector.PICKER -> localizationManager.getString("settings_window.layout_selector_picker")
        ServiceSelector.BOTTOM_STRIP -> localizationManager.getString("settings_window.layout_selector_bottom_strip")
        null -> ""
    }

    /**
     * A card's title, which for the unavailable Comparison is the way to make it available.
     *
     * "Comparison" alone, greyed out, teaches nothing; the label says what to do about it.
     */
    private fun presetCardTitle(preset: LayoutPreset): String = when (preset) {
        LayoutPreset.CLASSIC -> localizationManager.getString("settings_window.layout_card_classic")
        LayoutPreset.COMPACT -> localizationManager.getString("settings_window.layout_card_compact")
        LayoutPreset.SIDE_BY_SIDE -> localizationManager.getString("settings_window.layout_card_side_by_side")
        LayoutPreset.COMPARISON ->
            if (comparisonAvailable) localizationManager.getString("settings_window.layout_card_comparison")
            else comparisonConfigureLabel()
    }

    private fun presetCardHint(preset: LayoutPreset): String = when (preset) {
        LayoutPreset.CLASSIC -> localizationManager.getString("settings_window.layout_card_classic_hint")
        LayoutPreset.COMPACT -> localizationManager.getString("settings_window.layout_card_compact_hint")
        LayoutPreset.SIDE_BY_SIDE -> localizationManager.getString("settings_window.layout_card_side_by_side_hint")
        LayoutPreset.COMPARISON ->
            if (comparisonAvailable) localizationManager.getString("settings_window.layout_card_comparison_hint")
            else comparisonUnavailableHint()
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        val layout = c.layoutConfig
        comparisonAvailable = c.isComparisonEligible(availableTranslatorIds())

        withoutTrigger {
            summaryLabel.text = summaryOf(layout)
            panesCombo.selectedItem = layout.paneLayout
            toolbarCombo.selectedItem = layout.toolbarStyle
            selectorCombo.selectedItem = layout.serviceSelector
            statusCheck.isSelected = layout.showStatusBar

            // A comparison board has no selector of its own, so the row stays where it is and
            // says why rather than disappearing: a control that comes and goes with another
            // setting makes the page look like it changed shape behind the user's back.
            selectorCombo.isEnabled = layout.isServiceSelectorVisible
            selectorCombo.toolTipText = if (layout.isServiceSelectorVisible) null else selectorAbsentText()

            presetCards.forEach { (preset, card) ->
                card.text = presetCardTitle(preset)
                card.toolTipText = presetCardHint(preset)
            }
        }
    }

    private companion object {
        /** Padding between the card grid's border and the cards themselves. */
        const val CARD_GRID_PADDING = 8
    }
}
