package com.pnix.qtranslate.presentation.main_frame.panels

import com.pnix.qtranslate.presentation.components.TTextPane
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.utils.copyToClipboard
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

class TranslationBackwardPanel : JPanel() {

  val backwardTranslationTextArea = TTextPane().apply { isEditable = false }

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