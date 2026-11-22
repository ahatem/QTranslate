package com.github.ahatem.qtranslate.ui.swing.main.widgets

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingUtilities
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

    private val scrollPane = JScrollPane(textPane).apply { isFocusable = false }
    private var lastText: String? = null

    init {
        val actionsWrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
            isOpaque = false
            add(actionsPanel, BorderLayout.CENTER)
        }

        add(scrollPane, BorderLayout.CENTER)
        add(actionsWrapper, BorderLayout.LINE_END)
    }

    override fun render(state: ReadOnlyTextPanelState) {
        isVisible = state.isVisible
        if (!isVisible) return

        val isNewResult = lastText != state.text
        lastText = state.text

        textPane.render(
            text = state.text,
            corrections = emptyList(),
            isEditable = state.isEditable
        )

        textPane.updateFontsAndRescanDocument(
            newPrimary = state.fontConfig.toFont(),
            newFallback = state.fallbackFontConfig.toFont()
        )

        // Issue #416: a newly translated result must start at the top, not wherever setText left
        // the caret (the end of the text). Queued after the font pass so re-wrapping cannot scroll
        // back down. Editable panes keep the caret the user placed.
        if (isNewResult && !state.isEditable) {
            SwingUtilities.invokeLater {
                if (textPane.document.length > 0) textPane.caretPosition = 0
                scrollPane.verticalScrollBar.value = 0
            }
        }

        actionsPanel.render(state.actionsState)
    }
}
