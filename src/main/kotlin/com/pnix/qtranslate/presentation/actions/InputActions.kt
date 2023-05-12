package com.pnix.qtranslate.presentation.actions

import com.pnix.qtranslate.presentation.main_frame.QTranslateViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.awt.event.ActionEvent
import java.awt.event.ActionListener

class ClearAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    QTranslateViewModel.setTranslationText("")
    QTranslateViewModel.setInputText("")
  }
}

class SwapAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    val translation = QTranslateViewModel.translation.value

    val sourceLanguage = QTranslateViewModel.inputLanguage.value
    val targetLanguage = QTranslateViewModel.outputLanguage.value

    QTranslateViewModel.setInputText(translation)
    QTranslateViewModel.setInputLanguage(targetLanguage)
    QTranslateViewModel.setOutputLanguage(sourceLanguage)

    GlobalScope.launch { QTranslateViewModel.translateAndWait() }
  }
}

class TranslateAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    GlobalScope.launch { QTranslateViewModel.translateAndWait() }
  }
}

class ListenToInput : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    GlobalScope.launch { QTranslateViewModel.listenToInput() }
  }
}

class ListenToTranslation : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    GlobalScope.launch { QTranslateViewModel.listenToTranslation() }
  }
}

