package com.pnix.qtranslate.presentation.actions

import com.pnix.qtranslate.presentation.main_frame.QTranslateViewModel
import java.awt.event.ActionEvent
import java.awt.event.ActionListener

class ClearAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    /*QTranslateViewModel.updateOutputText("")
    QTranslateViewModel.updateInputText("")*/
  }
}

class SwapAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {

   /* val translation = QTranslateViewModel.uiState.value.outputText

    val sourceLanguage = QTranslateViewModel.uiState.value.sourceLanguage
    val targetLanguage = QTranslateViewModel.uiState.value.targetLanguage

    QTranslateViewModel.updateInputText(translation)
    QTranslateViewModel.updateSourceLanguage(targetLanguage)
    QTranslateViewModel.updateTargetLanguage(sourceLanguage)

    QTranslateViewModel.translate()*/
  }
}

class TranslateAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    /*QTranslateViewModel.translate()*/
  }
}

class ListenToInput: ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    /*QTranslateViewModel.listenToInput()*/
  }
}

class ListenToTranslation: ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    /*QTranslateViewModel.listenToTranslation()*/
  }
}

