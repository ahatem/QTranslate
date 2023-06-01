package com.pnix.qtranslate.presentation.main_frame.panels

import com.pnix.qtranslate.presentation.components.TTextPane
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.utils.copyToClipboard
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

class TranslationOutputPanel : JPanel(BorderLayout()) {

  val outputTextArea = TTextPane().apply { isEditable = false }

  init {
    layout = BorderLayout()
    val scrollPane = JScrollPane(outputTextArea)

    val buttonsPanel = TranslationActionsPanel().apply {
      copyButton.addActionListener { outputTextArea.text.copyToClipboard() }
      listenButton.addActionListener(WindowKeyListeners.ListenToTranslation.action)
    }

    val overlayPanel = JPanel(BorderLayout()).apply {
      add(buttonsPanel, BorderLayout.LINE_END)
      add(scrollPane, BorderLayout.CENTER)
    }

    add(overlayPanel, BorderLayout.CENTER)
  }

}