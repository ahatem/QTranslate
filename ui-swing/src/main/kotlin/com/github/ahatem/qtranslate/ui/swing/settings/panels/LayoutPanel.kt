package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.CloseButtonBehavior
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.main.layout.LayoutManager
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel

/**
 * The main window: how it is arranged, what it shows, and what its close button does.
 *
 * Split from the former Window & Layout page, which also held the floating popups. The two
 * were only ever neighbours: chrome is arranged once and left alone, while popup behaviour is
 * tuned repeatedly by anyone the popups annoy. See [PopupsPanel].
 */
class LayoutPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private val layouts = LayoutManager.getAvailableLayouts().map {
        LayoutInfo(it.id, localizationManager.getString("main_window_main_menu.${it.localizeId}"))
    }

    private lateinit var layoutCombo: JComboBox<LayoutInfo>
    private lateinit var historyCheck: JCheckBox
    private lateinit var languageCheck: JCheckBox
    private lateinit var servicesCheck: JCheckBox
    private lateinit var selectorStyleCombo: JComboBox<ServiceSelectorStyleInfo>
    private lateinit var selectorAppearanceCombo: JComboBox<ServiceSelectorAppearanceInfo>
    private lateinit var statusCheck: JCheckBox
    private lateinit var dictionaryPanelCheck: JCheckBox
    private lateinit var closeButtonCombo: JComboBox<CloseButtonBehaviorInfo>

    init {
        buildUI()
    }

    private fun buildUI() {
        // ── Layout ───────────────────────────────────────────────────────────
        addSeparator(localizationManager.getString("settings_window.layout_group"))

        layoutCombo = JComboBox<LayoutInfo>(layouts.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val layout = selectedItem as? LayoutInfo ?: return@addActionListener
                    applyDraft(store) { it.copy(layoutPresetId = layout.id) }
                }
            }
        }
        addRow(localizationManager.getString("settings_window.layout_preset"), layoutCombo)

        // ── Toolbars & panels ────────────────────────────────────────────────
        addSeparator(localizationManager.getString("settings_window.toolbars_group"))

        historyCheck = addCheckbox(
            localizationManager.getString("settings_window.show_history_bar"),
            false
        ) { enabled ->
            applyDraft(store) {
                it.copy(toolbarVisibility = it.toolbarVisibility.copy(isHistoryBarVisible = enabled))
            }
        }

        languageCheck = addCheckbox(
            localizationManager.getString("settings_window.show_language_bar"),
            false
        ) { enabled ->
            applyDraft(store) {
                it.copy(toolbarVisibility = it.toolbarVisibility.copy(isLanguageBarVisible = enabled))
            }
        }

        statusCheck = addCheckbox(
            localizationManager.getString("settings_window.show_status_bar"),
            false
        ) { enabled ->
            applyDraft(store) {
                it.copy(toolbarVisibility = it.toolbarVisibility.copy(isStatusBarVisible = enabled))
            }
        }

        dictionaryPanelCheck = addCheckbox(
            localizationManager.getString("settings_window.show_dictionary_panel"),
            false
        ) { enabled ->
            applyDraft(store) { it.copy(showDictionaryPanel = enabled) }
        }

        // ── Service selector ─────────────────────────────────────────────────
        // Its own sub-section rather than three unrelated-looking rows among the toolbars: the
        // two combo boxes only mean anything when the selector itself is showing.
        addSubSeparator(localizationManager.getString("settings_window.service_selector_sub"))

        servicesCheck = addCheckbox(
            localizationManager.getString("settings_window.show_services_panel"),
            false
        ) { enabled ->
            applyDraft(store) {
                it.copy(toolbarVisibility = it.toolbarVisibility.copy(isServicesPanelVisible = enabled))
            }
        }

        val selectorStyles = listOf(
            ServiceSelectorStyleInfo(
                ServiceSelectorStyle.CLASSIC,
                localizationManager.getString("settings_window.service_selector_classic")
            ),
            ServiceSelectorStyleInfo(
                ServiceSelectorStyle.ENHANCED,
                localizationManager.getString("settings_window.service_selector_enhanced")
            )
        )
        selectorStyleCombo = JComboBox(selectorStyles.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName.orEmpty()) }
            addActionListener {
                if (!isUpdatingFromState) {
                    (selectedItem as? ServiceSelectorStyleInfo)?.let { selected ->
                        applyDraft(store) { it.copy(serviceSelectorStyle = selected.value) }
                    }
                }
            }
        }
        addRow(localizationManager.getString("settings_window.service_selector_style"), selectorStyleCombo)

        val appearances = listOf(
            ServiceSelectorAppearanceInfo(
                ServiceSelectorAppearance.ICONS_ONLY,
                localizationManager.getString("settings_window.service_selector_icons")
            ),
            ServiceSelectorAppearanceInfo(
                ServiceSelectorAppearance.ICONS_AND_TEXT,
                localizationManager.getString("settings_window.service_selector_icons_text")
            ),
            ServiceSelectorAppearanceInfo(
                ServiceSelectorAppearance.TEXT_ONLY,
                localizationManager.getString("settings_window.service_selector_text")
            )
        )
        selectorAppearanceCombo = JComboBox(appearances.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName.orEmpty()) }
            addActionListener {
                if (!isUpdatingFromState) {
                    (selectedItem as? ServiceSelectorAppearanceInfo)?.let { selected ->
                        applyDraft(store) { it.copy(serviceSelectorAppearance = selected.value) }
                    }
                }
            }
        }
        addRow(
            localizationManager.getString("settings_window.service_selector_appearance"),
            selectorAppearanceCombo
        )

        // ── Close button ─────────────────────────────────────────────────────
        addSeparator(localizationManager.getString("settings_window.close_behavior_group"))

        val behaviorOptions = listOf(
            CloseButtonBehaviorInfo(
                CloseButtonBehavior.ASK,
                localizationManager.getString("settings_window.close_behavior_ask")
            ),
            CloseButtonBehaviorInfo(
                CloseButtonBehavior.MINIMIZE_TO_TRAY,
                localizationManager.getString("settings_window.close_behavior_minimize")
            ),
            CloseButtonBehaviorInfo(
                CloseButtonBehavior.EXIT,
                localizationManager.getString("settings_window.close_behavior_exit")
            )
        )

        closeButtonCombo = JComboBox(behaviorOptions.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val selected = selectedItem as? CloseButtonBehaviorInfo ?: return@addActionListener
                    applyDraft(store) { it.copy(closeButtonBehavior = selected.behavior) }
                }
            }
        }
        addRow(localizationManager.getString("settings_window.close_button"), closeButtonCombo)
        addHint(localizationManager.getString("settings_window.close_behavior_hint"))

        finishLayout()
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            layoutCombo.selectedItem = layouts.find { it.id == c.layoutPresetId }
            historyCheck.isSelected = c.toolbarVisibility.isHistoryBarVisible
            languageCheck.isSelected = c.toolbarVisibility.isLanguageBarVisible
            servicesCheck.isSelected = c.toolbarVisibility.isServicesPanelVisible
            statusCheck.isSelected = c.toolbarVisibility.isStatusBarVisible
            dictionaryPanelCheck.isSelected = c.showDictionaryPanel

            selectorStyleCombo.selectedItem = (0 until selectorStyleCombo.itemCount)
                .map(selectorStyleCombo::getItemAt)
                .find { it.value == c.serviceSelectorStyle }
            selectorAppearanceCombo.selectedItem = (0 until selectorAppearanceCombo.itemCount)
                .map(selectorAppearanceCombo::getItemAt)
                .find { it.value == c.serviceSelectorAppearance }

            // The selector's own options are meaningless while it is hidden.
            val selectorShown = c.toolbarVisibility.isServicesPanelVisible
            selectorStyleCombo.isEnabled = selectorShown
            selectorAppearanceCombo.isEnabled = selectorShown

            closeButtonCombo.selectedItem = (0 until closeButtonCombo.itemCount)
                .map { closeButtonCombo.getItemAt(it) }
                .find { it.behavior == c.closeButtonBehavior }
        }
    }

    private data class LayoutInfo(val id: String, val displayName: String)
    private data class ServiceSelectorStyleInfo(val value: ServiceSelectorStyle, val displayName: String)
    private data class ServiceSelectorAppearanceInfo(val value: ServiceSelectorAppearance, val displayName: String)
    private data class CloseButtonBehaviorInfo(val behavior: CloseButtonBehavior, val displayName: String)
}
