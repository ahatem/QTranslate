package com.pnix.qtranslate.presentation.main_frame.menus

import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.presentation.settings_dialog.SettingsDialog
import com.pnix.qtranslate.presentation.snipping_screen_dialog.SnippingToolDialog
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
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
    add(JMenuItem("Dictionary").apply {
      addActionListener(WindowKeyListeners.OpenDictionaryDialog.action)
    })
    add(JMenuItem("Text recognition").apply {
      addActionListener {
        QTranslateViewModel.mainFrame.isVisible = false
        QTranslateViewModel.mainFrame.state = JFrame.ICONIFIED
        SwingUtilities.invokeLater {
          Thread.sleep(200)
          SnippingToolDialog(QTranslateViewModel.mainFrame)
        }
      }
    })
    add(JMenuItem("History").apply {
      addActionListener(WindowKeyListeners.OpenHistoryDialog.action)
    })
    add(JMenuItem("Settings").apply {
      addActionListener(WindowKeyListeners.OpenSettingsDialog.action)
    })
    add(JMenuItem("About QTranslate"))
    addSeparator()
    add(JCheckBoxMenuItem("Enable global hotkeys").apply { isSelected = true })
//      add(JMenu("Mouse mode"))
    addSeparator()
    add(JMenuItem("Exit").apply { addActionListener { exitProcess(0) } })
  }
}