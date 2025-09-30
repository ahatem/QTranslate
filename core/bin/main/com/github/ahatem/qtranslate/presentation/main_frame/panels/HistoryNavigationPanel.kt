package com.github.ahatem.qtranslate.presentation.main_frame.panels

import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.TranslationHistory
import com.github.ahatem.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.github.ahatem.qtranslate.presentation.snipping_screen_dialog.SnippingToolDialog
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.utils.createButtonWithIcon
import com.github.ahatem.qtranslate.utils.localizedName
import javax.swing.*


class HistoryNavigationPanel : JPanel() {

  val buttonHistoryBackward =
    createButtonWithIcon(
      "app-icons/arrow-alt-circle-${Localizer.localizeDir("left")}.svg",
      16,
      Localizer.localize("main_panel_button_tooltip_history_backward")
    ).apply {
      isEnabled = TranslationHistory.canUndo()
      addActionListener(WindowKeyListeners.GoBackwardInHistory.action)
    }
  val buttonHistoryForward =
    createButtonWithIcon(
      "app-icons/arrow-alt-circle-${Localizer.localizeDir("right")}.svg",
      16,
      Localizer.localize("main_panel_button_tooltip_history_forward")
    ).apply {
      isEnabled = TranslationHistory.canRedo()
      addActionListener(WindowKeyListeners.GoForwardInHistory.action)
    }

  private val status = JLabel().apply {
    border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
  }


  val buttonImageTranslate = createButtonWithIcon(
    "app-icons/camera.svg",
    16,
    Localizer.localize("main_panel_button_tooltip_image_translate")
  ).apply {
    addActionListener {
      QTranslateViewModel.mainFrame.isVisible = false
      QTranslateViewModel.mainFrame.state = JFrame.ICONIFIED
      SwingUtilities.invokeLater {
        Thread.sleep(200)
        SnippingToolDialog(QTranslateViewModel.mainFrame)
      }
    }
  }

  val buttonFileTranslate = createButtonWithIcon(
    "app-icons/document.svg",
    16,
    Localizer.localize("main_panel_button_tooltip_file_translate")
  ).apply { isEnabled = false }

  init {
    layout = BoxLayout(this, BoxLayout.LINE_AXIS)

    add(buttonHistoryBackward)
    add(buttonHistoryForward)
    add(status)
    add(Box.createHorizontalGlue())
    add(buttonImageTranslate)
    add(buttonFileTranslate)


  }

  fun updateStatus() {
    val translatorName = QTranslateViewModel.currentTranslator.localizedName
    val inputLanguageName = QTranslateViewModel.inputLanguage.value.name
    val outputLanguageName = QTranslateViewModel.outputLanguage.value.name
    status.text = Localizer.localize("main_panel_text_translator_from_to")
      .format(translatorName, inputLanguageName, outputLanguageName)
  }


}