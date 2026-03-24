package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import javax.swing.*

class ServicesPanel(
    private val store: SettingsStore,
    private val pluginManager: PluginManager,
    private val localizationManager: LocalizationManager,
    private val scope: CoroutineScope
) : SettingsPanel() {

    private lateinit var presetCombo: JComboBox<PresetInfo>
    private lateinit var renameBtn: JButton
    private lateinit var deleteBtn: JButton
    private val serviceComboBoxes = mutableMapOf<ServiceType, JComboBox<ServiceOption>>()

    init {
        buildUI()
        observePlugins()
    }

    private fun buildUI() {

        addSeparator(localizationManager.getString("settings_services.presets_group"))

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

        addRow(
            localizationManager.getString("settings_services.current_preset"),
            presetCombo
        )

        val newBtn = JButton(
            localizationManager.getString("settings_services.new_preset_btn")
        ).apply { addActionListener { onNew() } }

        renameBtn = JButton(
            localizationManager.getString("settings_services.rename_preset_btn")
        ).apply { addActionListener { onRename() } }

        deleteBtn = JButton(
            localizationManager.getString("settings_services.delete_preset_btn")
        ).apply { addActionListener { onDelete() } }

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .anchor(GridBagConstraints.LINE_START)
            .insets(4, 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEADING, 4, 0)).apply {
                isOpaque = false
                add(newBtn)
                add(renameBtn)
                add(deleteBtn)
            })

        addHint(
            localizationManager.getString("settings_services.preset_hint")
        )


        addSeparator(
            localizationManager.getString("settings_services.config_group")
        )

        ServiceType.entries.forEach { type ->

            val combo = JComboBox<ServiceOption>().apply {
                setRenderer { _, value, _, _, _ ->
                    JLabel(value?.name ?: "None")
                }

                addActionListener {
                    if (!isUpdatingFromState) {
                        val selected = selectedItem as? ServiceOption
                        store.dispatch(
                            SettingsIntent.UpdateServiceInActivePreset(
                                type,
                                selected?.id
                            )
                        )
                    }
                }
            }

            serviceComboBoxes[type] = combo

            addRow(
                serviceLabel(type),
                combo
            )
        }

        finishLayout()
    }

    private fun serviceLabel(type: ServiceType): String =
        when (type) {
            ServiceType.TRANSLATOR ->
                localizationManager.getString("settings_services.translator")

            ServiceType.TTS ->
                localizationManager.getString("settings_services.tts")

            ServiceType.OCR ->
                localizationManager.getString("settings_services.ocr")

            ServiceType.SPELL_CHECKER ->
                localizationManager.getString("settings_services.spell_checker")

            ServiceType.DICTIONARY ->
                localizationManager.getString("settings_services.dictionary")

            ServiceType.SUMMARIZER ->
                localizationManager.getString("settings_services.summarizer")

            ServiceType.REWRITER ->
                localizationManager.getString("settings_services.rewriter")
        }

    private fun observePlugins() {
        populateCombos(groupByType(pluginManager.activeServices.value.values))

        scope.launch {
            pluginManager.activeServices
                .collect { services ->
                    SwingUtilities.invokeLater {
                        populateCombos(groupByType(services.values))
                    }
                }
        }
    }

    private fun groupByType(
        services: Collection<Service>
    ): Map<ServiceType, List<Service>> {
        val result = mutableMapOf<ServiceType, MutableList<Service>>()
        services.forEach { service ->
            val type = service.type ?: return@forEach
            result.getOrPut(type) { mutableListOf() }.add(service)
        }
        return result
    }

    private fun populateCombos(servicesByType: Map<ServiceType, List<Service>>) {
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

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            presetCombo.removeAllItems()
            c.servicePresets.forEach { presetCombo.addItem(PresetInfo(it.id, it.name)) }

            val active = c.servicePresets.find { it.id == c.activeServicePresetId }
            active?.let { presetCombo.selectedItem = PresetInfo(it.id, it.name) }

            val hasPreset = active != null
            renameBtn.isEnabled = hasPreset
            deleteBtn.isEnabled = hasPreset && c.servicePresets.size > 1 // keep at least one

            active?.let { preset ->
                serviceComboBoxes.forEach { (type, combo) ->
                    val selectedId = preset.selectedServices[type]
                    for (i in 0 until combo.itemCount) {
                        val item = combo.getItemAt(i)
                        if (item?.id == selectedId) {
                            combo.selectedIndex = i; break
                        }
                    }
                }
            }
        }
    }

    private fun onNew() {
        val name = JOptionPane.showInputDialog(
            this,
            localizationManager.getString("settings_services.new_preset_prompt"),
            localizationManager.getString("settings_services.new_preset_title"),
            JOptionPane.PLAIN_MESSAGE
        )

        if (!name.isNullOrBlank())
            store.dispatch(SettingsIntent.CreatePreset(name.trim()))
    }

    private fun onRename() {
        val selected = presetCombo.selectedItem as? PresetInfo ?: return

        val newName = JOptionPane.showInputDialog(
            this,
            localizationManager.getString("settings_services.rename_preset_prompt"),
            localizationManager.getString("settings_services.rename_preset_title"),
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            selected.name
        ) as? String

        if (!newName.isNullOrBlank() && newName != selected.name)
            store.dispatch(
                SettingsIntent.RenamePreset(
                    selected.id,
                    newName.trim()
                )
            )
    }

    private fun onDelete() {
        val selected = presetCombo.selectedItem as? PresetInfo ?: return

        val message =
            localizationManager
                .getString("settings_services.delete_preset_confirm")
                .format(selected.name)

        val result = JOptionPane.showConfirmDialog(
            this,
            message,
            localizationManager.getString("settings_services.delete_preset_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result == JOptionPane.YES_OPTION)
            store.dispatch(SettingsIntent.DeletePreset(selected.id))
    }

    private data class PresetInfo(val id: String, val name: String)
    private data class ServiceOption(val id: String, val name: String)
}