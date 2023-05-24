package com.pnix.qtranslate.presentation.settings_dialog.panels

import com.pnix.qtranslate.models.Configuration
import com.pnix.qtranslate.presentation.components.JCheckboxList
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.utils.GBHelper
import com.pnix.qtranslate.utils.addSeparator
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane


class ServicesPanel(configuration: Configuration) : JPanel() {

  private val pos = GBHelper().apply { insets = Insets(2, 2, 2, 2) }

  init {
    layout = GridBagLayout()
    addSeparator(pos, "Translators")

    val translators = JCheckboxList(QTranslateViewModel.translators.map { it.serviceName }.toTypedArray())
    translators.layoutOrientation = JList.VERTICAL
    add(
      JScrollPane(translators),
      pos.nextRow().expandW().expandH()
    )

    addSeparator(pos, "Dictionaries")
    val dictionaries = JCheckboxList(emptyArray<String>())
    dictionaries.layoutOrientation = JList.VERTICAL
    add(
      JScrollPane(dictionaries),
      pos.nextRow().expandW().expandH()
    )

    /*val buttonsPanel = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.X_AXIS)
      add(JButton("Add dictionary"))
      add(JButton("Remove dictionary"))
    }
    add(buttonsPanel, pos.nextRow())*/

  }
}
