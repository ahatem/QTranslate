package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.CloseButtonBehavior
import com.github.ahatem.qtranslate.core.settings.data.HistoryPlacement
import com.github.ahatem.qtranslate.core.settings.data.LanguagePlacement
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.MinimizeButtonBehavior
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.ServicePlacement
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.WorkspaceLayout
import com.github.ahatem.qtranslate.core.settings.data.WorkspacePreset
import com.github.ahatem.qtranslate.core.settings.data.isComparisonEligible
import com.github.ahatem.qtranslate.core.settings.data.withWorkspaceLayout
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
 * The main window: how its panes sit, and what each visual region does.
 *
 * The four cards are starting points, not stored modes: a card writes only the regions it names,
 * so "one row but keep my bottom strip" is one click. Every value below is a value the window can
 * actually build, and a region that is hidden keeps its commands reachable elsewhere — the
 * history commands move into the input pane, the service choice into the View menu.
 *
 * Every write on this page goes through [withWorkspaceLayout] via [writeLayout]; the View menu
 * dispatches the same transform through a scoped save. There is no second place that writes a
 * layout field directly.
 */
class LayoutPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    private val availableTranslatorIds: () -> List<String> = { emptyList() },
    /** Invoked when the user acts on the unavailable-Comparison entry, to send them to fix it. */
    private val onOpenServicesSettings: () -> Unit = {},
) : SettingsPanel() {

    private var comparisonAvailable = true
    private val presetCards = LinkedHashMap<WorkspacePreset, JButton>()

    private lateinit var summaryLabel: JLabel
    private lateinit var panesCombo: JComboBox<PaneLayout>
    private lateinit var historyCombo: JComboBox<HistoryPlacement>
    private lateinit var languagesCombo: JComboBox<LanguagePlacement>
    private lateinit var servicesCombo: JComboBox<ServicePlacement>
    private lateinit var appearanceCombo: JComboBox<ServiceSelectorAppearance>
    private lateinit var statusCheck: JCheckBox

    init {
        buildUI()
    }

    private fun comparisonUnavailableHint() =
        localizationManager.getString("settings_window.layout_comparison_unavailable")

    private fun comparisonConfigureLabel() =
        localizationManager.getString("settings_window.layout_comparison_configure")

    private fun buildUI() {
        addSeparator(localizationManager.getString("settings_window.layout_group"))

        summaryLabel = JLabel().apply { font = SettingsVisuals.paragraphFont(font) }
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .add(summaryLabel)

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(presetCardGrid())

        panesCombo = enumCombo(PaneLayout.entries.toTypedArray(), ::paneText) { pane ->
            if (isUnavailableComparison(pane)) {
                withoutTrigger { panesCombo.selectedItem = store.state.value.workingConfiguration.workspaceLayout.panes }
                onOpenServicesSettings()
            } else {
                writeLayout { it.copy(panes = pane) }
            }
        }
        panesCombo.renderer = DisplayValueRenderer<PaneLayout>(
            text = ::paneText,
            isDisabled = ::isUnavailableComparison,
            tooltip = { if (isUnavailableComparison(it)) comparisonUnavailableHint() else null },
        )
        addRow(localizationManager.getString("settings_window.layout_row_panes"), panesCombo)

        addSeparator(localizationManager.getString("settings_window.toolbars_group"))

        historyCombo = enumCombo(HistoryPlacement.entries.toTypedArray(), ::historyText) {
            writeLayout { layout -> layout.copy(history = it) }
        }
        addRow(localizationManager.getString("settings_window.layout_row_history"), historyCombo)

        languagesCombo = enumCombo(LanguagePlacement.entries.toTypedArray(), ::languageText) {
            writeLayout { layout -> layout.copy(languages = it) }
        }
        addRow(localizationManager.getString("settings_window.layout_row_languages"), languagesCombo)

        addSubSection(localizationManager.getString("settings_window.service_selector_sub")) {
            servicesCombo = enumCombo(ServicePlacement.entries.toTypedArray(), ::serviceText) {
                writeLayout { layout -> layout.copy(services = it) }
            }
            addRow(localizationManager.getString("settings_window.layout_row_selector"), servicesCombo)

            appearanceCombo = enumCombo(ServiceSelectorAppearance.entries.toTypedArray(), ::appearanceText) {
                writeLayout { layout -> layout.copy(appearance = it) }
            }
            addRow(localizationManager.getString("settings_window.layout_row_appearance"), appearanceCombo)
        }

        statusCheck = addCheckbox(
            localizationManager.getString("settings_window.layout_row_status"),
            false
        ) { shown -> writeLayout { it.copy(showStatusBar = shown) } }

        finishLayout()
    }

    private fun <T> enumCombo(values: Array<T>, label: (T) -> String, onChange: (T) -> Unit): JComboBox<T> =
        JComboBox(values).apply {
            renderer = DisplayValueRenderer<T>(text = { it?.let(label).orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) (selectedItem as? T)?.let(onChange)
            }
        }

    private fun presetCardGrid(): JPanel {
        val grid = JPanel(GridLayout(2, 2, 8, 8)).apply { isOpaque = false }
        WorkspacePreset.entries.forEach { preset ->
            val card = JButton(presetTitle(preset)).apply {
                toolTipText = presetHint(preset)
                putClientProperty(
                    FlatClientProperties.BUTTON_TYPE,
                    FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON
                )
                addActionListener { if (!isUpdatingFromState) choosePreset(preset) }
            }
            presetCards[preset] = card
            grid.add(card)
            registerSearchEntry(presetTitle(preset), card, hint = presetHint(preset))
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

    private fun isUnavailableComparison(pane: PaneLayout?): Boolean =
        pane == PaneLayout.COMPARISON && !comparisonAvailable

    private fun choosePreset(preset: WorkspacePreset) {
        if (preset == WorkspacePreset.COMPARISON && !comparisonAvailable) {
            onOpenServicesSettings()
            return
        }
        writeLayout { current -> preset.recipe.applyTo(current) }
    }

    /** The single write path for the whole page: one whole [WorkspaceLayout] per change. */
    private fun writeLayout(transform: (WorkspaceLayout) -> WorkspaceLayout) {
        applyDraft(store) { configuration ->
            configuration.withWorkspaceLayout(transform(configuration.workspaceLayout))
        }
    }

    private fun summaryOf(layout: WorkspaceLayout): String = localizationManager.getString(
        "settings_window.layout_summary_format",
        paneText(layout.panes),
        historyText(layout.history),
        languageText(layout.languages),
        if (layout.panes == PaneLayout.COMPARISON) {
            localizationManager.getString("settings_window.layout_selector_absent")
        } else {
            serviceText(layout.services)
        },
    )

    private fun paneText(pane: PaneLayout?) = when (pane) {
        PaneLayout.STACKED -> localizationManager.getString("settings_window.layout_panes_stacked")
        PaneLayout.SIDE_BY_SIDE -> localizationManager.getString("settings_window.layout_panes_side_by_side")
        PaneLayout.COMPARISON -> localizationManager.getString("settings_window.layout_panes_comparison")
        null -> ""
    }

    private fun historyText(placement: HistoryPlacement?) = when (placement) {
        HistoryPlacement.TOP_ROW -> localizationManager.getString("settings_window.layout_history_top_row")
        HistoryPlacement.BOTTOM_ROW -> localizationManager.getString("settings_window.layout_history_bottom_row")
        HistoryPlacement.COMBINED -> localizationManager.getString("settings_window.layout_history_combined")
        HistoryPlacement.RELOCATED -> localizationManager.getString("settings_window.layout_history_relocated")
        null -> ""
    }

    private fun languageText(placement: LanguagePlacement?) = when (placement) {
        LanguagePlacement.ROW -> localizationManager.getString("settings_window.layout_languages_row")
        LanguagePlacement.RELOCATED -> localizationManager.getString("settings_window.layout_languages_relocated")
        null -> ""
    }

    private fun serviceText(placement: ServicePlacement?) = when (placement) {
        ServicePlacement.PICKER -> localizationManager.getString("settings_window.layout_selector_picker")
        ServicePlacement.BOTTOM_STRIP -> localizationManager.getString("settings_window.layout_selector_bottom_strip")
        ServicePlacement.RELOCATED -> localizationManager.getString("settings_window.layout_services_relocated")
        null -> ""
    }

    private fun appearanceText(appearance: ServiceSelectorAppearance?) = when (appearance) {
        ServiceSelectorAppearance.ICONS_ONLY -> localizationManager.getString("settings_window.layout_appearance_icons")
        ServiceSelectorAppearance.ICONS_AND_TEXT -> localizationManager.getString("settings_window.layout_appearance_both")
        ServiceSelectorAppearance.TEXT_ONLY -> localizationManager.getString("settings_window.layout_appearance_text")
        null -> ""
    }

    private fun presetTitle(preset: WorkspacePreset) = when (preset) {
        WorkspacePreset.CLASSIC -> localizationManager.getString("settings_window.layout_card_classic")
        WorkspacePreset.COMPACT -> localizationManager.getString("settings_window.layout_card_compact")
        WorkspacePreset.SIDE_BY_SIDE -> localizationManager.getString("settings_window.layout_card_side_by_side")
        WorkspacePreset.COMPARISON ->
            if (comparisonAvailable) localizationManager.getString("settings_window.layout_card_comparison")
            else comparisonConfigureLabel()
    }

    private fun presetHint(preset: WorkspacePreset) = when (preset) {
        WorkspacePreset.CLASSIC -> localizationManager.getString("settings_window.layout_card_classic_hint")
        WorkspacePreset.COMPACT -> localizationManager.getString("settings_window.layout_card_compact_hint")
        WorkspacePreset.SIDE_BY_SIDE -> localizationManager.getString("settings_window.layout_card_side_by_side_hint")
        WorkspacePreset.COMPARISON ->
            if (comparisonAvailable) localizationManager.getString("settings_window.layout_card_comparison_hint")
            else comparisonUnavailableHint()
    }

    override fun render(state: SettingsState) {
        val layout = state.workingConfiguration.workspaceLayout
        comparisonAvailable = state.workingConfiguration.isComparisonEligible(availableTranslatorIds())

        withoutTrigger {
            summaryLabel.text = summaryOf(layout)
            panesCombo.selectedItem = layout.panes
            historyCombo.selectedItem = layout.history
            languagesCombo.selectedItem = layout.languages
            servicesCombo.selectedItem = layout.services
            appearanceCombo.selectedItem = layout.appearance
            statusCheck.isSelected = layout.showStatusBar

            // Appearance only changes the bottom strip; under a picker or in comparison it is a
            // dead value, so disable the row with a reason rather than let it change nothing.
            val stripShown = layout.panes != PaneLayout.COMPARISON &&
                layout.services == ServicePlacement.BOTTOM_STRIP
            appearanceCombo.isEnabled = stripShown
            panesCombo.selectedItem?.let {
                if (it == PaneLayout.COMPARISON) panesCombo.toolTipText = comparisonUnavailableHint()
            }

            presetCards.forEach { (preset, card) ->
                card.text = presetTitle(preset)
                card.toolTipText = presetHint(preset)
            }
        }
    }

    private companion object {
        const val CARD_GRID_PADDING = 8
    }
}
