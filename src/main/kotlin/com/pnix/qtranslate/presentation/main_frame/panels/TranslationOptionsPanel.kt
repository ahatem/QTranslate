package com.pnix.qtranslate.presentation.main_frame.panels

import com.pnix.qtranslate.models.Language
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.utils.createButtonWithIcon
import net.miginfocom.swing.MigLayout
import java.awt.event.KeyEvent
import javax.swing.*

class TranslationOptionsPanel : JPanel() {

  private val iconSize = 16
  val clearButton = createButtonWithIcon("app-icons/trash.svg", iconSize, "Clear current translation").apply {
    addActionListener(WindowKeyListeners.ClearCurrentTranslation.action)
  }
  val menuButton = createButtonWithIcon("app-icons/menu.svg", iconSize).apply {
    val menu = createTranslationOptionsMenu()
    addActionListener {
      if (menu.isVisible) menu.isVisible = false
      else menu.show(this, 0, this.height)
    }
  }
  val swapButton = createButtonWithIcon("app-icons/swap.svg", iconSize, "Change translation direction").apply {
    addActionListener(WindowKeyListeners.SwapTranslationDirection.action)
  }
  val translateButton = JButton("Translate").apply {
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

  private fun createComboBox(selectedLanguage: Language): JComboBox<Language> {
    return JComboBox(QTranslateViewModel.supportedLanguages).apply {
      selectedItem = selectedLanguage
      addItemListener { event ->
        when (val comboBox = event.source as JComboBox<*>) {
          inputLangComboBox -> QTranslateViewModel.setInputLanguage(comboBox.selectedItem as Language)
          outputLangComboBox -> QTranslateViewModel.setOutputLanguage(comboBox.selectedItem as Language)
        }
      }
    }
  }
}