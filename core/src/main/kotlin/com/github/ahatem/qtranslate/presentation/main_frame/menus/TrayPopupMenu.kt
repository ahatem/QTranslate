package com.github.ahatem.qtranslate.presentation.main_frame.menus

import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.Configurations
import com.github.ahatem.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.github.ahatem.qtranslate.presentation.snipping_screen_dialog.SnippingToolDialog
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import java.awt.Font
import javax.swing.*
import kotlin.system.exitProcess

class TrayPopupMenu : JPopupMenu() {
  init {
    add(JMenuItem("QTranslate").apply {
      font = font.deriveFont(Font.BOLD)
      addActionListener {
        QTranslateViewModel.mainFrame.isVisible = true
        QTranslateViewModel.mainFrame.state = JFrame.NORMAL
      }
    })
    add(JMenuItem(Localizer.localize("menu_item_dictionary_text")).apply {
      isEnabled = false
      addActionListener(WindowKeyListeners.OpenDictionaryDialog.action)
    })
    add(JMenuItem(Localizer.localize("menu_item_text_recognition_text")).apply {
      addActionListener {
        QTranslateViewModel.mainFrame.isVisible = false
        QTranslateViewModel.mainFrame.state = JFrame.ICONIFIED
        SwingUtilities.invokeLater {
          Thread.sleep(200)
          SnippingToolDialog(QTranslateViewModel.mainFrame)
        }
      }
    })
    add(JMenuItem(Localizer.localize("menu_item_history_text")).apply {
      addActionListener(WindowKeyListeners.OpenHistoryDialog.action)
    })
    add(JMenuItem(Localizer.localize("menu_item_settings_text")).apply {
      addActionListener(WindowKeyListeners.OpenSettingsDialog.action)
    })
    addSeparator()
    add(JCheckBoxMenuItem(Localizer.localize("menu_item_enable_global_hotkeys_text")).apply {
      isSelected = Configurations.enableGlobalHotkeys
      accelerator = WindowKeyListeners.ToggleGlobalHotkeys.hotkey
      addActionListener(WindowKeyListeners.ToggleGlobalHotkeys.action)
    })
    addSeparator()
    add(JMenuItem(Localizer.localize("menu_item_exit_text")).apply { addActionListener { exitProcess(0) } })
  }
}