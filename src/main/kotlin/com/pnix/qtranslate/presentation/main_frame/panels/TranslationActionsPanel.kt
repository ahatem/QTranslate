package com.pnix.qtranslate.presentation.main_frame.panels

import com.pnix.qtranslate.utils.createButtonWithIcon
import javax.swing.BoxLayout
import javax.swing.JPanel

class TranslationActionsPanel : JPanel() {

  val favouriteButton =
    createButtonWithIcon("app-icons/star.svg", 16, "Toggle favourite (Not supported yet.)").apply {
      isEnabled = false
    }

  val speechButton =
    createButtonWithIcon("app-icons/microphone.svg", 16, "Listen to speech (Not supported yet.)").apply {
      isVisible = false
      isEnabled = false
    }

  val copyButton = createButtonWithIcon("app-icons/copy-alt.svg", 16, "Copy translation")
  val listenButton = createButtonWithIcon("app-icons/headphones.svg", 16, "Listen to translation")

  init {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)

    add(favouriteButton)
    add(speechButton)
    add(copyButton)
    add(listenButton)
  }
}