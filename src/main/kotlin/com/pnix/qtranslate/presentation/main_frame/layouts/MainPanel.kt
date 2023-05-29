package com.pnix.qtranslate.presentation.main_frame.layouts

import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.main_frame.panels.*
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JSplitPane

class MainPanel(private var mainLayout: Layout) : JPanel() {

  val historyNavigationPanel = HistoryNavigationPanel().apply {
    isVisible = Configurations.showHistoryPanel
  }
  val translatorsPanel = TranslatorsPanel().apply {
    isVisible = Configurations.showServicesPanel
  }

  val translationOptionsPanel = TranslationOptionsPanel().apply {
    isVisible = Configurations.showTranslationOptionsPanel
  }
  val translationInputPanel = TranslationInputPanel()
  val translationOutputPanel = TranslationOutputPanel()
  val translationBackwardPanel = TranslationBackwardPanel()


  var split1 =
    JSplitPane(JSplitPane.VERTICAL_SPLIT, translationInputPanel, JPanel(BorderLayout()).apply {
      add(translationOptionsPanel, BorderLayout.NORTH)
      add(translationOutputPanel)
    }).apply { resizeWeight = 0.55 }
  var split2 =
    JSplitPane(JSplitPane.VERTICAL_SPLIT, split1, translationBackwardPanel).apply { resizeWeight = 0.55 }

  init {
    mainLayout.createLayout(this)
  }

  fun showBackwardTranslation(show: Boolean) {
    mainLayout.showBackwardTranslation(this, show)
  }

  fun changeLayout(newLayout: Layout) {
    removeAll()
    mainLayout = newLayout
    mainLayout.createLayout(this)
    revalidate()
    repaint()
  }

}