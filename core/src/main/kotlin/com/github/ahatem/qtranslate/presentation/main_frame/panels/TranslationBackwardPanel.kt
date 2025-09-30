package com.github.ahatem.qtranslate.presentation.main_frame.panels

import com.github.ahatem.qtranslate.presentation.components.QtTextPane
import com.github.ahatem.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.github.ahatem.qtranslate.utils.copyToClipboard
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

class TranslationBackwardPanel : JPanel() {

  val backwardTranslationTextArea = QtTextPane().apply { isEditable = false }

  init {
    layout = BorderLayout()
    val scrollPane = JScrollPane(backwardTranslationTextArea)

    val buttonsPanel = TranslationActionsPanel().apply {
      copyButton.addActionListener { backwardTranslationTextArea.text.copyToClipboard() }
      listenButton.addActionListener(WindowKeyListeners.ListenToBackwardTranslation.action)
    }

    add(buttonsPanel, BorderLayout.LINE_END)
    add(scrollPane, BorderLayout.CENTER)
  }
}