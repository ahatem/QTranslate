package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.api.spellchecker.Correction
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

        val rightPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
            add(actionsPanel, BorderLayout.CENTER)
        }

        add(scrollPane, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)

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

    /**
     * Adds a "Spelling Suggestions" menu to the context menu if the user
     * right-clicked on a word with known corrections.
     */
    private fun customizeContextMenu(menu: JPopupMenu, clickPosition: Point) {
        // Always clean up items from the previous popup.
        spellingMenu?.let { menu.remove(it) }
        spellingMenuSeparator?.let { menu.remove(it) }

        // Convert the mouse coordinates to a character index in the document.
        val clickOffset = textPane.viewToModel(clickPosition)
        val correction = findCorrectionAtOffset(clickOffset)

        if (correction != null && correction.suggestions.isNotEmpty()) {
            spellingMenu = buildSpellingMenu(correction)
            spellingMenuSeparator = JSeparator()

            // Insert the new items at the top of the menu.
            menu.insert(spellingMenu, 0)
            menu.insert(spellingMenuSeparator, 1)
        }
    }

    private fun findCorrectionAtOffset(offset: Int): Correction? {
        return currentState?.corrections?.find {
            offset >= it.startIndex && offset < it.endIndex
        }
    }

    private fun buildSpellingMenu(correction: Correction): JMenu {
        return JMenu("Spelling Suggestions").apply {
            correction.suggestions.forEach { suggestion ->
                add(JMenuItem(suggestion)).addActionListener {
                    onCorrectionApplied(correction.original, suggestion)
                }
            }
        }
    }
}