package com.pnix.qtranslate.presentation.main_frame.layouts

import com.pnix.qtranslate.models.Configurations
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JSplitPane

class Layout2 : Layout() {

  override fun showBackwardTranslation(mainPanel: MainPanel, show: Boolean) {
    mainPanel.translationBackwardPanel.isVisible = show
    mainPanel.split2.setDividerLocation(0.5)
    mainPanel.split2.dividerSize = if (show) 5 else 0
  }

  override fun createLayout(mainPanel: MainPanel) {
    mainPanel.layout = BorderLayout()
    mainPanel.border = BorderFactory.createEmptyBorder(2, 0, 0, 0)

    val centerBottomPanel = JPanel(BorderLayout()).apply {
      add(mainPanel.translationOptionsPanel, BorderLayout.NORTH)
      add(mainPanel.translationOutputPanel)
    }

    mainPanel.split1 =
      JSplitPane(JSplitPane.VERTICAL_SPLIT, mainPanel.translationInputPanel, centerBottomPanel).apply {
        resizeWeight = 0.55
      }

    mainPanel.split2 =
      JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        mainPanel.split1,
        mainPanel.translationBackwardPanel
      ).apply { resizeWeight = 0.55 }

    val bottomPanel = JPanel(BorderLayout()).apply {
      add(mainPanel.translatorsPanel, BorderLayout.NORTH)
      add(mainPanel.statusPanel, BorderLayout.SOUTH)
    }

    mainPanel.add(mainPanel.historyNavigationPanel, BorderLayout.NORTH)
    mainPanel.add(mainPanel.split2)
    mainPanel.add(bottomPanel, BorderLayout.SOUTH)

    showBackwardTranslation(mainPanel, Configurations.showBackwardTranslationPanel)

  }


}