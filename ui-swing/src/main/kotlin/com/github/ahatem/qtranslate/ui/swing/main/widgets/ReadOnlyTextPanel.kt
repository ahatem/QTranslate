package com.github.ahatem.qtranslate.ui.swing.main.widgets

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane

data class ReadOnlyTextPanelState(
    val text: String,
    val isVisible: Boolean,
    val isLoading: Boolean,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val actionsState: TextActionsState
) : UiState

class ReadOnlyTextPanel(
    private val textPane: AdvancedTextPane,
    private val actionsPanel: TextActionsPanel
) : JPanel(BorderLayout()), Renderable<ReadOnlyTextPanelState> {

    init {
        val scrollPane = JScrollPane(textPane)

        val rightPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
            add(actionsPanel, BorderLayout.CENTER)
        }

        add(scrollPane, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)
    }

    override fun render(state: ReadOnlyTextPanelState) {
        isVisible = state.isVisible
        if (!isVisible) return

        textPane.render(
            text = state.text,
            corrections = emptyList(),
            isEditable = false
        )

        textPane.updateFontsAndRescanDocument(
            newPrimary = state.fontConfig.toFont(),
            newFallback = state.fallbackFontConfig.toFont()
        )

        actionsPanel.render(state.actionsState)
    }
}