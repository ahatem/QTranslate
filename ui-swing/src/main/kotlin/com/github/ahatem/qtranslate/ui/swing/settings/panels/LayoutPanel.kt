package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.LayoutAvailability
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceWidget
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowPreset
import com.github.ahatem.qtranslate.core.settings.data.WindowRecipe
import com.github.ahatem.qtranslate.core.settings.data.effectivePaneLayout
import com.github.ahatem.qtranslate.core.settings.data.isComparisonEligible
import com.github.ahatem.qtranslate.core.settings.data.withWindowLayout
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.layout.LayoutTexts
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DisplayValueRenderer
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The main window: how its panes sit, and where each region goes.
 *
 * The four cards are starting points, not stored modes: a card writes only the values it names,
 * so "one row but keep my service strip" is one click. Every value below is a value the window can
 * actually build, and a hidden region keeps its commands reachable in the input pane.
 *
 * Every write on this page goes through [withWindowLayout] via [writeLayout]; the View menu
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

    /** The starting-point cards this page offers. */
    private enum class LayoutCard(val recipe: WindowRecipe) {
        CLASSIC(WindowPreset.MODERN_CLASSIC.recipe),
        COMPACT(WindowPreset.COMPACT.recipe),
        SIDE_BY_SIDE(
            WindowRecipe(
                panes = PaneLayout.SIDE_BY_SIDE,
                history = RegionSlot.TOP,
                languages = RegionSlot.TOP,
                services = RegionSlot.BOTTOM,
                serviceWidget = ServiceWidget.PICKER,
                shareRows = false,
                showStatusBar = true,
            )
        ),
        COMPARISON(
            WindowRecipe(
                panes = PaneLayout.COMPARISON,
                history = RegionSlot.TOP,
                languages = RegionSlot.ABOVE_RESULTS,
                shareRows = false,
                showStatusBar = true,
            )
        ),
    }

    private val texts = LayoutTexts(localizationManager)
    private var comparisonAvailable = true
    private val presetCards = LinkedHashMap<LayoutCard, JButton>()

    private lateinit var summaryLabel: JLabel
    private lateinit var panesCombo: JComboBox<PaneLayout>
    private lateinit var historyCombo: JComboBox<RegionSlot>
    private lateinit var languagesCombo: JComboBox<RegionSlot>
    private lateinit var servicesCombo: JComboBox<RegionSlot>
    private lateinit var widgetCombo: JComboBox<ServiceWidget>
    private lateinit var appearanceCombo: JComboBox<ServiceSelectorAppearance>
    private lateinit var shareRowsCheck: JCheckBox
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

        panesCombo = enumCombo(PaneLayout.entries.toTypedArray(), texts::pane) { pane ->
            if (isUnavailableComparison(pane)) {
                withoutTrigger { panesCombo.selectedItem = store.state.value.workingConfiguration.windowLayout.panes }
                onOpenServicesSettings()
            } else {
                writeLayout { it.copy(panes = pane) }
            }
        }
        panesCombo.renderer = DisplayValueRenderer<PaneLayout>(
            text = texts::pane,
            isDisabled = ::isUnavailableComparison,
            tooltip = { if (isUnavailableComparison(it)) comparisonUnavailableHint() else null },
        )
        addRow(localizationManager.getString("settings_window.layout_row_panes"), panesCombo)

        addSeparator(localizationManager.getString("settings_window.toolbars_group"))

        historyCombo = enumCombo(RegionSlot.entries.toTypedArray(), texts::slot) {
            writeLayout { layout -> layout.copy(history = it) }
        }
        addRow(localizationManager.getString("settings_window.layout_row_history"), historyCombo)

        languagesCombo = enumCombo(RegionSlot.entries.toTypedArray(), texts::slot) {
            writeLayout { layout -> layout.copy(languages = it) }
        }
        addRow(localizationManager.getString("settings_window.layout_row_languages"), languagesCombo)

        addSubSection(localizationManager.getString("settings_window.service_selector_sub")) {
            servicesCombo = enumCombo(RegionSlot.entries.toTypedArray(), texts::slot) {
                writeLayout { layout -> layout.copy(services = it) }
            }
            addRow(localizationManager.getString("settings_window.layout_row_position"), servicesCombo)

            widgetCombo = enumCombo(ServiceWidget.entries.toTypedArray(), texts::widget) {
                writeLayout { layout -> layout.copy(serviceWidget = it) }
            }
            addRow(localizationManager.getString("settings_window.layout_row_selector"), widgetCombo)

            appearanceCombo = enumCombo(ServiceSelectorAppearance.entries.toTypedArray(), texts::appearance) {
                writeLayout { layout -> layout.copy(stripAppearance = it) }
            }
            addRow(localizationManager.getString("settings_window.layout_row_appearance"), appearanceCombo)
        }

        shareRowsCheck = addCheckbox(
            localizationManager.getString("settings_window.layout_row_share_rows"),
            false
        ) { shared -> writeLayout { it.copy(shareRows = shared) } }

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
        LayoutCard.entries.forEach { card ->
            val button = JButton(presetTitle(card)).apply {
                toolTipText = presetHint(card)
                putClientProperty(
                    FlatClientProperties.BUTTON_TYPE,
                    FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON
                )
                addActionListener { if (!isUpdatingFromState) choosePreset(card) }
            }
            presetCards[card] = button
            grid.add(button)
            registerSearchEntry(presetTitle(card), button, hint = presetHint(card))
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

    private fun choosePreset(card: LayoutCard) {
        if (card == LayoutCard.COMPARISON && !comparisonAvailable) {
            onOpenServicesSettings()
            return
        }
        writeLayout { current -> card.recipe.applyTo(current) }
    }

    /** The single write path for the whole page: one whole [WindowLayout] per change. */
    private fun writeLayout(transform: (WindowLayout) -> WindowLayout) {
        applyDraft(store) { configuration ->
            configuration.withWindowLayout(transform(configuration.windowLayout))
        }
    }

    private fun summaryOf(layout: WindowLayout): String = localizationManager.getString(
        "settings_window.layout_summary_format",
        texts.pane(layout.panes),
        texts.slot(layout.history),
        texts.slot(layout.languages),
        if (layout.panes == PaneLayout.COMPARISON) {
            localizationManager.getString("settings_window.layout_selector_absent")
        } else {
            texts.slot(layout.services)
        },
    )

    private fun presetTitle(card: LayoutCard) = when (card) {
        LayoutCard.CLASSIC -> localizationManager.getString("settings_window.layout_card_classic")
        LayoutCard.COMPACT -> localizationManager.getString("settings_window.layout_card_compact")
        LayoutCard.SIDE_BY_SIDE -> localizationManager.getString("settings_window.layout_card_side_by_side")
        LayoutCard.COMPARISON ->
            if (comparisonAvailable) localizationManager.getString("settings_window.layout_card_comparison")
            else comparisonConfigureLabel()
    }

    private fun presetHint(card: LayoutCard) = when (card) {
        LayoutCard.CLASSIC -> localizationManager.getString("settings_window.layout_card_classic_hint")
        LayoutCard.COMPACT -> localizationManager.getString("settings_window.layout_card_compact_hint")
        LayoutCard.SIDE_BY_SIDE -> localizationManager.getString("settings_window.layout_card_side_by_side_hint")
        LayoutCard.COMPARISON ->
            if (comparisonAvailable) localizationManager.getString("settings_window.layout_card_comparison_hint")
            else comparisonUnavailableHint()
    }

    /** Enables [control] when [availability] allows, and otherwise says why it does nothing. */
    private fun gate(control: JComponent, availability: LayoutAvailability) {
        control.isEnabled = availability == LayoutAvailability.Enabled
        control.toolTipText = (availability as? LayoutAvailability.Disabled)?.let { texts.reason(it.reason) }
    }

    override fun render(state: SettingsState) {
        val configuration = state.workingConfiguration
        val layout = configuration.windowLayout
        val translatorIds = availableTranslatorIds()
        comparisonAvailable = configuration.isComparisonEligible(translatorIds)
        val effectivePanes = configuration.effectivePaneLayout(translatorIds)

        withoutTrigger {
            summaryLabel.text = summaryOf(layout)
            panesCombo.selectedItem = layout.panes
            historyCombo.selectedItem = layout.history
            languagesCombo.selectedItem = layout.languages
            servicesCombo.selectedItem = layout.services
            widgetCombo.selectedItem = layout.serviceWidget
            appearanceCombo.selectedItem = layout.stripAppearance
            shareRowsCheck.isSelected = layout.shareRows
            statusCheck.isSelected = layout.showStatusBar

            // Under a comparison board the header switches the translator, so the selector's own
            // values are kept but cannot change anything until the panes change back.
            val servicesAvailability = layout.servicesControlsAvailability(effectivePanes)
            gate(servicesCombo, servicesAvailability)
            gate(widgetCombo, servicesAvailability)
            // The strip's labels only exist while the strip does.
            gate(appearanceCombo, layout.appearanceAvailability(effectivePanes))
            panesCombo.selectedItem?.let {
                if (it == PaneLayout.COMPARISON) panesCombo.toolTipText = comparisonUnavailableHint()
            }

            presetCards.forEach { (card, button) ->
                button.text = presetTitle(card)
                button.toolTipText = presetHint(card)
            }
        }
    }

    private companion object {
        const val CARD_GRID_PADDING = 8
    }
}
