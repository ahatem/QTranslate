package com.pnix.qtranslate.presentation.components

import com.pnix.qtranslate.models.Language
import javax.swing.JComboBox

data class LanguageComboBoxItem(val language: Language) {

}

class QtLanguageComboBox: JComboBox<LanguageComboBoxItem>() {

}