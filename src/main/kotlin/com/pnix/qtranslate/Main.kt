package com.pnix.qtranslate

import com.formdev.flatlaf.FlatDarculaLaf
import com.pnix.qtranslate.presentation.main_frame.QTranslateFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
  FlatDarculaLaf.setup()
//  FlatIntelliJLaf.setup()
  UIManager.put("ScrollBar.showButtons", false)
  SwingUtilities.invokeLater { QTranslateFrame().isVisible = true }
}