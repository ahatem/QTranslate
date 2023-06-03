package com.pnix.qtranslate.presentation.main_frame.menus

import com.pnix.qtranslate.common.Localizer
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.presentation.main_frame.layouts.LayoutFactory
import javax.swing.*
import kotlin.system.exitProcess


private class LayoutsPresetsSubMenu : JMenu(Localizer.localize("menu_item_layout_presets_text")) {
  init {
    LayoutFactory.availableLayouts.forEach {
      add(JCheckBoxMenuItem(it.presetName).apply {
        val presetId = it.presetId
        val listener = WindowKeyListeners.ChangeLayoutPreset(presetId)

        isSelected = Configurations.layoutPreset == presetId
        accelerator = listener.hotkey
        addActionListener(listener.action)
      })
    }
  }
}

private class OptionsExtendedSubMenu : JMenu(Localizer.localize("menu_item_extended_text")) {

  init {
    add(JMenuItem(Localizer.localize("menu_item_read_phonetically_text")).apply { isEnabled = false })
    add(JMenuItem(Localizer.localize("menu_item_clear_input_text")).apply { isEnabled = false })
    add(JMenuItem(Localizer.localize("menu_item_auto_cleanup_text")).apply { isEnabled = false })
    add(JMenuItem(Localizer.localize("menu_item_save_content_on_exit_text")).apply { isEnabled = false })
    addSeparator()
    add(LayoutsPresetsSubMenu())
    addSeparator()
    add(JCheckBoxMenuItem(Localizer.localize("menu_item_show_history_text")).apply {
      isSelected = Configurations.showHistoryPanel
      accelerator = WindowKeyListeners.ToggleHistoryPane.hotkey
      addActionListener(WindowKeyListeners.ToggleHistoryPane.action)
    })
    add(JCheckBoxMenuItem(Localizer.localize("menu_item_show_translation_options_text")).apply {
      isSelected = Configurations.showTranslationOptionsPanel
      accelerator = WindowKeyListeners.ToggleTranslationOptionsPane.hotkey
      addActionListener(WindowKeyListeners.ToggleTranslationOptionsPane.action)
    })
    add(JCheckBoxMenuItem(Localizer.localize("menu_item_show_services_text")).apply {
      isSelected = Configurations.showServicesPanel
      accelerator = WindowKeyListeners.ToggleServicesPane.hotkey
      addActionListener(WindowKeyListeners.ToggleServicesPane.action)
    })
    add(JCheckBoxMenuItem(Localizer.localize("menu_item_show_status_text")).apply {
      isSelected = Configurations.showStatusPanel
      accelerator = WindowKeyListeners.ToggleStatusPane.hotkey
      addActionListener(WindowKeyListeners.ToggleStatusPane.action)
    })
    addSeparator()
    add(JCheckBoxMenuItem(Localizer.localize("menu_item_minimize_on_minimize_text")).apply { isEnabled = false })
    add(JCheckBoxMenuItem(Localizer.localize("menu_item_minimize_on_close_text")).apply { isEnabled = false })
  }
}

class OptionsPopupMenu : JPopupMenu() {
  init {
    add(JCheckBoxMenuItem(Localizer.localize("menu_item_spell_check_text")).apply {
      isSelected = Configurations.spellChecking
      accelerator = WindowKeyListeners.ToggleSpellChecking.hotkey
      addActionListener(WindowKeyListeners.ToggleSpellChecking.action)
    })
    add(JCheckBoxMenuItem(Localizer.localize("menu_item_instant_translation_text")).apply {
      isSelected = Configurations.instantTranslation
      accelerator = WindowKeyListeners.ToggleInstantTranslation.hotkey
      addActionListener(WindowKeyListeners.ToggleInstantTranslation.action)
    })
    add(JCheckBoxMenuItem(Localizer.localize("menu_item_backward_translation_text")).apply {
      isSelected = Configurations.showBackwardTranslationPanel
      accelerator = WindowKeyListeners.ToggleBackwardTranslationPane.hotkey
      addActionListener(WindowKeyListeners.ToggleBackwardTranslationPane.action)
    })
    add(OptionsExtendedSubMenu())
    add(JSeparator())
    add(JMenuItem(Localizer.localize("menu_item_dictionary_text")).apply {
      isEnabled = false
      accelerator = WindowKeyListeners.OpenDictionaryDialog.hotkey
      addActionListener(WindowKeyListeners.OpenDictionaryDialog.action)
    })
    add(JMenuItem(Localizer.localize("menu_item_history_text")).apply {
      accelerator = WindowKeyListeners.OpenHistoryDialog.hotkey
      addActionListener(WindowKeyListeners.OpenHistoryDialog.action)
    })
    add(JMenuItem(Localizer.localize("menu_item_settings_text")).apply {
      accelerator = WindowKeyListeners.OpenSettingsDialog.hotkey
      addActionListener(WindowKeyListeners.OpenSettingsDialog.action)
    })
    add(JMenu(Localizer.localize("menu_item_help_text")).apply {
      add(JMenuItem(Localizer.localize("menu_item_how_to_use_text")).apply {
        accelerator = WindowKeyListeners.HowToUse.hotkey
        addActionListener(WindowKeyListeners.HowToUse.action)
      })
      add(JMenuItem(Localizer.localize("menu_item_about_qtranslate_text")).apply {
        accelerator = WindowKeyListeners.OpenAboutQTranslateDialog.hotkey
        addActionListener(WindowKeyListeners.OpenAboutQTranslateDialog.action)
      })
      add(JMenuItem(Localizer.localize("menu_item_contact_us_text")).apply {
        accelerator = WindowKeyListeners.ContactUs.hotkey
        addActionListener(WindowKeyListeners.ContactUs.action)
      })
      add(JSeparator())
      add(JCheckBoxMenuItem(Localizer.localize("menu_item_auto_check_for_updates_text")).apply {
        isSelected = Configurations.autoCheckForUpdates
        accelerator = WindowKeyListeners.ToggleAutoCheckForUpdates.hotkey
        addActionListener(WindowKeyListeners.ToggleAutoCheckForUpdates.action)
      })
      add(JMenuItem(Localizer.localize("menu_item_check_for_new_updates_text")).apply {
        accelerator = WindowKeyListeners.CheckForUpdate.hotkey
        addActionListener(WindowKeyListeners.CheckForUpdate.action)
      })
    })
    add(JSeparator())
    add(JMenuItem(Localizer.localize("menu_item_exit_text")).apply { addActionListener { exitProcess(0) } })
  }
}