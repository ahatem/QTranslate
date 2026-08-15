package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import javax.swing.JCheckBox

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
    private lateinit var selectionIconCheckbox: JCheckBox

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

        selectionIconCheckbox = addCheckbox(
            text = localizationManager.getString("settings_general.selection_icon"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(isSelectionIconEnabled = enabled) } }
        )
        addHint(localizationManager.getString("settings_general.selection_icon_hint"))

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
            selectionIconCheckbox.isSelected = c.isSelectionIconEnabled
            historyCheckbox.isSelected = c.isHistoryEnabled
            clearCheckbox.isSelected   = c.clearHistoryOnExit
            clearCheckbox.isEnabled    = c.isHistoryEnabled
        }
    }
}
