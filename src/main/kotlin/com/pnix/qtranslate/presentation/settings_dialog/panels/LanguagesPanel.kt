package com.pnix.qtranslate.presentation.settings_dialog.panels

import com.pnix.qtranslate.models.Configuration
import com.pnix.qtranslate.presentation.components.JCheckboxList
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane

class LanguagesPanel(configuration: Configuration) : JPanel() {

  init {
    layout = BorderLayout()

    val languagesList = JCheckboxList(QTranslateViewModel.supportedLanguages)
    languagesList.layoutOrientation = JList.VERTICAL_WRAP
    languagesList.visibleRowCount = -1
    languagesList.visibleColumnCount = 5

    add(JScrollPane(languagesList))
    add(
      JButton("Check/Uncheck all").apply {
        var checkAll = false
        addActionListener { languagesList.checkAll(checkAll.also { checkAll = !checkAll }) }
      },
      BorderLayout.SOUTH
    )


  }
}