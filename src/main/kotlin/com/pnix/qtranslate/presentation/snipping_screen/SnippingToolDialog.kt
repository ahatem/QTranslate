package com.pnix.qtranslate.presentation.snipping_screen

import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.KeyStroke


class SnippingToolDialog(frame: JFrame) : JDialog(frame, "", false) {
  init {
    defaultCloseOperation = DISPOSE_ON_CLOSE
    isUndecorated = true

    rootPane.registerKeyboardAction(
      { dispose() },
      KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
      JComponent.WHEN_IN_FOCUSED_WINDOW
    )

    addWindowFocusListener(object : WindowFocusListener {
      override fun windowGainedFocus(e: WindowEvent?) = Unit
      override fun windowLostFocus(e: WindowEvent?) = dispose()
    })

    contentPane.add(OverlayPanel(frame, this))
    pack()
    setLocationRelativeTo(null)
    isVisible = true
  }
}
