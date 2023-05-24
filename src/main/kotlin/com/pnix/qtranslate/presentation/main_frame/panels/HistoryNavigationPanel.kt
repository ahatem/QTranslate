package com.pnix.qtranslate.presentation.main_frame.panels

import com.pnix.qtranslate.models.TranslationHistory
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.utils.createButtonWithIcon
import javax.swing.*


class HistoryNavigationPanel : JPanel() {

  val buttonHistoryBackward =
    createButtonWithIcon("app-icons/arrow-alt-circle-left.svg", 16, "Go backward in history").apply {
      isEnabled = TranslationHistory.canUndo()
      addActionListener(WindowKeyListeners.GoBackwardInHistory.action)
    }
  val buttonHistoryForward =
    createButtonWithIcon("app-icons/arrow-alt-circle-right.svg", 16, "Go forward in history").apply {
      isEnabled = TranslationHistory.canRedo()
      addActionListener(WindowKeyListeners.GoForwardInHistory.action)
    }
  private val buttonHistory = createButtonWithIcon("app-icons/history.svg", 16, "Open history").apply {
    isVisible = false
  }
  private val status = JLabel().apply {
    border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
  }

  init {
    layout = BoxLayout(this, BoxLayout.X_AXIS)

    add(buttonHistoryBackward)
    add(buttonHistoryForward)
    add(status)
    add(Box.createHorizontalGlue())
    add(buttonHistory)
  }

  fun updateStatus() {
    val translatorName = QTranslateViewModel.currentTranslator.serviceName
    val inputLanguageName = QTranslateViewModel.inputLanguage.value.name
    val outputLanguageName = QTranslateViewModel.outputLanguage.value.name
    status.text = "$translatorName \uD83E\uDC72 $inputLanguageName to $outputLanguageName"
  }

}