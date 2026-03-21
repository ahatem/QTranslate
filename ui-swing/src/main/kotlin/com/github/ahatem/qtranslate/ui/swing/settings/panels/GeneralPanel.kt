package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import javax.swing.JCheckBox

class GeneralPanel(private val store: SettingsStore) : SettingsPanel() {

    private lateinit var launchCheckbox:  JCheckBox
    private lateinit var updatesCheckbox: JCheckBox
    private lateinit var historyCheckbox: JCheckBox
    private lateinit var clearCheckbox:   JCheckBox

    init { buildUI() }

    private fun buildUI() {
        addSeparator("Startup")
        launchCheckbox = addCheckbox(
            text = "Launch on system startup",
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(launchOnSystemStartup = enabled) } }
        )

        addSeparator("Updates")
        updatesCheckbox = addCheckbox(
            text = "Automatically check for updates",
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(autoCheckForUpdates = enabled) } }
        )

        addSeparator("History")
        historyCheckbox = addCheckbox(
            text = "Enable translation history",
            selected = false,
            onChange = { enabled ->
                applyDraft(store) { it.copy(isHistoryEnabled = enabled) }
            }
        )
        clearCheckbox = addCheckbox(
            text = "Clear history when the app closes",
            selected = false,
            enabled = false,
            onChange = { enabled -> applyDraft(store) { it.copy(clearHistoryOnExit = enabled) } }
        )
        addHint("Saved translations are permanently deleted on exit when this is on.")

        finishLayout()
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            launchCheckbox.isSelected  = c.launchOnSystemStartup
            updatesCheckbox.isSelected = c.autoCheckForUpdates
            historyCheckbox.isSelected = c.isHistoryEnabled
            clearCheckbox.isSelected   = c.clearHistoryOnExit
            clearCheckbox.isEnabled    = c.isHistoryEnabled
        }
    }
}