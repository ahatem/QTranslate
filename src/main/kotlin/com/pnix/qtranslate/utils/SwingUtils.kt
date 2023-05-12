package com.pnix.qtranslate.utils

import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JPanel

fun JFrame.setPadding(padding: Int) {
  val emptyBorder = BorderFactory.createEmptyBorder(padding, padding, padding, padding)
  val panel = JPanel(BorderLayout())
  panel.border = emptyBorder
  panel.add(contentPane, BorderLayout.CENTER)
  this.contentPane = panel
}
