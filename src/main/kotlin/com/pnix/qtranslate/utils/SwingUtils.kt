package com.pnix.qtranslate.utils

import java.awt.BorderLayout
import java.awt.GridBagConstraints
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener



fun interface SimpleDocumentListener : DocumentListener {
  fun update(e: DocumentEvent?)

  override fun insertUpdate(e: DocumentEvent?) {
    update(e)
  }

  override fun removeUpdate(e: DocumentEvent?) {
    update(e)
  }

  override fun changedUpdate(e: DocumentEvent?) {
    update(e)
  }
}

fun JFrame.setPadding(padding: Int) {
  val emptyBorder = BorderFactory.createEmptyBorder(padding, padding, padding, padding)
  val panel = JPanel(BorderLayout())
  panel.border = emptyBorder
  panel.add(contentPane, BorderLayout.CENTER)
  this.contentPane = panel
}

fun JDialog.setPadding(padding: Int) {
  val emptyBorder = BorderFactory.createEmptyBorder(padding, padding, padding, padding)
  val panel = JPanel(BorderLayout())
  panel.border = emptyBorder
  panel.add(contentPane, BorderLayout.CENTER)
  this.contentPane = panel
}

fun JPanel.addSeparator(pos: GBHelper, text: String) {
  add(
    JLabel(text).apply {
      isOpaque = true
      border = BorderFactory.createCompoundBorder(
        BorderFactory.createTitledBorder(""),
        BorderFactory.createEmptyBorder(3, 3, 3, 3)
      )
    },
    pos.nextRow().expandW(0.0).width(1).align(GridBagConstraints.WEST)
  )

  add(
    JSeparator(),
    pos.expandW().fill(GridBagConstraints.HORIZONTAL).width(3)
  )
}
