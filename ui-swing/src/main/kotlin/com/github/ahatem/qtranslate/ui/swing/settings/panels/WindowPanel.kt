package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.main.layout.LayoutManager
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class WindowPanel(private val store: SettingsStore) : SettingsPanel() {

    private val layouts = LayoutManager.getAvailableLayouts().map {
        LayoutInfo(it.id, it.id.replaceFirstChar { c -> c.uppercase() })
    }

    private lateinit var layoutCombo:          JComboBox<LayoutInfo>
    private lateinit var historyCheck:         JCheckBox
    private lateinit var languageCheck:        JCheckBox
    private lateinit var servicesCheck:        JCheckBox
    private lateinit var statusCheck:          JCheckBox
    private lateinit var autoSizeCheck:        JCheckBox
    private lateinit var autoPositionCheck:    JCheckBox
    private lateinit var transparencySlider:   JSlider
    private lateinit var transparencyLabel:    JLabel

    init { buildUI() }

    private fun buildUI() {
        // ---- Layout ----
        addSeparator("Layout")

        layoutCombo = JComboBox<LayoutInfo>(layouts.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val layout = selectedItem as? LayoutInfo ?: return@addActionListener
                    applyDraft(store) { it.copy(layoutPresetId = layout.id) }
                }
            }
        }
        addRow("Layout Preset:", layoutCombo)

        // ---- Toolbars ----
        addSeparator("Toolbars")

        historyCheck = addCheckbox("Show history navigation bar", false) { enabled ->
            // Use applyDraft so we always read the freshest config before copying
            applyDraft(store) { it.copy(toolbarVisibility = it.toolbarVisibility.copy(isHistoryBarVisible = enabled)) }
        }
        languageCheck = addCheckbox("Show language selector bar", false) { enabled ->
            applyDraft(store) { it.copy(toolbarVisibility = it.toolbarVisibility.copy(isLanguageBarVisible = enabled)) }
        }
        servicesCheck = addCheckbox("Show services panel", false) { enabled ->
            applyDraft(store) { it.copy(toolbarVisibility = it.toolbarVisibility.copy(isServicesPanelVisible = enabled)) }
        }
        statusCheck = addCheckbox("Show status bar", false) { enabled ->
            applyDraft(store) { it.copy(toolbarVisibility = it.toolbarVisibility.copy(isStatusBarVisible = enabled)) }
        }

        // ---- Quick Translate Popup ----
        addSeparator("Quick Translate Popup")

        autoSizeCheck = addCheckbox("Auto-size popup to content", false) { enabled ->
            applyDraft(store) { it.copy(isPopupAutoSizeEnabled = enabled) }
        }
        autoPositionCheck = addCheckbox("Auto-position popup near the cursor", false) { enabled ->
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
                if (!valueIsAdjusting && !isUpdatingFromState)
                    applyDraft(store) { it.copy(popupTransparencyPercentage = value) }
            }
        }
        addRow("Background Transparency:", JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(transparencySlider, BorderLayout.CENTER)
            add(transparencyLabel,  BorderLayout.EAST)
        })

        finishLayout()
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            layoutCombo.selectedItem = layouts.find { it.id == c.layoutPresetId }
            historyCheck.isSelected  = c.toolbarVisibility.isHistoryBarVisible
            languageCheck.isSelected = c.toolbarVisibility.isLanguageBarVisible
            servicesCheck.isSelected = c.toolbarVisibility.isServicesPanelVisible
            statusCheck.isSelected   = c.toolbarVisibility.isStatusBarVisible
            autoSizeCheck.isSelected     = c.isPopupAutoSizeEnabled
            autoPositionCheck.isSelected = c.isPopupAutoPositionEnabled
            transparencySlider.value = c.popupTransparencyPercentage
            transparencyLabel.text   = "${c.popupTransparencyPercentage}%"
        }
    }

    private data class LayoutInfo(val id: String, val displayName: String)
}