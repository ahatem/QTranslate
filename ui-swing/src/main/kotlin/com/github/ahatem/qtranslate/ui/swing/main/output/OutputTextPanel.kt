package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.ui.swing.main.widgets.ReadOnlyTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.widgets.ReadOnlyTextPanelState
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import javax.swing.JPanel

class OutputTextPanel(
    iconManager: IconManager,
    onListen: (text: String) -> Unit,
    onTranslateRequest: (text: String) -> Unit
) : JPanel(BorderLayout()), Renderable<OutputTextState> {

    private val textPane = AdvancedTextPane(
        onTextChanged = { /* Read-only */ },
        onListenRequest = onListen,
        onTranslateRequest = onTranslateRequest
    )
    private val actionsPanel = TextActionsPanel(iconManager)
    private val readOnlyPanel = ReadOnlyTextPanel(textPane, actionsPanel)

    init {
        add(readOnlyPanel, BorderLayout.CENTER)
    }

    override fun render(state: OutputTextState) {
        readOnlyPanel.render(
            ReadOnlyTextPanelState(
                text = state.text,
                isVisible = true,
                isLoading = state.isLoading,
                fontConfig = state.fontConfig,
                fallbackFontConfig = state.fallbackFontConfig,
                actionsState = state.actionsState
            )
        )
    }
}