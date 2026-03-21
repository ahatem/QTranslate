package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Insets
import javax.swing.*

data class PresetSelectorState(
    val availablePresets: List<ServicePreset>,
    val activePresetId: String?,
    val isLoading: Boolean
)


class PresetSelector(
    private val iconManager: IconManager,
    private val onPresetSelected: (presetId: String) -> Unit,
    private val onEditPreset: (presetId: String) -> Unit,
    private val onManagePresets: () -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)) {

    private val presetComboBox = JComboBox<ServicePreset>()
    private val settingsButton: JButton
    private var isRendering = false

    init {
        isOpaque = false
        presetComboBox.renderer = PresetRenderer()

        settingsButton = JButton(iconManager.getIcon("icons/lucide/settings.svg", 18, 18)).apply {
            toolTipText = "Manage Service Presets"
            isFocusable = false
            putClientProperty("JButton.buttonType", "toolBarButton")
            margin = Insets(4, 8, 4, 8)
        }

        add(presetComboBox)
        add(settingsButton)

        setupListeners()
    }

    private fun setupListeners() {
        presetComboBox.addActionListener {
            if (!isRendering) {
                (presetComboBox.selectedItem as? ServicePreset)?.let {
                    onPresetSelected(it.id)
                }
            }
        }

        settingsButton.addActionListener {
            val popup = createSettingsPopupMenu()
            popup.show(settingsButton, 0, settingsButton.height)
        }
    }

    fun render(state: PresetSelectorState) {
        isRendering = true

        val currentPresetIds = (0 until presetComboBox.model.size)
            .map { presetComboBox.model.getElementAt(it).id }
        val newPresetIds = state.availablePresets.map { it.id }

        if (currentPresetIds != newPresetIds) {
            presetComboBox.model = DefaultComboBoxModel(state.availablePresets.toTypedArray())
        }

        val presetToSelect = state.availablePresets.find { it.id == state.activePresetId }
        if (presetComboBox.selectedItem != presetToSelect) {
            presetComboBox.selectedItem = presetToSelect
        }

        val isEnabled = !state.isLoading
        presetComboBox.isEnabled = isEnabled
        settingsButton.isEnabled = isEnabled

        isRendering = false
    }

    private fun createSettingsPopupMenu(): JPopupMenu {
        val popup = JPopupMenu()
        val activePreset = presetComboBox.selectedItem as? ServicePreset

        if (activePreset != null) {
            popup.add(JMenuItem("Edit '${activePreset.name}'...")).addActionListener {
                onEditPreset(activePreset.id)
            }
        }
        popup.add(JMenuItem("Manage Presets...")).addActionListener {
            onManagePresets()
        }

        popup.add(JMenuItem("New Preset...")).addActionListener {
            println("New Preset clicked!")
        }

        return popup
    }

    private class PresetRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is ServicePreset) {
                text = value.name
            }
            return this
        }
    }
}