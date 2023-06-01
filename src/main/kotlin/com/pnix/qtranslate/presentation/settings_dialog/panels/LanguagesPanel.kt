package com.pnix.qtranslate.presentation.settings_dialog.panels

import com.pnix.qtranslate.models.Configuration
import com.pnix.qtranslate.presentation.components.CheckListItem
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

    val languages = QTranslateViewModel.supportedLanguages.map {
      CheckListItem(it).apply { isSelected = true }
    }.toTypedArray()
    val languagesList = JCheckboxList(languages)
    languagesList.minimumSelectedItems = languages.size
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