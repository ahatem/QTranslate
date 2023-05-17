package com.pnix.qtranslate

import com.formdev.flatlaf.FlatDarculaLaf
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatIntelliJLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.intellijthemes.FlatDarkPurpleIJTheme
import com.formdev.flatlaf.intellijthemes.FlatVuesionIJTheme
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubDarkContrastIJTheme
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubDarkIJTheme
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMoonlightContrastIJTheme
import com.pnix.qtranslate.domain.models.Configurations
import com.pnix.qtranslate.presentation.main_frame.QTranslateFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager


fun main() {
//  FlatDarkPurpleIJTheme.setup()
//  FlatHiberbeeDarkIJTheme.setup()
//  FlatVuesionIJTheme.setup()
//  FlatOneDarkIJTheme.setup()
//  FlatMaterialPalenightContrastIJTheme.setup()

  FlatLaf.setup(Configurations.theme.lookAndFeel)
  UIManager.put("ScrollBar.showButtons", false)
  SwingUtilities.invokeLater { QTranslateFrame().isVisible = true }
}