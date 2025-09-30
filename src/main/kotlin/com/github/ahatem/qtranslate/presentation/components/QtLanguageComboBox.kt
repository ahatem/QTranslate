package com.github.ahatem.qtranslate.presentation.components

import com.github.ahatem.qtranslate.models.Language
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.presentation.viewmodels.getAutoDetectLanguage
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox

data class LanguageComboBoxItem(val language: Language) {
  var name = language.name
  override fun toString() = name
}

class QtLanguageComboBox : JComboBox<LanguageComboBoxItem>() {

  private val languagesModel = DefaultComboBoxModel(
    arrayOf(
      LanguageComboBoxItem(Language.getAutoDetectLanguage()),
      *QTranslateViewModel.supportedLanguages.map { LanguageComboBoxItem(it) }.toTypedArray()
    )
  )

  var selectedLanguage: Language = Language.getAutoDetectLanguage()
    get() = (selectedItem as LanguageComboBoxItem).language
    set(value) {
      if (value.alpha3 == "auto") {
        field = model.getElementAt(0).apply { name = value.name }.language
      } else {
        field = value
      }
      selectedItem = LanguageComboBoxItem(field)
    }

  init {
    model = languagesModel
    selectedLanguage = languagesModel.getElementAt(0).language
    addActionListener { if (selectedLanguage.id != "auto") model.getElementAt(0).apply { name = "Auto detect" } }
  }

  val allItems get() = Array(model.size) { it }.map { model.getElementAt(it) }

}