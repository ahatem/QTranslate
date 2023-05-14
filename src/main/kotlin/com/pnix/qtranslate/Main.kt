package com.pnix.qtranslate

import com.formdev.flatlaf.intellijthemes.FlatDarkPurpleIJTheme
import com.pnix.qtranslate.presentation.main_frame.QTranslateFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager


fun main() {
//  FlatDarculaLaf.setup()
//  FlatIntelliJLaf.setup()

  FlatDarkPurpleIJTheme.setup()
  UIManager.put("ScrollBar.showButtons", false)
  SwingUtilities.invokeLater { QTranslateFrame().isVisible = true }
}