package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import javax.swing.*

class ServicesPanel(
    private val store: SettingsStore,
    private val pluginManager: PluginManager,
    private val scope: CoroutineScope
) : SettingsPanel() {

    private lateinit var presetCombo: JComboBox<PresetInfo>
    private lateinit var renameBtn:   JButton
    private lateinit var deleteBtn:   JButton
    private val serviceComboBoxes = mutableMapOf<ServiceType, JComboBox<ServiceOption>>()

    init {
        buildUI()
        observePlugins()
    }

    private fun buildUI() {
        // ---- Active Preset ----
        addSeparator("Active Preset")

        presetCombo = JComboBox<PresetInfo>().apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.name ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    (selectedItem as? PresetInfo)?.let {
                        store.dispatch(SettingsIntent.SetActivePreset(it.id))
                    }
                }
            }
        }
        addRow("Current Preset:", presetCombo)

        val newBtn    = JButton("New…").apply    { addActionListener { onNew() } }
        renameBtn     = JButton("Rename…").apply { addActionListener { onRename() } }
        deleteBtn     = JButton("Delete").apply  { addActionListener { onDelete() } }

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .anchor(GridBagConstraints.WEST)
            .insets(4, 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(newBtn)
                add(renameBtn)
                add(deleteBtn)
            })
        addHint("Presets let you save different service combinations and switch between them quickly.")

        // ---- Service Configuration ----
        addSeparator("Service Configuration")

        ServiceType.entries.forEach { type ->
            val combo = JComboBox<ServiceOption>().apply {
                setRenderer { _, value, _, _, _ -> JLabel(value?.name ?: "None") }
                addActionListener {
                    if (!isUpdatingFromState) {
                        val selected = selectedItem as? ServiceOption
                        store.dispatch(SettingsIntent.UpdateServiceInActivePreset(type, selected?.id))
                    }
                }
            }
            serviceComboBoxes[type] = combo
            addRow(type.displayLabel(), combo)
        }

        finishLayout()
    }

    private fun ServiceType.displayLabel() = when (this) {
        ServiceType.TRANSLATOR    -> "Translator:"
        ServiceType.TTS           -> "Text-to-Speech:"
        ServiceType.OCR           -> "OCR:"
        ServiceType.SPELL_CHECKER -> "Spell Checker:"
        ServiceType.DICTIONARY    -> "Dictionary:"
    }

    private fun observePlugins() {
        scope.launch {
            pluginManager.activeServices
                .map { it.values.groupBy { s -> s.type } }
                .distinctUntilChanged()
                .collect { servicesByType ->
                    SwingUtilities.invokeLater {
                        serviceComboBoxes.forEach { (type, combo) ->
                            val current = combo.selectedItem as? ServiceOption
                            combo.removeAllItems()
                            combo.addItem(null) // "None" option

                            servicesByType[type]?.forEach { service ->
                                combo.addItem(ServiceOption(service.id, service.name))
                            }

                            // Restore previous selection if still available
                            if (current != null) {
                                for (i in 0 until combo.itemCount) {
                                    if (combo.getItemAt(i)?.id == current.id) {
                                        combo.selectedIndex = i; break
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            presetCombo.removeAllItems()
            c.servicePresets.forEach { presetCombo.addItem(PresetInfo(it.id, it.name)) }

            val active = c.servicePresets.find { it.id == c.activeServicePresetId }
            active?.let { presetCombo.selectedItem = PresetInfo(it.id, it.name) }

            // Enable rename/delete only when there are presets to act on
            val hasPreset = active != null
            renameBtn.isEnabled = hasPreset
            deleteBtn.isEnabled = hasPreset && c.servicePresets.size > 1 // keep at least one

            active?.let { preset ->
                serviceComboBoxes.forEach { (type, combo) ->
                    val selectedId = preset.selectedServices[type]
                    for (i in 0 until combo.itemCount) {
                        val item = combo.getItemAt(i)
                        if (item?.id == selectedId || (selectedId == null && item == null)) {
                            combo.selectedIndex = i; break
                        }
                    }
                }
            }
        }
    }

    // ---- Preset dialogs ----

    private fun onNew() {
        val name = JOptionPane.showInputDialog(
            this, "Enter a name for the new preset:", "New Preset", JOptionPane.PLAIN_MESSAGE
        )
        if (!name.isNullOrBlank()) store.dispatch(SettingsIntent.CreatePreset(name.trim()))
    }

    private fun onRename() {
        val selected = presetCombo.selectedItem as? PresetInfo ?: return
        val newName  = JOptionPane.showInputDialog(
            this, "Enter new name:", "Rename Preset",
            JOptionPane.PLAIN_MESSAGE, null, null, selected.name
        ) as? String
        if (!newName.isNullOrBlank() && newName != selected.name)
            store.dispatch(SettingsIntent.RenamePreset(selected.id, newName.trim()))
    }

    private fun onDelete() {
        val selected = presetCombo.selectedItem as? PresetInfo ?: return
        val result = JOptionPane.showConfirmDialog(
            this,
            "Delete preset \"${selected.name}\"?\nThis cannot be undone.",
            "Delete Preset",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (result == JOptionPane.YES_OPTION) store.dispatch(SettingsIntent.DeletePreset(selected.id))
    }

    private data class PresetInfo(val id: String, val name: String)
    private data class ServiceOption(val id: String, val name: String)
}