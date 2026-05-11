package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.main.widgets.ReadOnlyTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.widgets.ReadOnlyTextPanelState
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.KeyStroke

class OutputTextPanel(
    iconManager: IconManager,
    private val localizationManager: LocalizationManager,
    onListen: (text: String) -> Unit,
    onTranslateRequest: (text: String) -> Unit,
    private val onFindInDictionary: ((String) -> Unit)? = null,
    private val onSetAsInput: ((String) -> Unit)? = null,
    private val onEscapePressed: (() -> Unit)? = null,
) : JPanel(BorderLayout()), Renderable<OutputTextState> {

    private val textPane = AdvancedTextPane(
        onTextChanged = {},
        onListenRequest = onListen,
        onTranslateRequest = onTranslateRequest
    )
    private val actionsPanel = TextActionsPanel(iconManager)
    private val readOnlyPanel = ReadOnlyTextPanel(textPane, actionsPanel)

    private var dictMenuItem: JMenuItem? = null
    private var dictMenuSeparator: JSeparator? = null
    private var setAsInputMenuItem: JMenuItem? = null
    private var setAsInputSeparator: JSeparator? = null

    fun requestFocusOnText() = textPane.requestFocusInWindow()
    fun setTranslateKeyStroke(old: javax.swing.KeyStroke?, new: javax.swing.KeyStroke?) =
        textPane.setTranslateKeyStroke(old, new)

    /** The underlying text component — exposed for the frame-level focus traversal policy. */
    val textPaneComponent: JComponent get() = textPane

    init {
        add(readOnlyPanel, BorderLayout.CENTER)

        textPane.hintText = localizationManager.getString("main_window_editor_context_menu.output_hint")

        onEscapePressed?.let { handler ->
            textPane.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape-to-input")
            textPane.actionMap.put("escape-to-input", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = handler()
            })
        }
        textPane.getContextMenuLabel = { key ->
            localizationManager.getString("main_window_editor_context_menu.$key")
        }
        if (onFindInDictionary != null || onSetAsInput != null) {
            textPane.onBeforeContextMenuPopup = { menu, clickPosition ->
                if (onFindInDictionary != null) addFindInDictionaryItem(menu, clickPosition)
                if (onSetAsInput != null) addSetAsInputItem(menu)
            }
        }
    }

    override fun render(state: OutputTextState) {
        readOnlyPanel.render(
            ReadOnlyTextPanelState(
                text = state.text,
                isVisible = true,
                isLoading = state.isLoading,
                fontConfig = state.fontConfig,
                fallbackFontConfig = state.fallbackFontConfig,
                actionsState = state.actionsState,
                isEditable = state.isEditable
            )
        )
    }

    private fun addFindInDictionaryItem(menu: JPopupMenu, clickPosition: Point) {
        dictMenuItem?.let { menu.remove(it) }
        dictMenuSeparator?.let { menu.remove(it) }
        dictMenuItem = null
        dictMenuSeparator = null

        val clickOffset = textPane.viewToModel(clickPosition)
        val word = (textPane.selectedText?.trim() ?: "")
            .takeIf { it.isNotBlank() && !it.contains(' ') }
            ?: wordAtOffset(clickOffset)
        if (word.isNotEmpty()) {
            val sep = JSeparator()
            val item = JMenuItem(localizationManager.getString("main_window_editor_context_menu.find_in_dictionary")).apply {
                addActionListener { onFindInDictionary?.invoke(word) }
            }
            dictMenuSeparator = sep
            dictMenuItem = item
            menu.add(sep)
            menu.add(item)
        }
    }

    private fun addSetAsInputItem(menu: JPopupMenu) {
        setAsInputMenuItem?.let { menu.remove(it) }
        setAsInputSeparator?.let { menu.remove(it) }
        setAsInputMenuItem = null
        setAsInputSeparator = null

        // Use selected text if available; fall back to entire pane content.
        val text = (textPane.selectedText?.trim()?.takeIf { it.isNotBlank() }
            ?: textPane.text?.trim()).takeIf { !it.isNullOrBlank() } ?: return

        val sep = JSeparator()
        val item = JMenuItem(localizationManager.getString("main_window_editor_context_menu.set_as_input")).apply {
            addActionListener { onSetAsInput?.invoke(text) }
        }
        setAsInputSeparator = sep
        setAsInputMenuItem = item
        menu.add(sep)
        menu.add(item)
    }

    private fun wordAtOffset(offset: Int): String {
        val text = textPane.text ?: return ""
        if (offset < 0 || offset >= text.length) return ""
        var start = offset
        var end = offset
        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
        while (end < text.length && text[end].isLetterOrDigit()) end++
        return text.substring(start, end)
    }
}