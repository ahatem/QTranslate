package com.pnix.qtranslate.presentation.main_frame.panels

import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.utils.copyToClipboard
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane

class TranslationBackwardPanel : JPanel() {

  private var fontSize = Configurations.inputsFontSize.toFloat()

  val backwardTranslationTextArea = object : JTextPane() {
    override fun getScrollableTracksViewportWidth(): Boolean {
      return true
    }
  }.apply {
    font = Font(Configurations.inputsFontName, Font.PLAIN, Configurations.inputsFontSize)
    isEditable = false

    addMouseWheelListener { e ->
      if (e.isControlDown) {
        if (e.wheelRotation < 0 && fontSize < 72f) {
          fontSize += 1f
        } else if (e.wheelRotation > 0 && fontSize > 8f) {
          fontSize -= 1f
        }
        font = font.deriveFont(fontSize)
      } else {
        parent.dispatchEvent(e)
      }
    }
  }

  init {
    layout = BorderLayout()
    val scrollPane = JScrollPane(backwardTranslationTextArea)


    val buttonsPanel = TranslationActionsPanel().apply {
      copyButton.addActionListener { backwardTranslationTextArea.text.copyToClipboard() }
      listenButton.addActionListener(WindowKeyListeners.ListenToBackwardTranslation.action)
    }

    add(buttonsPanel, BorderLayout.EAST)
    add(scrollPane, BorderLayout.CENTER)
  }


}