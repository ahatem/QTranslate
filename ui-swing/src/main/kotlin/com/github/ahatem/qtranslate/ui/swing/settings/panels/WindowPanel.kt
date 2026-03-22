package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.main.layout.LayoutManager
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class WindowPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private val layouts = LayoutManager.getAvailableLayouts().map {
        LayoutInfo(it.id, it.id.replaceFirstChar { c -> c.uppercase() })
    }

    private lateinit var layoutCombo: JComboBox<LayoutInfo>
    private lateinit var historyCheck: JCheckBox
    private lateinit var languageCheck: JCheckBox
    private lateinit var servicesCheck: JCheckBox
    private lateinit var statusCheck: JCheckBox
    private lateinit var autoSizeCheck: JCheckBox
    private lateinit var autoPositionCheck: JCheckBox
    private lateinit var transparencySlider: JSlider
    private lateinit var transparencyLabel: JLabel

    init { buildUI() }

    private fun buildUI() {
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

        addSeparator(localizationManager.getString("settings_window.toolbars_group"))

        historyCheck = addCheckbox(
            localizationManager.getString("settings_window.show_history_bar"),
            false
        ) { enabled ->
            applyDraft(store) {
                it.copy(
                    toolbarVisibility = it.toolbarVisibility.copy(
                        isHistoryBarVisible = enabled
                    )
                )
            }
        }

        languageCheck = addCheckbox(
            localizationManager.getString("settings_window.show_language_bar"),
            false
        ) { enabled ->
            applyDraft(store) {
                it.copy(
                    toolbarVisibility = it.toolbarVisibility.copy(
                        isLanguageBarVisible = enabled
                    )
                )
            }
        }

        servicesCheck = addCheckbox(
            localizationManager.getString("settings_window.show_services_panel"),
            false
        ) { enabled ->
            applyDraft(store) {
                it.copy(
                    toolbarVisibility = it.toolbarVisibility.copy(
                        isServicesPanelVisible = enabled
                    )
                )
            }
        }

        statusCheck = addCheckbox(
            localizationManager.getString("settings_window.show_status_bar"),
            false
        ) { enabled ->
            applyDraft(store) {
                it.copy(
                    toolbarVisibility = it.toolbarVisibility.copy(
                        isStatusBarVisible = enabled
                    )
                )
            }
        }

        addSeparator(localizationManager.getString("settings_window.popup_group"))

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

        transparencyLabel = JLabel("0%").apply {
            preferredSize = Dimension(48, preferredSize.height)
            horizontalAlignment = SwingConstants.RIGHT
        }

        transparencySlider = JSlider(0, 100, 0).apply {
            majorTickSpacing = 25
            minorTickSpacing = 5
            paintTicks = true
            addChangeListener {
                transparencyLabel.text = "${value}%"
                if (!valueIsAdjusting && !isUpdatingFromState) {
                    applyDraft(store) { it.copy(popupTransparencyPercentage = value) }
                }
            }
        }

        addRow(
            localizationManager.getString("settings_window.transparency"),
            JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(transparencySlider, BorderLayout.CENTER)
                add(transparencyLabel, BorderLayout.EAST)
            }
        )

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
            autoSizeCheck.isSelected = c.isPopupAutoSizeEnabled
            autoPositionCheck.isSelected = c.isPopupAutoPositionEnabled
            transparencySlider.value = c.popupTransparencyPercentage
            transparencyLabel.text = "${c.popupTransparencyPercentage}%"
        }
    }

    private data class LayoutInfo(val id: String, val displayName: String)
}