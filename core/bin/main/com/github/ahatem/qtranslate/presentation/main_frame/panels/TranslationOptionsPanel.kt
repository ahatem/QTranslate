package com.github.ahatem.qtranslate.presentation.main_frame.panels

import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.Language
import com.github.ahatem.qtranslate.presentation.components.QtLanguageComboBox
import com.github.ahatem.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.utils.createButtonWithIcon
import net.miginfocom.swing.MigLayout
import java.awt.event.KeyEvent
import javax.swing.*

class TranslationOptionsPanel : JPanel() {

  private val iconSize = 16
  val clearButton = createButtonWithIcon("app-icons/trash.svg", iconSize, Localizer.localize("main_panel_button_tooltip_clear_translation")).apply {
    addActionListener(WindowKeyListeners.ClearCurrentTranslation.action)
  }
  val menuButton = createButtonWithIcon("app-icons/menu.svg", iconSize).apply {
    val menu = createTranslationOptionsMenu()
    addActionListener {
      if (menu.isVisible) menu.isVisible = false
      else menu.show(this, 0, this.height)
    }
  }
  val swapButton = createButtonWithIcon("app-icons/swap.svg", iconSize, Localizer.localize("main_panel_button_tooltip_change_translation_direction")).apply {
    addActionListener(WindowKeyListeners.SwapTranslationDirection.action)
  }
  val translateButton = JButton(Localizer.localize("main_panel_button_text_translate")).apply {
    addActionListener(WindowKeyListeners.Translate.action)
  }

  val inputLangComboBox = createComboBox(QTranslateViewModel.inputLanguage.value)
  val outputLangComboBox = createComboBox(QTranslateViewModel.outputLanguage.value)


  init {
    layout = MigLayout("insets 4 2 4 2", "[]3[grow,fill]3[]3[grow,fill]3[]")
    add(clearButton, "width 35::75")
//      add(menuButton, "width 35::25")
    add(inputLangComboBox, "width 0:0:")
    add(swapButton, "width 35::75")
    add(outputLangComboBox, "width 0:0:")
    add(translateButton)
  }


  private fun createTranslationOptionsMenu(): JPopupMenu {
    return JPopupMenu().apply {
      add(JMenuItem("Reset").apply {
        accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, KeyEvent.SHIFT_DOWN_MASK)
      })
      add(JMenuItem("English to Arabic"))
      add(JMenuItem("Arabic to English"))
      add(JSeparator())
      add(JMenuItem("Edit"))
      add(JSeparator())
      add(JCheckBoxMenuItem("Always detect language"))
    }
  }

  private fun createComboBox(selectedLanguage: Language): QtLanguageComboBox {
    return QtLanguageComboBox().apply {
      this.selectedLanguage = selectedLanguage
      addItemListener { event ->
        when (val comboBox = event.source as QtLanguageComboBox) {
          inputLangComboBox -> QTranslateViewModel.setInputLanguage(comboBox.selectedLanguage)
          outputLangComboBox -> QTranslateViewModel.setOutputLanguage(comboBox.selectedLanguage)
        }
      }
    }
  }
}