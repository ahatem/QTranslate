package com.github.ahatem.qtranslate.presentation.main_frame.layouts

import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JSplitPane

class Layout3 : Layout() {

    override fun showBackwardTranslation(mainPanel: MainPanel, show: Boolean) {
        mainPanel.translationBackwardPanel.isVisible = show
        mainPanel.split1.setDividerLocation(0.5)
        mainPanel.split1.dividerSize = if (show) 5 else 0
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
                JSplitPane.VERTICAL_SPLIT,
                mainPanel.translationOutputPanel,
                mainPanel.translationBackwardPanel
            ).apply { resizeWeight = 0.55 }

        mainPanel.split2 =
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mainPanel.translationInputPanel, mainPanel.split1).apply {
                resizeWeight = 0.45
            }

        val bottomPanel = JPanel(BorderLayout()).apply {
            add(mainPanel.translatorsPanel, BorderLayout.NORTH)
            add(mainPanel.statusPanel, BorderLayout.SOUTH)
        }

        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(mainPanel.split2)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)
    }
}