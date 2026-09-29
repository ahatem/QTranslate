package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.isServiceRoleEnabled
import com.github.ahatem.qtranslate.core.settings.data.withServiceRoleEnabled
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.shared.util.roles
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DisplayValueRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class ServicesPanel(
    private val store: SettingsStore,
    private val activeServices: StateFlow<Map<String, Service>>,
    private val localizationManager: LocalizationManager,
    private val scope: CoroutineScope
) : SettingsPanel() {

    private lateinit var presetCombo: JComboBox<PresetInfo>
    private lateinit var renameBtn: JButton
    private lateinit var deleteBtn: JButton
    private lateinit var translatorSection: TranslatorSetSection
    private val serviceComboBoxes = mutableMapOf<ServiceRole, JComboBox<ServiceOption>>()
    private val serviceEnabledChecks = mutableMapOf<ServiceRole, JCheckBox>()
    private var servicesByRole: Map<ServiceRole, List<ServiceOption>> = emptyMap()
    private var lastState: SettingsState? = null

    init {
        buildUI()
        observePlugins()
    }

    private fun buildUI() {

        // ── Preset management ─────────────────────────────────────────────────
        addSeparator(localizationManager.getString("settings_services.presets_group"))

        presetCombo = JComboBox<PresetInfo>().apply {
            renderer = DisplayValueRenderer<PresetInfo>(text = { it?.name.orEmpty() })
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

        // ── Translator set ────────────────────────────────────────────────────
        // One ordered set, not a translator plus an unrelated comparison list: the first entry is
        // the Primary, and Comparison is what the set becomes once two of its members are usable.
        val translatorsTitle = localizationManager.getString("settings_services.translators_group")
        addSeparator(translatorsTitle, trailing = buildEnabledCheck(ServiceRole.TRANSLATOR))
        translatorSection = TranslatorSetSection(localizationManager, ::pickerAction) { store.dispatch(it) }
        registerSearchEntry(translatorsTitle, translatorSection)
        registerSearchEntry(
            localizationManager.getString("settings_services.translator_add"),
            translatorSection.addAnchor,
            localizationManager.getString("settings_services.translator_set_ready")
        )
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 8, 0)
            .add(translatorSection)

        // ── Every other role: one service each ────────────────────────────────
        addSeparator(localizationManager.getString("settings_services.other_services_group"))
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0)
            .add(buildOtherServices())

        finishLayout()
    }

    private fun buildOtherServices(): JPanel {
        val grid = JPanel(GridBagLayout()).apply { isOpaque = false }
        val gap = UIScale.scale(12)
        val vertical = UIScale.scale(2)
        ServiceRole.entries.filter { it != ServiceRole.TRANSLATOR }.forEachIndexed { row, role ->
            val label = role.readableName(localizationManager)
            val combo = buildServiceCombo(role)
            serviceComboBoxes[role] = combo
            registerSearchEntry(label, combo)

            fun cell(x: Int, weightX: Double, insets: Insets) = GridBagConstraints().apply {
                gridx = x
                gridy = row
                weightx = weightX
                fill = if (weightX > 0) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
                anchor = GridBagConstraints.LINE_START
                this.insets = insets
            }
            grid.add(
                JLabel(label, serviceRoleIcon(role), SwingConstants.LEADING).apply { iconTextGap = UIScale.scale(6) },
                cell(0, 0.0, Insets(vertical, 0, vertical, gap))
            )
            grid.add(combo, cell(1, 1.0, Insets(vertical, 0, vertical, 0)))
            grid.add(buildEnabledCheck(role), cell(2, 0.0, Insets(vertical, gap, vertical, 0)))
        }
        return grid
    }

    private fun buildEnabledCheck(role: ServiceRole): JCheckBox =
        JCheckBox(localizationManager.getString("settings_plugins.status_enabled"), true).apply {
            name = "role-enabled:${role.name}"
            isOpaque = false
            addActionListener {
                if (!isUpdatingFromState) {
                    applyDraft(store) { it.withServiceRoleEnabled(role, isSelected) }
                }
            }
            serviceEnabledChecks[role] = this
        }

    private fun buildServiceCombo(role: ServiceRole): JComboBox<ServiceOption> =
        JComboBox<ServiceOption>().apply {
            name = "service-combo:${role.name}"
            renderer = DisplayValueRenderer<ServiceOption>(
                text = { option ->
                    when {
                        option == null -> localizationManager.getString("settings_services.automatic")
                        option.available -> option.name
                        else -> "${option.name} (${localizationManager.getString("settings_services.unavailable_suffix")})"
                    }
                },
                isDisabled = { it?.available == false },
                tooltip = { it?.takeIf { option -> !option.available }?.id }
            )
            addActionListener {
                if (!isUpdatingFromState) {
                    store.dispatch(SettingsIntent.UpdateServiceInActivePreset(role, (selectedItem as? ServiceOption)?.id))
                }
            }
        }

    // ── Plugin observation ────────────────────────────────────────────────────

    private fun observePlugins() {
        servicesByRole = groupByRole(activeServices.value)
        scope.launch {
            activeServices.collect { services ->
                SwingUtilities.invokeLater {
                    servicesByRole = groupByRole(services)
                    lastState?.let(::render)
                }
            }
        }
    }

    /**
     * Groups by every role a service declares, so one that both translates and defines
     * words is offered in both pickers. Takes the registry map rather than its values because
     * the key is the service id, which the combo needs to store the selection.
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

    // ── Render ────────────────────────────────────────────────────────────────

    override fun render(state: SettingsState) {
        lastState = state
        val c = state.workingConfiguration
        withoutTrigger {
            presetCombo.removeAllItems()
            c.servicePresets.forEach { presetCombo.addItem(PresetInfo(it.id, localizedPresetName(it.name))) }

            val active = c.servicePresets.find { it.id == c.activeServicePresetId }
            active?.let { presetCombo.selectedItem = PresetInfo(it.id, localizedPresetName(it.name)) }

            val hasPreset = active != null
            renameBtn.isEnabled = hasPreset
            deleteBtn.isEnabled = hasPreset && c.servicePresets.size > 1

            ServiceRole.entries.forEach { role ->
                val enabled = c.isServiceRoleEnabled(role)
                serviceEnabledChecks[role]?.isSelected = enabled
                serviceComboBoxes[role]?.isEnabled = enabled
            }
            serviceComboBoxes.forEach { (role, combo) ->
                syncCombo(role, combo, active?.selectedServices?.get(role))
            }

            translatorSection.render(
                translatorSetModel(c, servicesByRole[ServiceRole.TRANSLATOR].orEmpty()),
                c.isServiceRoleEnabled(ServiceRole.TRANSLATOR)
            )
        }
    }

    /**
     * Rebuilds a combo's items only when they changed, so an ordinary selection never replaces the
     * model under the popup that produced it, then selects the saved choice.
     */
    private fun syncCombo(role: ServiceRole, combo: JComboBox<ServiceOption>, savedId: String?) {
        val choices = serviceChoices(servicesByRole[role].orEmpty(), savedId)
        val current = List(combo.itemCount) { combo.getItemAt(it) }
        if (current != choices) {
            combo.removeAllItems()
            choices.forEach { combo.addItem(it) }
        }
        combo.selectedIndex = choices.indexOfFirst { it?.id == savedId }.coerceAtLeast(0)
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
}
