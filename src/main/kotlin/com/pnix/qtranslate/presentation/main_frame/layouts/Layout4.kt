package com.pnix.qtranslate.presentation.main_frame.layouts

import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JSplitPane

class Layout4 : Layout() {

  override fun showBackwardTranslation(mainPanel: MainPanel, show: Boolean) {
    mainPanel.translationBackwardPanel.isVisible = show
    mainPanel.split2.setDividerLocation(0.5)
    mainPanel.split2.dividerSize = if (show) 5 else 0
  }

  override fun createLayout(mainPanel: MainPanel) {
    mainPanel.layout = BorderLayout()
    mainPanel.border = BorderFactory.createEmptyBorder(2, 0, 0, 0)

    val topPanel = JPanel(BorderLayout()).apply {
      add(mainPanel.historyNavigationPanel, BorderLayout.NORTH)
      add(mainPanel.translationOptionsPanel, BorderLayout.SOUTH)
    }

    mainPanel.split1 =
      JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        mainPanel.translationInputPanel,
        mainPanel.translationOutputPanel
      ).apply { resizeWeight = 0.55 }
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

    mainPanel.add(topPanel, BorderLayout.NORTH)
    mainPanel.add(mainPanel.split2)
    mainPanel.add(bottomPanel, BorderLayout.SOUTH)
  }
}