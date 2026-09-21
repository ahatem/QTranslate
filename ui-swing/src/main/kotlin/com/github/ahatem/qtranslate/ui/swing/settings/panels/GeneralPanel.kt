package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.SelectionBehavior
import com.github.ahatem.qtranslate.core.settings.data.SelectionReadSource
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel

/**
 * Application-wide settings that belong to no other page: startup, updates, the selection
 * button, and history.
 *
 * The default target language used to sit at the top of this page. It was the only language
 * setting outside [LanguagesPanel], which is where it now lives alongside a default source.
 */
class GeneralPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private lateinit var launchCheckbox:  JCheckBox
    private lateinit var updatesCheckbox: JCheckBox
    private lateinit var historyCheckbox: JCheckBox
    private lateinit var clearCheckbox:   JCheckBox
    private lateinit var selectionBehaviorCombo: JComboBox<SelectionBehaviorInfo>
    private lateinit var selectionReadSourceCombo: JComboBox<SelectionReadSourceInfo>

    private data class SelectionBehaviorInfo(
        val behavior: SelectionBehavior,
        val displayName: String
    )

    private data class SelectionReadSourceInfo(
        val source: SelectionReadSource,
        val displayName: String
    )

    init { buildUI() }

    private fun buildUI() {
        // ── Startup & Updates (merged — two closely related checkboxes, not two separate sections)
        addSeparator(localizationManager.getString("settings_general.startup_updates_group"))

        launchCheckbox = addCheckbox(
            text = localizationManager.getString("settings_general.launch_on_startup"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(launchOnSystemStartup = enabled) } }
        )
        updatesCheckbox = addCheckbox(
            text = localizationManager.getString("settings_general.auto_check_updates"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(autoCheckForUpdates = enabled) } }
        )

        // ── Selection
        addSeparator(localizationManager.getString("settings_general.selection_group"))

        val selectionBehaviors = listOf(
            SelectionBehaviorInfo(SelectionBehavior.OFF, localizationManager.getString("settings_general.selection_behavior_off")),
            SelectionBehaviorInfo(SelectionBehavior.SHOW_ICON, localizationManager.getString("settings_general.selection_behavior_icon")),
            SelectionBehaviorInfo(SelectionBehavior.TRANSLATE, localizationManager.getString("settings_general.selection_behavior_translate")),
            SelectionBehaviorInfo(SelectionBehavior.TRANSLATE_AND_READ, localizationManager.getString("settings_general.selection_behavior_read"))
        )
        selectionBehaviorCombo = JComboBox(selectionBehaviors.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val behavior = (selectedItem as? SelectionBehaviorInfo)?.behavior ?: return@addActionListener
                    applyDraft(store) { it.copy(selectionBehavior = behavior) }
                }
            }
        }
        addRow(localizationManager.getString("settings_general.selection_behavior"), selectionBehaviorCombo)
        addHint(localizationManager.getString("settings_general.selection_behavior_hint"))

        val selectionReadSources = listOf(
            SelectionReadSourceInfo(
                SelectionReadSource.SOURCE,
                localizationManager.getString("settings_general.selection_read_original")
            ),
            SelectionReadSourceInfo(
                SelectionReadSource.TRANSLATION,
                localizationManager.getString("settings_dialog_sidebar.translation")
            )
        )
        selectionReadSourceCombo = JComboBox(selectionReadSources.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val source = (selectedItem as? SelectionReadSourceInfo)?.source
                        ?: return@addActionListener
                    applyDraft(store) { it.copy(selectionReadSource = source) }
                }
            }
        }
        addRow(localizationManager.getString("settings_general.selection_read_aloud"), selectionReadSourceCombo)

        // ── History
        addSeparator(localizationManager.getString("settings_general.history_group"))

        historyCheckbox = addCheckbox(
            text = localizationManager.getString("settings_general.enable_history"),
            selected = false,
            onChange = { enabled ->
                applyDraft(store) { it.copy(isHistoryEnabled = enabled) }
            }
        )
        clearCheckbox = addCheckbox(
            text = localizationManager.getString("settings_general.clear_history_on_exit"),
            selected = false,
            enabled = false,
            onChange = { enabled -> applyDraft(store) { it.copy(clearHistoryOnExit = enabled) } }
        )
        addHint(localizationManager.getString("settings_general.clear_history_hint"))

        finishLayout()
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration

        withoutTrigger {
            launchCheckbox.isSelected  = c.launchOnSystemStartup
            updatesCheckbox.isSelected = c.autoCheckForUpdates
            for (index in 0 until selectionBehaviorCombo.itemCount) {
                if (selectionBehaviorCombo.getItemAt(index).behavior == c.selectionBehavior) {
                    selectionBehaviorCombo.selectedIndex = index
                    break
                }
            }
            for (index in 0 until selectionReadSourceCombo.itemCount) {
                if (selectionReadSourceCombo.getItemAt(index).source == c.selectionReadSource) {
                    selectionReadSourceCombo.selectedIndex = index
                    break
                }
            }
            selectionReadSourceCombo.isEnabled = c.selectionBehavior == SelectionBehavior.TRANSLATE_AND_READ
            historyCheckbox.isSelected = c.isHistoryEnabled
            clearCheckbox.isSelected   = c.clearHistoryOnExit
            clearCheckbox.isEnabled    = c.isHistoryEnabled
        }
    }
}
