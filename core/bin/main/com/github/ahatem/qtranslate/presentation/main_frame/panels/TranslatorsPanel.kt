package com.github.ahatem.qtranslate.presentation.main_frame.panels

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.services.translators.abstraction.TranslatorService
import com.github.ahatem.qtranslate.utils.localizedName
import java.awt.GridLayout
import java.util.*
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JToggleButton
import javax.swing.JToolBar

class TranslatorsPanel : JToolBar() {

  var buttonGroup = ButtonGroup()
  private val translatorsButtons get() = QTranslateViewModel.translators.mapIndexed { index, it -> createToggleButton(it, index) }

  init {
    layout = GridLayout(1, 0, 0, 0)
    isFloatable = false
    isRollover = true
    border = BorderFactory.createEmptyBorder(4, 0, 0, 0)

    updateTranslators()
  }

  private fun createToggleButton(translator: TranslatorService, index: Int) =
    JToggleButton(translator.localizedName).apply {
      icon = FlatSVGIcon(
        "translator-icons/${translator.serviceName.lowercase()}.svg",
        (16 * 1.25).toInt(),
        (16 * 1.25).toInt()
      )
      addActionListener {
        QTranslateViewModel.setSelectedTranslatorIndex(index)
        WindowKeyListeners.Translate.action.actionPerformed(it)
      }
      buttonGroup.add(this)
    }

  fun updateTranslators() {
    removeAll()
    buttonGroup = ButtonGroup()
    translatorsButtons.forEach { add(it) }
    selectIndex(0)
  }

  fun selectIndex(index: Int) {
    val buttons = Collections.list(buttonGroup.elements).toList()
    buttons.withIndex().forEach { (buttonIndex, button) ->
      if (buttonIndex == index && !button.isSelected) button.isSelected = true
    }
  }
}