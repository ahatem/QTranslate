package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Point
import javax.swing.*

class InputTextPanel(
    private val iconManager: IconManager,
    private val localizationManager: LocalizationManager,
    private val onTextChanged: (String) -> Unit,
    private val onListen: (String) -> Unit,
    private val onTranslateRequest: (String) -> Unit,
    private val onCorrectionApplied: (original: String, suggestion: String) -> Unit
) : JPanel(BorderLayout()), Renderable<InputTextState> {

    private val textPane = AdvancedTextPane(
        onTextChanged = onTextChanged,
        onListenRequest = onListen,
        onTranslateRequest = onTranslateRequest
    )
    private val actionsPanel = TextActionsPanel(iconManager)

    private var spellingMenu: JMenu? = null
    private var spellingMenuSeparator: JSeparator? = null

    private var currentState: InputTextState? = null

    init {
        val scrollPane = JScrollPane(textPane)

        val actionsWrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
            isOpaque = false
            add(actionsPanel, BorderLayout.CENTER)
        }

        add(scrollPane, BorderLayout.CENTER)
        add(actionsWrapper, BorderLayout.LINE_END)

        textPane.onBeforeContextMenuPopup = { menu, clickPosition ->
            customizeContextMenu(menu, clickPosition)
        }
    }

    override fun render(state: InputTextState) {
        currentState = state

        textPane.render(
            text = state.text,
            corrections = state.corrections,
            isEditable = state.isEditable
        )
        textPane.updateFontsAndRescanDocument(
            newPrimary = state.fontConfig.toFont(),
            newFallback = state.fallbackFontConfig.toFont()
        )

        actionsPanel.render(state.actionsState)
    }

    fun requestFocusOnText() = textPane.requestFocusInWindow()

    private fun customizeContextMenu(menu: JPopupMenu, clickPosition: Point) {
        spellingMenu?.let { menu.remove(it) }
        spellingMenuSeparator?.let { menu.remove(it) }

        val clickOffset = textPane.viewToModel(clickPosition)
        val correction = findCorrectionAtOffset(clickOffset)

        if (correction != null && correction.suggestions.isNotEmpty()) {
            spellingMenu = buildSpellingMenu(correction)
            spellingMenuSeparator = JSeparator()
            menu.insert(spellingMenu, 0)
            menu.insert(spellingMenuSeparator, 1)
        }
    }

    private fun findCorrectionAtOffset(offset: Int): Correction? =
        currentState?.corrections?.find { offset >= it.startIndex && offset < it.endIndex }

    private fun buildSpellingMenu(correction: Correction): JMenu {
        return JMenu(localizationManager.getString("main_window_editor_context_menu.spelling_suggestions")).apply {
            correction.suggestions.forEach { suggestion ->
                add(JMenuItem(suggestion)).addActionListener {
                    onCorrectionApplied(correction.original, suggestion)
                }
            }
        }
    }
}