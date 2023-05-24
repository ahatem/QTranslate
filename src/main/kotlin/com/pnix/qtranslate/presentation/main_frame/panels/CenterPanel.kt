package com.pnix.qtranslate.presentation.main_frame.panels

import com.pnix.qtranslate.main
import com.pnix.qtranslate.models.Configurations
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JSplitPane

class CenterPanel : JPanel() {
  val translationInputPanel = TranslationInputPanel()
  val translationOutputPanel = TranslationOutputPanel()
  val translationBackwardPanel = TranslationBackwardPanel()

  private val inputOutputSplit =
    JSplitPane(JSplitPane.VERTICAL_SPLIT, translationInputPanel, translationOutputPanel).apply { resizeWeight = 0.55 }
  private val mainPanel =
    JSplitPane(JSplitPane.VERTICAL_SPLIT, inputOutputSplit, translationBackwardPanel).apply { resizeWeight = 0.55 }

  init {
    layout = BorderLayout()
    border = BorderFactory.createEmptyBorder(2, 0, 0, 0)

//    showBackwardTranslation(Configurations.showBackwardTranslationPanel)

    add(mainPanel)
  }

  fun showBackwardTranslation(show: Boolean) {
    translationBackwardPanel.isVisible = show
    mainPanel.dividerLocation = mainPanel.lastDividerLocation
    mainPanel.dividerSize = if (show) 5 else 0
  }

  private fun adjustDividerLocation() {
    if (translationBackwardPanel.isVisible) {
      val totalHeight = height
      val currentHeight = mainPanel.dividerLocation + translationBackwardPanel.height
      val newHeight = currentHeight.coerceAtMost((totalHeight * 0.8).toInt())
      mainPanel.dividerLocation = newHeight - translationBackwardPanel.preferredSize.height
    } else {
      mainPanel.dividerLocation = height - translationBackwardPanel.preferredSize.height
    }
  }

}