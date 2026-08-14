package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.isServiceTypeEnabled
import com.github.ahatem.qtranslate.core.settings.data.withServiceTypeEnabled
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.*
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
    private val serviceEnabledChecks = mutableMapOf<ServiceType, JCheckBox>()

    init {
        buildUI()
        observePlugins()
    }

    private fun buildUI() {

        // ── Preset management ─────────────────────────────────────────────────
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
        addRow(localizationManager.getString("settings_services.current_preset"), presetCombo)

        val newBtn = JButton(localizationManager.getString("settings_services.new_preset_btn"))
            .apply { addActionListener { onNew() } }
        renameBtn = JButton(localizationManager.getString("settings_services.rename_preset_btn"))
            .apply { addActionListener { onRename() } }
        deleteBtn = JButton(localizationManager.getString("settings_services.delete_preset_btn"))
            .apply { addActionListener { onDelete() } }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .anchor(GridBagConstraints.LINE_START).insets(4, 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEADING, 4, 0)).apply {
                isOpaque = false
                add(newBtn); add(renameBtn); add(deleteBtn)
            })

        addHint(localizationManager.getString("settings_services.preset_hint"))

        // ── Service configuration — 2-column card grid ────────────────────────
        addSeparator(localizationManager.getString("settings_services.config_group"))

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0)
            .add(buildServiceGrid())

        finishLayout()
    }

    // ── 2-column service grid ─────────────────────────────────────────────────

    private fun buildServiceGrid(): JPanel {
        val grid = JPanel(GridLayout(0, 2, 12, 10)).apply { isOpaque = false }
        ServiceType.entries.forEach { type ->
            val combo = buildServiceCombo(type)
            serviceComboBoxes[type] = combo
            grid.add(buildServiceCard(type, combo))
        }
        return grid
    }

    private fun buildServiceCard(type: ServiceType, combo: JComboBox<ServiceOption>): JPanel {
        val icon = serviceIcon(type)
        val label = serviceLabel(type)
        val enabledCheck = JCheckBox(localizationManager.getString("settings_plugins.status_enabled"), true).apply {
            isOpaque = false
            addActionListener {
                if (!isUpdatingFromState) {
                    applyDraft(store) { it.withServiceTypeEnabled(type, isSelected) }
                }
            }
        }
        serviceEnabledChecks[type] = enabledCheck

        val header = JPanel(FlowLayout(FlowLayout.LEADING, 5, 0)).apply {
            isOpaque = false
            if (icon != null) add(JLabel(icon))
            add(JLabel(label).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
                font = font.deriveFont(font.size - 1f)
            })
            add(enabledCheck)
        }

        return JPanel(BorderLayout(0, 5)).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(combo, BorderLayout.CENTER)
        }
    }

    private fun buildServiceCombo(type: ServiceType): JComboBox<ServiceOption> =
        JComboBox<ServiceOption>().apply {
            setRenderer { _, value, _, _, _ ->
                JLabel(value?.name ?: localizationManager.getString("common.none"))
            }
            addActionListener {
                if (!isUpdatingFromState) {
                    store.dispatch(
                        SettingsIntent.UpdateServiceInActivePreset(type, (selectedItem as? ServiceOption)?.id)
                    )
                }
            }
        }

    /**
     * Loads a theme-aware 14×14 icon for [type] using [FlatSVGIcon] with a [FlatSVGIcon.ColorFilter]
     * that remaps all SVG colors to `Label.disabledForeground` at paint time.
     */
    private fun serviceIcon(type: ServiceType): Icon? {
        val path = when (type) {
            ServiceType.TRANSLATOR -> "icons/lucide/languages.svg"
            ServiceType.TTS -> "icons/lucide/volume.svg"
            ServiceType.OCR -> "icons/lucide/scan-text.svg"
            ServiceType.SPELL_CHECKER -> "icons/lucide/check.svg"
            ServiceType.DICTIONARY -> "icons/lucide/book-open.svg"
            ServiceType.SUMMARIZER -> "icons/lucide/text-align-start.svg"
            ServiceType.REWRITER -> "icons/lucide/pen-line.svg"
            // No service declares this yet; the generic icon is a placeholder until one does.
            ServiceType.IMAGE_SEARCH -> "icons/lucide/search.svg"
        }
        return runCatching {
            val icon = FlatSVGIcon(path, 14, 14, javaClass.classLoader)
            icon.colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Label.disabledForeground") ?: Color.GRAY }
            icon as Icon
        }.getOrNull()
    }

    private fun serviceLabel(type: ServiceType): String = when (type) {
        ServiceType.TRANSLATOR -> localizationManager.getString("settings_services.translator")
        ServiceType.TTS -> localizationManager.getString("settings_services.tts")
        ServiceType.OCR -> localizationManager.getString("settings_services.ocr")
        ServiceType.SPELL_CHECKER -> localizationManager.getString("settings_services.spell_checker")
        ServiceType.DICTIONARY -> localizationManager.getString("settings_services.dictionary")
        ServiceType.SUMMARIZER -> localizationManager.getString("settings_services.summarizer")
        ServiceType.REWRITER -> localizationManager.getString("settings_services.rewriter")
        ServiceType.IMAGE_SEARCH -> localizationManager.getString("settings_services.image_search")
    }

    // ── Plugin observation ────────────────────────────────────────────────────

    private fun observePlugins() {
        populateCombos(groupByType(pluginManager.activeServices.value))
        scope.launch {
            pluginManager.activeServices.collect { services ->
                SwingUtilities.invokeLater { populateCombos(groupByType(services)) }
            }
        }
    }

    /**
     * Groups by every capability a service declares, so one that both translates and defines
     * words is offered in both pickers. Takes the registry map rather than its values because
     * the key is the service's id, which the combo needs to store the selection.
     */
    private fun groupByType(services: Map<String, Service>): Map<ServiceType, List<ServiceOption>> {
        val result = mutableMapOf<ServiceType, MutableList<ServiceOption>>()
        services.forEach { (id, service) ->
            service.capabilities.forEach { capability ->
                result.getOrPut(capability) { mutableListOf() }.add(ServiceOption(id, service.name))
            }
        }
        return result
    }

    private fun populateCombos(servicesByType: Map<ServiceType, List<ServiceOption>>) {
        withoutTrigger {
            serviceComboBoxes.forEach { (type, combo) ->
                val current = combo.selectedItem as? ServiceOption
                combo.removeAllItems()
                combo.addItem(null) // "None" option
                servicesByType[type]?.forEach { option -> combo.addItem(option) }
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

    // ── Render ────────────────────────────────────────────────────────────────

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            presetCombo.removeAllItems()
            c.servicePresets.forEach { presetCombo.addItem(PresetInfo(it.id, localizedPresetName(it.name))) }

            val active = c.servicePresets.find { it.id == c.activeServicePresetId }
            active?.let { presetCombo.selectedItem = PresetInfo(it.id, localizedPresetName(it.name)) }

            val hasPreset = active != null
            renameBtn.isEnabled = hasPreset
            deleteBtn.isEnabled = hasPreset && c.servicePresets.size > 1

            ServiceType.entries.forEach { type ->
                val enabled = c.isServiceTypeEnabled(type)
                serviceEnabledChecks[type]?.isSelected = enabled
                serviceComboBoxes[type]?.isEnabled = enabled
            }

            active?.let { preset ->
                serviceComboBoxes.forEach { (type, combo) ->
                    val selectedId = preset.selectedServices[type]
                    for (i in 0 until combo.itemCount) {
                        if (combo.getItemAt(i)?.id == selectedId) {
                            combo.selectedIndex = i; break
                        }
                    }
                }
            }
        }
    }

    // ── Preset CRUD ───────────────────────────────────────────────────────────

    private fun onNew() {
        val name = JOptionPane.showInputDialog(
            this,
            localizationManager.getString("settings_services.new_preset_prompt"),
            localizationManager.getString("settings_services.new_preset_title"),
            JOptionPane.PLAIN_MESSAGE
        )
        if (!name.isNullOrBlank()) store.dispatch(SettingsIntent.CreatePreset(name.trim()))
    }

    private fun onRename() {
        val selected = presetCombo.selectedItem as? PresetInfo ?: return
        val newName = JOptionPane.showInputDialog(
            this,
            localizationManager.getString("settings_services.rename_preset_prompt"),
            localizationManager.getString("settings_services.rename_preset_title"),
            JOptionPane.PLAIN_MESSAGE, null, null, selected.name
        ) as? String
        if (!newName.isNullOrBlank() && newName != selected.name)
            store.dispatch(SettingsIntent.RenamePreset(selected.id, newName.trim()))
    }

    private fun onDelete() {
        val selected = presetCombo.selectedItem as? PresetInfo ?: return
        val result = JOptionPane.showConfirmDialog(
            this,
            localizationManager.getString("settings_services.delete_preset_confirm").format(selected.name),
            localizationManager.getString("settings_services.delete_preset_title"),
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (result == JOptionPane.YES_OPTION) store.dispatch(SettingsIntent.DeletePreset(selected.id))
    }

    private fun localizedPresetName(name: String): String =
        if (name == ServicePreset.DEFAULT_PRESET_NAME)
            localizationManager.getString("settings_services.default_preset_name")
        else name

    private data class PresetInfo(val id: String, val name: String)
    private data class ServiceOption(val id: String, val name: String)
}
