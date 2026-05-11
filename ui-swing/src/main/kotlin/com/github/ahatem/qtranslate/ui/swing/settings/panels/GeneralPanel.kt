package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.LanguageComboBox
import javax.swing.JCheckBox

class GeneralPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    /**
     * Returns the current list of languages supported by the active translator.
     * Evaluated at render time so it is always up-to-date.
     * AUTO is excluded — a default *target* language must be a concrete language.
     */
    private val availableLanguages: () -> List<LanguageCode>
) : SettingsPanel() {

    private lateinit var launchCheckbox:  JCheckBox
    private lateinit var updatesCheckbox: JCheckBox
    private lateinit var historyCheckbox: JCheckBox
    private lateinit var clearCheckbox:   JCheckBox

    /**
     * Picker for the default target language restored on every app startup.
     * Uses the same [LanguageComboBox] as the main window so search-by-typing,
     * RTL rendering, and display names are all consistent.
     */
    private val defaultLanguageCombo = LanguageComboBox(
        onLanguageSelected = { lang ->
            if (!isUpdatingFromState)
                applyDraft(store) { it.copy(preferredTargetLanguage = lang.tag) }
        },
        localizer = localizationManager
    )

    init { buildUI() }

    private fun buildUI() {
        // ── Default Language
        addSeparator(localizationManager.getString("settings_general.default_language_group"))
        addRow(
            localizationManager.getString("settings_general.default_target_language"),
            defaultLanguageCombo
        )
        addHint(localizationManager.getString("settings_general.default_target_language_hint"))

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

        // Exclude AUTO — a default target language must be a concrete language.
        val langs = availableLanguages().filter { it.tag != "auto" }
        val preferred = LanguageCode(c.preferredTargetLanguage)

        withoutTrigger {
            defaultLanguageCombo.render(
                availableLanguages    = langs,
                selectedLanguage      = preferred,
                autoDetectedLanguage  = null,
                isEnabled             = langs.isNotEmpty()
            )

            launchCheckbox.isSelected  = c.launchOnSystemStartup
            updatesCheckbox.isSelected = c.autoCheckForUpdates
            historyCheckbox.isSelected = c.isHistoryEnabled
            clearCheckbox.isSelected   = c.clearHistoryOnExit
            clearCheckbox.isEnabled    = c.isHistoryEnabled
        }
    }
}
