package com.pnix.qtranslate

import com.formdev.flatlaf.FlatLaf
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.main_frame.QTranslateFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager

/*
* ==========================================
* Make JPopupMenus to its own classes
* support VERTICAL_SPLIT for split panes ... make different layouts -> AbstractLayout.kt .. Layout1.kt etc ...
* ==========================================
* organize folders and try to make more swing component to use again like TextArea for example with zoom (TTextArea.kt)
*
* */

fun main() {
  FlatLaf.setup(Configurations.theme.lookAndFeel)
  UIManager.put("TitlePane.showIcon", false)
  UIManager.put("ScrollBar.showButtons", false)
  SwingUtilities.invokeLater { QTranslateFrame().isVisible = true }
}