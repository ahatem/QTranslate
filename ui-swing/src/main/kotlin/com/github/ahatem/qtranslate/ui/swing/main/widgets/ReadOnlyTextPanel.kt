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
    val actionsState: TextActionsState,
    val isEditable: Boolean = false
) : UiState

/**
 * A read-only result surface: the text in its own scroll pane with the action column beside it.
 *
 * The scroll pane is what makes the text wrap to the width it is given, so the panel must own it.
 * Wrapping the panel in another scroll pane instead leaves the text pane without a viewport of its
 * own, and it then lays itself out at the width of its longest line.
 */
class ReadOnlyTextPanel(
    private val textPane: AdvancedTextPane,
    private val actionsPanel: TextActionsPanel,
) : JPanel(BorderLayout()), Renderable<ReadOnlyTextPanelState> {

    init {
        val actionsWrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
            isOpaque = false
            add(actionsPanel, BorderLayout.CENTER)
        }

        add(JScrollPane(textPane).apply { isFocusable = false }, BorderLayout.CENTER)
        add(actionsWrapper, BorderLayout.LINE_END)
    }

    override fun render(state: ReadOnlyTextPanelState) {
        isVisible = state.isVisible
        if (!isVisible) return

        textPane.render(
            text = state.text,
            corrections = emptyList(),
            isEditable = state.isEditable
        )

        textPane.updateFontsAndRescanDocument(
            newPrimary = state.fontConfig.toFont(),
            newFallback = state.fallbackFontConfig.toFont()
        )

        actionsPanel.render(state.actionsState)
    }
}
