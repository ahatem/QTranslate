package com.github.ahatem.qtranslate.presentation.settings_dialog.panels

import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.Configuration
import com.github.ahatem.qtranslate.presentation.components.CheckListItem
import com.github.ahatem.qtranslate.presentation.components.JCheckboxList
import com.github.ahatem.qtranslate.services.translators.abstraction.TranslatorService
import com.github.ahatem.qtranslate.utils.GBHelper
import com.github.ahatem.qtranslate.utils.addSeparator
import com.github.ahatem.qtranslate.utils.localizedName
import com.github.ahatem.qtranslate.utils.supportedTranslators
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane



class ServicesPanel(configuration: Configuration) : JPanel() {

  data class TranslatorCheckboxItem(val translatorService: TranslatorService) {
    override fun toString(): String {
      return translatorService.localizedName
    }
  }

  private val pos = GBHelper().apply { insets = Insets(2, 2, 2, 2) }


  init {
    layout = GridBagLayout()
    addSeparator(pos, Localizer.localize("services_panel_text_translators"))

    val translators = supportedTranslators.map {
      CheckListItem(TranslatorCheckboxItem(it)).apply {
        isSelected = !configuration.excludedTranslators.contains(it.serviceName)
      }
    }.toTypedArray()
    val translatorsList = JCheckboxList(translators)
    translatorsList.addCheckChangeListener {
      val item = it.item as TranslatorCheckboxItem
      if (translatorsList.selectedValue.isSelected) {
        configuration.excludedTranslators.remove(item.translatorService.serviceName)
      } else {
        configuration.excludedTranslators.add(item.translatorService.serviceName)
      }
    }
    translatorsList.minimumSelectedItems = 1
    translatorsList.layoutOrientation = JList.VERTICAL
    add(
      JScrollPane(translatorsList),
      pos.nextRow().expandW().expandH()
    )

    addSeparator(pos, Localizer.localize("services_panel_text_dictionaries"))
    val dictionaries = JCheckboxList(emptyArray<CheckListItem>())
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
