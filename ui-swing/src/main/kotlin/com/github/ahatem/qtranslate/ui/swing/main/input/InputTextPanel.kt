package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.main.widgets.ACTION_GAP
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.*

class InputTextPanel(
    private val iconManager: IconManager,
    private val localizationManager: LocalizationManager,
    private val onTextChanged: (String) -> Unit,
    private val onListen: (String) -> Unit,
    private val onTranslateRequest: (String) -> Unit,
    private val onCorrectionApplied: (correction: Correction, suggestion: String) -> Unit,
    private val onImageDropped: ((BufferedImage) -> Unit)? = null,
    private val onDocumentPasted: ((java.io.File) -> Unit)? = null,
    private val onFindInDictionary: ((String) -> Unit)? = null,
    private val onSearchImages: ((String) -> Unit)? = null,
) : JPanel(BorderLayout()), Renderable<InputTextState> {

    private val textPane = AdvancedTextPane(
        onTextChanged = onTextChanged,
        onListenRequest = onListen,
        onTranslateRequest = onTranslateRequest,
        onImageDropped = onImageDropped,
        onDocumentPasted = onDocumentPasted,
    )
    private val actionsPanel = TextActionsPanel(iconManager)

    /**
     * The input pane's action strip: the column of icon buttons beside the text, plus a row of
     * chips under it. Commands whose own region is hidden are mounted here (see
     * [setStripComponents]), so hiding a row never strands one.
     */
    private val actionsWrapper = JPanel(BorderLayout(0, ACTION_GAP)).apply {
        border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
        isOpaque = false
        add(actionsPanel, BorderLayout.CENTER)
    }

    /** Chips standing in for hidden rows: the language pair and the provider. */
    private val chipRow = JPanel(FlowLayout(FlowLayout.LEADING, 4, 0)).apply {
        border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
        isOpaque = false
        isVisible = false
    }

    private var chips: List<JComponent> = emptyList()
    private var trailingAction: JComponent? = null

    private var spellingMenu: JMenu? = null
    private var spellingMenuSeparator: JSeparator? = null
    private var dictMenuItem: JMenuItem? = null
    private var dictMenuSeparator: JSeparator? = null
    private var imageMenuItem: JMenuItem? = null

    private var currentState: InputTextState? = null

    init {
        val scrollPane = JScrollPane(textPane).apply { isFocusable = false }

        add(scrollPane, BorderLayout.CENTER)
        add(actionsWrapper, BorderLayout.LINE_END)
        add(chipRow, BorderLayout.PAGE_END)

        textPane.hintText      = localizationManager.getString("main_window_editor_context_menu.input_hint")
        textPane.showCharCount = true

        textPane.onBeforeContextMenuPopup = { menu, clickPosition ->
            customizeContextMenu(menu, clickPosition)
        }
        textPane.getContextMenuLabel = { key ->
            localizationManager.getString("main_window_editor_context_menu.$key")
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

    /**
     * Mounts [chips] in the chip row under the text, in order, and [trailing] at the foot of the
     * icon column (the notification button while the status bar and history row are both off).
     * Components mounted by an earlier call and not named now are removed.
     */
    fun setStripComponents(chips: List<JComponent>, trailing: JComponent?) {
        if (chips != this.chips || chips.any { it.parent !== chipRow }) {
            this.chips.forEach { if (it.parent === chipRow) chipRow.remove(it) }
            chips.forEach { chip ->
                chipRow.add(chip)
                chip.applyComponentOrientation(componentOrientation)
            }
            this.chips = chips
            chipRow.isVisible = chips.isNotEmpty()
            chipRow.revalidate()
            chipRow.repaint()
        }
        if (trailing !== trailingAction || (trailing != null && trailing.parent !== actionsWrapper)) {
            trailingAction?.let { if (it.parent === actionsWrapper) actionsWrapper.remove(it) }
            trailingAction = trailing
            trailing?.let {
                actionsWrapper.add(it, BorderLayout.PAGE_END)
                it.applyComponentOrientation(componentOrientation)
            }
            actionsWrapper.revalidate()
            actionsWrapper.repaint()
        }
    }

    /** The containers that make up the action strip, for the window's route checks. */
    internal val stripContainers: List<JComponent> get() = listOf(actionsWrapper, chipRow)

    fun requestFocusOnText() = textPane.requestFocusInWindow()
    fun setTranslateKeyStroke(old: javax.swing.KeyStroke?, new: javax.swing.KeyStroke?) =
        textPane.setTranslateKeyStroke(old, new)

    /** The underlying text component — exposed for the frame-level focus traversal policy. */
    val textPaneComponent: JComponent get() = textPane

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

        dictMenuItem?.let { menu.remove(it) }
        dictMenuSeparator?.let { menu.remove(it) }
        imageMenuItem?.let { menu.remove(it) }
        dictMenuItem = null
        dictMenuSeparator = null
        imageMenuItem = null

        if (onFindInDictionary == null && onSearchImages == null) return

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
            ).apply {
                addActionListener { lookup(word) }
            }.also(menu::add)
        }

        // Sits beside the dictionary entry because it answers the same question about the same
        // word, and this is where someone already looks for it.
        onSearchImages?.let { search ->
            imageMenuItem = JMenuItem(
                localizationManager.getString("main_window_editor_context_menu.search_images")
            ).apply {
                addActionListener { search(word) }
            }.also(menu::add)
        }
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

    private fun findCorrectionAtOffset(offset: Int): Correction? =
        currentState?.corrections?.find { offset >= it.startIndex && offset < it.endIndex }

    private fun buildSpellingMenu(correction: Correction): JMenu {
        return JMenu(localizationManager.getString("main_window_editor_context_menu.spelling_suggestions")).apply {
            correction.suggestions.forEach { suggestion ->
                add(JMenuItem(suggestion)).addActionListener {
                    onCorrectionApplied(correction, suggestion)
                }
            }
        }
    }
}
