package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.CloseButtonBehavior
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.main.layout.LayoutManager
import java.awt.BorderLayout
import javax.swing.*

class WindowPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private val layouts = LayoutManager.getAvailableLayouts().map {
        LayoutInfo(it.id, localizationManager.getString("main_window_main_menu.${it.localizeId}"))
    }

    private lateinit var layoutCombo: JComboBox<LayoutInfo>
    private lateinit var closeOnClickOutsideCheck: JCheckBox
    private lateinit var historyCheck: JCheckBox
    private lateinit var languageCheck: JCheckBox
    private lateinit var servicesCheck: JCheckBox
    private lateinit var selectorStyleCombo: JComboBox<ServiceSelectorStyleInfo>
    private lateinit var selectorAppearanceCombo: JComboBox<ServiceSelectorAppearanceInfo>
    private lateinit var statusCheck: JCheckBox
    private lateinit var dictionaryPanelCheck: JCheckBox
    private lateinit var autoSizeCheck: JCheckBox
    private lateinit var autoPositionCheck: JCheckBox
    private lateinit var transparencySpinner: JSpinner
    private lateinit var popupIdleSpinner: JSpinner
    private lateinit var dictTransparencySpinner: JSpinner
    private lateinit var dictIdleSpinner: JSpinner
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

        // ── Toolbars ─────────────────────────────────────────────────────────
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

        servicesCheck = addCheckbox(
            localizationManager.getString("settings_window.show_services_panel"),
            false
        ) { enabled ->
            applyDraft(store) {
                it.copy(toolbarVisibility = it.toolbarVisibility.copy(isServicesPanelVisible = enabled))
            }
        }

        val selectorStyles = listOf(
            ServiceSelectorStyleInfo(com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle.CLASSIC, localizationManager.getString("settings_window.service_selector_classic")),
            ServiceSelectorStyleInfo(com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle.ENHANCED, localizationManager.getString("settings_window.service_selector_enhanced"))
        )
        selectorStyleCombo = JComboBox(selectorStyles.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName.orEmpty()) }
            addActionListener { if (!isUpdatingFromState) (selectedItem as? ServiceSelectorStyleInfo)?.let { selected -> applyDraft(store) { it.copy(serviceSelectorStyle = selected.value) } } }
        }
        addRow(localizationManager.getString("settings_window.service_selector_style"), selectorStyleCombo)

        val appearances = listOf(
            ServiceSelectorAppearanceInfo(com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance.ICONS_ONLY, localizationManager.getString("settings_window.service_selector_icons")),
            ServiceSelectorAppearanceInfo(com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance.ICONS_AND_TEXT, localizationManager.getString("settings_window.service_selector_icons_text")),
            ServiceSelectorAppearanceInfo(com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance.TEXT_ONLY, localizationManager.getString("settings_window.service_selector_text"))
        )
        selectorAppearanceCombo = JComboBox(appearances.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName.orEmpty()) }
            addActionListener { if (!isUpdatingFromState) (selectedItem as? ServiceSelectorAppearanceInfo)?.let { selected -> applyDraft(store) { it.copy(serviceSelectorAppearance = selected.value) } } }
        }
        addRow(localizationManager.getString("settings_window.service_selector_appearance"), selectorAppearanceCombo)

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

        // ── Popup Windows (translation popup + dictionary popup under one header) ──
        addSeparator(localizationManager.getString("settings_window.popup_group"))

        // Translation Popup sub-section
        addSubSeparator(localizationManager.getString("settings_window.translation_popup_sub"))

        autoSizeCheck = addCheckbox(
            localizationManager.getString("settings_window.auto_size"),
            false
        ) { enabled ->
            applyDraft(store) { it.copy(isPopupAutoSizeEnabled = enabled) }
        }

        autoPositionCheck = addCheckbox(
            localizationManager.getString("settings_window.auto_position"),
            false
        ) { enabled ->
            applyDraft(store) { it.copy(isPopupAutoPositionEnabled = enabled) }
        }

        transparencySpinner = JSpinner(SpinnerNumberModel(5, 5, 50, 5)).apply {
            addChangeListener {
                if (!isUpdatingFromState) {
                    applyDraft(store) { it.copy(popupTransparencyPercentage = value as Int) }
                }
            }
        }
        addRow(
            localizationManager.getString("settings_window.transparency"),
            JPanel(BorderLayout(4, 0)).apply {
                isOpaque = false
                add(transparencySpinner, BorderLayout.LINE_START)
                add(JLabel("%"), BorderLayout.CENTER)
            }
        )

        popupIdleSpinner = JSpinner(SpinnerNumberModel(3, 2, 60, 1)).apply {
            addChangeListener {
                if (!isUpdatingFromState) {
                    applyDraft(store) { it.copy(popupIdleTimeoutSeconds = value as Int) }
                }
            }
        }
        addRow(
            localizationManager.getString("settings_window.popup_idle_timeout"),
            JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(popupIdleSpinner, BorderLayout.LINE_START)
                add(JLabel(localizationManager.getString("settings_window.seconds_unit")), BorderLayout.CENTER)
            }
        )
        addHint(localizationManager.getString("settings_window.popup_idle_hint"))

        closeOnClickOutsideCheck = addCheckbox(
            localizationManager.getString("settings_window.close_popup_on_click_outside"),
            true
        ) { enabled ->
            applyDraft(store) { it.copy(closePopupsOnClickOutside = enabled) }
        }
        addHint(localizationManager.getString("settings_window.close_popup_on_click_outside_hint"))

        // Dictionary Popup sub-section
        addSubSeparator(localizationManager.getString("settings_window.dict_popup_sub"))

        dictTransparencySpinner = JSpinner(SpinnerNumberModel(5, 5, 50, 5)).apply {
            addChangeListener {
                if (!isUpdatingFromState) {
                    applyDraft(store) { it.copy(quickDictionaryTransparencyPercentage = value as Int) }
                }
            }
        }
        addRow(
            localizationManager.getString("settings_window.transparency"),
            JPanel(BorderLayout(4, 0)).apply {
                isOpaque = false
                add(dictTransparencySpinner, BorderLayout.LINE_START)
                add(JLabel("%"), BorderLayout.CENTER)
            }
        )

        dictIdleSpinner = JSpinner(SpinnerNumberModel(8, 2, 60, 1)).apply {
            addChangeListener {
                if (!isUpdatingFromState) {
                    applyDraft(store) { it.copy(quickDictionaryIdleTimeoutSeconds = value as Int) }
                }
            }
        }
        addRow(
            localizationManager.getString("settings_window.popup_idle_timeout"),
            JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(dictIdleSpinner, BorderLayout.LINE_START)
                add(JLabel(localizationManager.getString("settings_window.seconds_unit")), BorderLayout.CENTER)
            }
        )
        addHint(localizationManager.getString("settings_window.popup_idle_hint"))

        // ── Close Button Behavior ─────────────────────────────────────────────
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
            selectorStyleCombo.selectedItem = (0 until selectorStyleCombo.itemCount).map(selectorStyleCombo::getItemAt).find { it.value == c.serviceSelectorStyle }
            selectorAppearanceCombo.selectedItem = (0 until selectorAppearanceCombo.itemCount).map(selectorAppearanceCombo::getItemAt).find { it.value == c.serviceSelectorAppearance }
            statusCheck.isSelected = c.toolbarVisibility.isStatusBarVisible
            dictionaryPanelCheck.isSelected = c.showDictionaryPanel
            autoSizeCheck.isSelected = c.isPopupAutoSizeEnabled
            autoPositionCheck.isSelected = c.isPopupAutoPositionEnabled
            transparencySpinner.value = c.popupTransparencyPercentage.coerceIn(5, 50)
            popupIdleSpinner.value = c.popupIdleTimeoutSeconds
            closeOnClickOutsideCheck.isSelected = c.closePopupsOnClickOutside
            dictTransparencySpinner.value = c.quickDictionaryTransparencyPercentage.coerceIn(5, 50)
            dictIdleSpinner.value = c.quickDictionaryIdleTimeoutSeconds
            closeButtonCombo.selectedItem = (0 until closeButtonCombo.itemCount)
                .map { closeButtonCombo.getItemAt(it) }
                .find { it.behavior == c.closeButtonBehavior }
        }
    }

    private data class LayoutInfo(val id: String, val displayName: String)
    private data class ServiceSelectorStyleInfo(val value: com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle, val displayName: String)
    private data class ServiceSelectorAppearanceInfo(val value: com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance, val displayName: String)
    private data class CloseButtonBehaviorInfo(val behavior: CloseButtonBehavior, val displayName: String)
}
