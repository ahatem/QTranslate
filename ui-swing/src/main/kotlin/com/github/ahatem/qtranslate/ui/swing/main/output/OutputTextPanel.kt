package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.main.widgets.ReadOnlyTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.widgets.ReadOnlyTextPanelState
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DefinitionStrip
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.KeyStroke
import javax.swing.UIManager

class OutputTextPanel(
    iconManager: IconManager,
    private val localizationManager: LocalizationManager,
    onListen: (text: String) -> Unit,
    onTranslateRequest: (text: String) -> Unit,
    private val onFindInDictionary: ((String) -> Unit)? = null,
    private val onSearchImages: ((String) -> Unit)? = null,
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
    // No rule of its own: the output pane above already draws a border.
    private val definitionStrip = DefinitionStrip(showDivider = false)

    private val noServiceLabel = JLabel()
    private val noServiceAction = JButton().apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        foreground = UIManager.getColor("Component.linkColor") ?: UIManager.getColor("Component.accentColor")
    }
    private val noServiceBanner = JPanel(FlowLayout(FlowLayout.LEADING, 8, 6)).apply {
        isVisible = false
        isOpaque = true
        add(noServiceLabel)
        add(noServiceAction)
    }

    private var dictMenuItem: JMenuItem? = null
    private var dictMenuSeparator: JSeparator? = null
    private var imageMenuItem: JMenuItem? = null
    private var setAsInputMenuItem: JMenuItem? = null
    private var setAsInputSeparator: JSeparator? = null

    fun requestFocusOnText() = textPane.requestFocusInWindow()
    fun setTranslateKeyStroke(old: javax.swing.KeyStroke?, new: javax.swing.KeyStroke?) =
        textPane.setTranslateKeyStroke(old, new)

    /** The underlying text component — exposed for the frame-level focus traversal policy. */
    val textPaneComponent: JComponent get() = textPane

    init {
        add(noServiceBanner, BorderLayout.NORTH)
        add(readOnlyPanel, BorderLayout.CENTER)
        // An aside beneath the translation. Kept out of readOnlyPanel so it never lands in the
        // clipboard when the translation is copied.
        add(definitionStrip, BorderLayout.SOUTH)

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
        if (onFindInDictionary != null || onSearchImages != null || onSetAsInput != null) {
            textPane.onBeforeContextMenuPopup = { menu, clickPosition ->
                if (onFindInDictionary != null || onSearchImages != null) addFindInDictionaryItem(menu, clickPosition)
                if (onSetAsInput != null) addSetAsInputItem(menu)
            }
        }
    }

    override fun render(state: OutputTextState) {
        renderNoServiceBanner(state.noService)
        definitionStrip.render(state.definition)
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

    /**
     * Shows or hides the "no translator configured" banner.
     *
     * Sits above the output rather than replacing it, so it never fights the layout manager
     * and the pane keeps working the moment a service becomes available. Colours are read
     * from [UIManager] at build time and refreshed on each render so the banner follows theme
     * changes.
     */
    private fun renderNoServiceBanner(state: NoServiceState?) {
        if (state == null) {
            if (noServiceBanner.isVisible) {
                noServiceBanner.isVisible = false
                revalidate()
                repaint()
            }
            return
        }

        noServiceLabel.text = state.message
        noServiceAction.text = state.actionLabel
        noServiceAction.actionListeners.forEach(noServiceAction::removeActionListener)
        noServiceAction.addActionListener { state.onAction() }

        noServiceBanner.background = UIManager.getColor("Component.warningFocusColor")
            ?.let { Color(it.red, it.green, it.blue, 28) }
            ?: UIManager.getColor("Panel.background")
        noServiceLabel.foreground = UIManager.getColor("Label.foreground")

        if (!noServiceBanner.isVisible) {
            noServiceBanner.isVisible = true
            revalidate()
            repaint()
        }
    }

    private fun addFindInDictionaryItem(menu: JPopupMenu, clickPosition: Point) {
        dictMenuItem?.let { menu.remove(it) }
        dictMenuSeparator?.let { menu.remove(it) }
        imageMenuItem?.let { menu.remove(it) }
        dictMenuItem = null
        dictMenuSeparator = null
        imageMenuItem = null

        val clickOffset = textPane.viewToModel(clickPosition)
        val word = (textPane.selectedText?.trim() ?: "")
            .takeIf { it.isNotBlank() && !it.contains(' ') }
            ?: wordAtOffset(clickOffset)
        if (word.isEmpty()) return

        val sep = JSeparator()
        dictMenuSeparator = sep
        menu.add(sep)

        onFindInDictionary?.let { lookup ->
            dictMenuItem = JMenuItem(
                localizationManager.getString("main_window_editor_context_menu.find_in_dictionary")
            ).apply { addActionListener { lookup(word) } }.also(menu::add)
        }

        onSearchImages?.let { search ->
            imageMenuItem = JMenuItem(
                localizationManager.getString("main_window_editor_context_menu.search_images")
            ).apply { addActionListener { search(word) } }.also(menu::add)
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