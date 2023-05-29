package com.pnix.qtranslate.presentation.main_frame.menus

import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.presentation.main_frame.layouts.LayoutFactory
import javax.swing.*
import kotlin.system.exitProcess

private class OnStartupSubMenu : JMenu("On startup") {
  init {
    add(JMenuItem("Show window"))
    add(JMenuItem("Minimize to tray"))
    add(JMenuItem("Restore previous state"))
  }
}

private class LayoutsPresetsSubMenu : JMenu("Layouts presets") {
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

private class OptionsExtendedSubMenu : JMenu("Extended") {

  init {
    add(JMenuItem("Read phonetically").apply { isEnabled = false })
    add(JMenuItem("Clear input on Drag & Drop").apply { isEnabled = false })
    add(JMenuItem("Auto-cleanup of translation").apply { isEnabled = false })
    add(JMenuItem("Save contents on exit ").apply { isEnabled = false })
    addSeparator()
    add(LayoutsPresetsSubMenu())
    addSeparator()
    add(JCheckBoxMenuItem("Show history pane").apply {
      isSelected = Configurations.showHistoryPanel
      accelerator = WindowKeyListeners.ToogleHistoryPane.hotkey
      addActionListener(WindowKeyListeners.ToogleHistoryPane.action)
    })
    add(JCheckBoxMenuItem("Show translation options pane").apply {
      isSelected = Configurations.showTranslationOptionsPanel
      accelerator = WindowKeyListeners.ToggleTranslationOptionsPane.hotkey
      addActionListener(WindowKeyListeners.ToggleTranslationOptionsPane.action)
    })
    add(JCheckBoxMenuItem("Show services pane").apply {
      isSelected = Configurations.showServicesPanel
      accelerator = WindowKeyListeners.ToggleServicesPane.hotkey
      addActionListener(WindowKeyListeners.ToggleServicesPane.action)
    })
    addSeparator()
    add(JCheckBoxMenuItem("Minimize to system tray on minimize").apply { isEnabled = false })
    add(JCheckBoxMenuItem("Minimize to system tray on close").apply { isEnabled = false })
    addSeparator()
    add(OnStartupSubMenu().apply { isEnabled = false })
  }
}

class OptionsPopupMenu : JPopupMenu() {
  init {
    add(JCheckBoxMenuItem("Spell checking").apply {
      isSelected = Configurations.spellChecking
      accelerator = WindowKeyListeners.ToggleSpellChecking.hotkey
      addActionListener(WindowKeyListeners.ToggleSpellChecking.action)
    })
    add(JCheckBoxMenuItem("Instant translation").apply {
      isSelected = Configurations.instantTranslation
      accelerator = WindowKeyListeners.ToggleInstantTranslation.hotkey
      addActionListener(WindowKeyListeners.ToggleInstantTranslation.action)
    })
    add(JCheckBoxMenuItem("Backward translation").apply {
      isSelected = Configurations.showBackwardTranslationPanel
      accelerator = WindowKeyListeners.ToggleBackwardTranslationPane.hotkey
      addActionListener(WindowKeyListeners.ToggleBackwardTranslationPane.action)
    })
    add(OptionsExtendedSubMenu())
    add(JSeparator())
    add(JMenuItem("Dictionary").apply {
      isEnabled = false
      accelerator = WindowKeyListeners.OpenDictionaryDialog.hotkey
      addActionListener(WindowKeyListeners.OpenDictionaryDialog.action)
    })
    add(JMenuItem("History").apply {
      accelerator = WindowKeyListeners.OpenHistoryDialog.hotkey
      addActionListener(WindowKeyListeners.OpenHistoryDialog.action)
    })
    add(JMenuItem("Settings").apply {
      accelerator = WindowKeyListeners.OpenSettingsDialog.hotkey
      addActionListener(WindowKeyListeners.OpenSettingsDialog.action)
    })
    add(JMenu("Help").apply {
      add(JMenuItem("How to use ?").apply {
        accelerator = WindowKeyListeners.HowToUse.hotkey
        addActionListener(WindowKeyListeners.HowToUse.action)
      })
      add(JMenuItem("About QTranslate").apply {
        accelerator = WindowKeyListeners.OpenAboutQTranslateDialog.hotkey
        addActionListener(WindowKeyListeners.OpenAboutQTranslateDialog.action)
      })
      add(JMenuItem("Contact Us").apply {
        accelerator = WindowKeyListeners.ContactUs.hotkey
        addActionListener(WindowKeyListeners.ContactUs.action)
      })
    })
    add(JSeparator())
    add(JMenuItem("Exit").apply { addActionListener { exitProcess(0) } })
  }
}