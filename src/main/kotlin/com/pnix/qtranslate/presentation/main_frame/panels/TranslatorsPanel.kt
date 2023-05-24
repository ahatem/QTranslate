package com.pnix.qtranslate.presentation.main_frame.panels

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JToggleButton
import javax.swing.JToolBar

class TranslatorsPanel : JToolBar() {

  val buttonGroup = ButtonGroup()
  private val translatorsButtons =
    QTranslateViewModel.translators.mapIndexed { index, it -> createToggleButton(it.serviceName, index) }

  init {
    layout = GridLayout(1, 0, 0, 0)
    isFloatable = false
    isRollover = true
    border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
    translatorsButtons.forEach { add(it) }
  }

  private fun createToggleButton(name: String, index: Int) = JToggleButton(name).apply {
    icon = FlatSVGIcon("translator-icons/${name.lowercase()}.svg", (16 * 1.25).toInt(), (16 * 1.25).toInt())
    addActionListener {
      QTranslateViewModel.setSelectedTranslatorIndex(index)
      WindowKeyListeners.Translate.action.actionPerformed(it)
    }
    buttonGroup.add(this)
  }
}