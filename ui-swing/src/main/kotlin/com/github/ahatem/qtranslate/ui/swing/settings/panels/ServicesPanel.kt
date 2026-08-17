package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.isServiceRoleEnabled
import com.github.ahatem.qtranslate.core.settings.data.withServiceRoleEnabled
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.util.roles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.*
import javax.swing.*
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet

class ServicesPanel(
    private val store: SettingsStore,
    private val pluginManager: PluginManager,
    private val localizationManager: LocalizationManager,
    private val scope: CoroutineScope
) : SettingsPanel() {

    private lateinit var presetCombo: JComboBox<PresetInfo>
    private lateinit var renameBtn: JButton
    private lateinit var deleteBtn: JButton
    private val serviceComboBoxes = mutableMapOf<ServiceRole, JComboBox<ServiceOption>>()
    private val serviceEnabledChecks = mutableMapOf<ServiceRole, JCheckBox>()

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
        // The same shape as the interface-language picker, through the same helper. These two rows
        // ask the identical question — a picker plus the actions that operate on it — and used to
        // answer it differently, two clicks apart in one dialog: labelled buttons on a row below
        // here, icon buttons inline there.
        renameBtn = pickerAction(
            Icons.EDIT,
            localizationManager.getString("settings_services.rename_preset_btn")
        ) { onRename() }
        deleteBtn = pickerAction(
            Icons.DELETE,
            localizationManager.getString("settings_services.delete_preset_btn")
        ) { onDelete() }

        addPickerRow(
            localizationManager.getString("settings_services.current_preset"),
            presetCombo,
            listOf(
                pickerAction(
                    Icons.ADD,
                    localizationManager.getString("settings_services.new_preset_btn")
                ) { onNew() },
                renameBtn,
                deleteBtn
            )
        )

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
        ServiceRole.entries.forEach { type ->
            val combo = buildServiceCombo(type)
            serviceComboBoxes[type] = combo
            grid.add(buildServiceCard(type, combo))
        }
        return grid
    }

    private fun buildServiceCard(type: ServiceRole, combo: JComboBox<ServiceOption>): JPanel {
        val icon = serviceIcon(type)
        val label = serviceLabel(type)
        val enabledCheck = JCheckBox(localizationManager.getString("settings_plugins.status_enabled"), true).apply {
            isOpaque = false
            addActionListener {
                if (!isUpdatingFromState) {
                    applyDraft(store) { it.withServiceRoleEnabled(type, isSelected) }
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

    private fun buildServiceCombo(type: ServiceRole): JComboBox<ServiceOption> =
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
    private fun serviceIcon(type: ServiceRole): Icon? {
        val path = when (type) {
            ServiceRole.TRANSLATOR -> Icons.TRANSLATE
            ServiceRole.TTS -> Icons.SPEAK
            ServiceRole.OCR -> Icons.OCR
            ServiceRole.SPELL_CHECKER -> Icons.CHECK
            ServiceRole.DICTIONARY -> Icons.DICTIONARY
            ServiceRole.SUMMARIZER -> Icons.SUMMARIZE
            ServiceRole.REWRITER -> Icons.EDIT
            ServiceRole.IMAGE_SEARCH -> Icons.SEARCH
        }
        return runCatching {
            val icon = IconSet.load(path, 14, 14)
            icon.colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Label.disabledForeground") ?: Color.GRAY }
            icon as Icon
        }.getOrNull()
    }

    private fun serviceLabel(type: ServiceRole): String = when (type) {
        ServiceRole.TRANSLATOR -> localizationManager.getString("settings_services.translator")
        ServiceRole.TTS -> localizationManager.getString("settings_services.tts")
        ServiceRole.OCR -> localizationManager.getString("settings_services.ocr")
        ServiceRole.SPELL_CHECKER -> localizationManager.getString("settings_services.spell_checker")
        ServiceRole.DICTIONARY -> localizationManager.getString("settings_services.dictionary")
        ServiceRole.SUMMARIZER -> localizationManager.getString("settings_services.summarizer")
        ServiceRole.REWRITER -> localizationManager.getString("settings_services.rewriter")
        ServiceRole.IMAGE_SEARCH -> localizationManager.getString("settings_services.image_search")
    }

    // ── Plugin observation ────────────────────────────────────────────────────

    private fun observePlugins() {
        populateCombos(groupByRole(pluginManager.activeServices.value))
        scope.launch {
            pluginManager.activeServices.collect { services ->
                SwingUtilities.invokeLater { populateCombos(groupByRole(services)) }
            }
        }
    }

    /**
     * Groups by every role a service declares, so one that both translates and defines
     * words is offered in both pickers. Takes the registry map rather than its values because
     * the key is the service's id, which the combo needs to store the selection.
     */
    private fun groupByRole(services: Map<String, Service>): Map<ServiceRole, List<ServiceOption>> {
        val result = mutableMapOf<ServiceRole, MutableList<ServiceOption>>()
        services.forEach { (id, service) ->
            service.roles.forEach { role ->
                result.getOrPut(role) { mutableListOf() }.add(ServiceOption(id, service.name))
            }
        }
        return result
    }

    private fun populateCombos(servicesByRole: Map<ServiceRole, List<ServiceOption>>) {
        withoutTrigger {
            serviceComboBoxes.forEach { (type, combo) ->
                val current = combo.selectedItem as? ServiceOption
                combo.removeAllItems()
                combo.addItem(null) // "None" option
                servicesByRole[type]?.forEach { option -> combo.addItem(option) }
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

            ServiceRole.entries.forEach { type ->
                val enabled = c.isServiceRoleEnabled(type)
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
