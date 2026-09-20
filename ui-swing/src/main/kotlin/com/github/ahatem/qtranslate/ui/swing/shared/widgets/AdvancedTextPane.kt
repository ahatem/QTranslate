package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.CorrectionHighlighter
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.TextPaneDirections
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.TextPaneKeyBindings
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTextPane
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.AttributeSet
import javax.swing.text.BadLocationException
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext
import javax.swing.text.StyledDocument
import javax.swing.undo.UndoManager

/** Shared because it is only ever read; a fresh one per paint was pure garbage. */
private val EMPTY_INSETS = Insets(0, 0, 0, 0)

private fun File.isImageFile(): Boolean =
    extension.lowercase() in setOf("png", "jpg", "jpeg", "bmp", "gif", "tiff", "tif", "webp")

/**
 * The application's text surface: a [JTextPane] that keeps the user's writing and the pane's own
 * presentation strictly apart.
 */
class AdvancedTextPane(
    private val onTextChanged: (text: String) -> Unit,
    private val onTranslateRequest: (text: String) -> Unit,
    private val onListenRequest: (text: String) -> Unit,
    private val onImageDropped: ((BufferedImage) -> Unit)? = null,
    /** Ctrl+V with a document on the clipboard. Null falls back to pasting its path as text. */
    private val onDocumentPasted: ((File) -> Unit)? = null,
) : JTextPane() {

    /** Internal so tests can assert what the undo history does and does not contain. */
    internal val undoManager by lazy { UndoManager() }

    /** Scratch attribute set for font-fallback runs, which are written in batches. */
    private val reusableAttrs = SimpleAttributeSet()

    private val correctionHighlights = CorrectionHighlighter(this)

    private val directions = TextPaneDirections(this)

    private val keyBindings = TextPaneKeyBindings(
        pane = this,
        onTranslateRequest = onTranslateRequest,
        onImageDropped = onImageDropped,
        onDocumentPasted = onDocumentPasted,
    )

    var primaryFont: Font = Font("SansSerif", Font.PLAIN, 14)
        private set
    var fallbackFont: Font = Font("Dialog", Font.PLAIN, 14)
        private set

    /**
     * Grey hint drawn when the pane is empty. Set from the owning panel with a localized string.
     * Triggers a repaint when changed.
     */
    var hintText: String = ""
        set(value) { field = value; repaint() }

    /**
     * When true, a faint character count is drawn in the bottom-right corner (bottom-left for RTL).
     * Useful for the input pane to give users a sense of translation payload size.
     */
    var showCharCount: Boolean = false
        set(value) { field = value; repaint() }

    // Paint-path caches. This component repaints on every caret blink, so anything allocated in
    // paintComponent is allocated roughly twice a second per pane, forever.
    private var cachedCounterFont: Font? = null
    private var cachedCounterBase: Font? = null
    private var cachedCounterValue: Int = -1
    private var cachedCounterText: String = ""
    private var cachedDisabledFg: Color? = null

    private val contextMenu: JPopupMenu by lazy { createContextMenu() }
    private val fallbackListener: FontFallbackDocumentListener

    var onBeforeContextMenuPopup: ((menu: JPopupMenu, clickPosition: Point) -> Unit)? = null
    var getContextMenuLabel: ((key: String) -> String)? = null

    private lateinit var ctxUndoItem:      JMenuItem
    private lateinit var ctxRedoItem:      JMenuItem  // stored so getContextMenuLabel can update it
    private lateinit var ctxCutItem:       JMenuItem
    private lateinit var ctxCopyItem:      JMenuItem
    private lateinit var ctxPasteItem:     JMenuItem
    private lateinit var ctxTranslateItem: JMenuItem
    private lateinit var ctxListenItem:    JMenuItem
    private lateinit var ctxSelectAllItem: JMenuItem
    private lateinit var ctxClearItem:     JMenuItem

    private var lastRenderedText: String? = null
    private var lastRenderedCorrections: List<Correction> = emptyList()
    private var lastEmittedText: String? = null

    private val documentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) { e?.let { onUserTextChange(it.offset, it.length) } }
        override fun removeUpdate(e: DocumentEvent?) { e?.let { onUserTextChange(it.offset, it.length) } }
        override fun changedUpdate(e: DocumentEvent?) = Unit
    }

    init {
        document.putProperty("container", this)

        highlighter  = WavyUnderlineHighlighter()
        editorKit    = WrappingEditorKit()
        caret        = AdvancedCaret()
        applyReadingSpacing()
        // Enable Swing's built-in focus traversal so plain Tab / Shift+Tab are consumed by
        // the KeyboardFocusManager and routed through TextPaneCycleFocusPolicy.
        // By default the JDK also includes Ctrl+Tab / Shift+Ctrl+Tab in the traversal sets,
        // which prevents those keystrokes from reaching the InputMap binding that inserts a
        // literal tab character.  Override both sets to contain only the unmodified Tab strokes.
        focusTraversalKeysEnabled = true
        setFocusTraversalKeys(
            KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
            setOf(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0))
        )
        setFocusTraversalKeys(
            KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
            setOf(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK))
        )
        val padding = UIScale.scale(6)
        margin = Insets(padding, padding, padding, padding)

        document.addUndoableEditListener(undoManager)
        document.addDocumentListener(documentListener)
        fallbackListener = FontFallbackDocumentListener(this)
        document.addDocumentListener(fallbackListener)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                putClientProperty("repaintManager.doubleBufferingEnabled", true)
            }
        })

        lastEmittedText = this.text
        keyBindings.install()
        setupMouseListeners()
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    fun render(text: String, corrections: List<Correction>, isEditable: Boolean) {
        // Use cached lastRenderedText instead of this.text (which serialises the whole document)
        // to avoid allocating a string on every state emission.
        if (lastRenderedText == text &&
            lastRenderedCorrections == corrections &&
            this.isEditable == isEditable
        ) return

        runOnEdt {
            if (this.isEditable != isEditable) this.isEditable = isEditable

            if (lastRenderedText != text) {
                // Only the user-edit callback is detached, so a programmatic render is not echoed
                // back through the state flow as typing. The font-fallback listener stays attached
                // so the new text is scanned for characters the primary font cannot draw.
                document.removeDocumentListener(documentListener)

                this.text = text
                lastRenderedText  = text
                lastEmittedText   = text
                undoManager.discardAllEdits()

                directions.apply()

                document.addDocumentListener(documentListener)
            }

            if (lastRenderedCorrections != corrections) {
                correctionHighlights.update(corrections)
                lastRenderedCorrections = corrections
            }

        }
    }

    private fun onUserTextChange(offset: Int, length: Int) {
        val currentText = text
        if (currentText != lastEmittedText) {
            lastEmittedText  = currentText
            // Kept in step with the typed text so the render that follows through the state flow
            // sees it as unchanged and does not replace the content and reset the caret.
            lastRenderedText = currentText
            onTextChanged(currentText)

            // Deferred: aligning paragraphs writes attributes, which must not happen inside a
            // document listener. Scoped to the edited range, since a typed paragraph can change
            // direction without the document's majority moving.
            SwingUtilities.invokeLater { directions.apply(offset, offset + length) }
        }
    }

    fun updateFontsAndRescanDocument(newPrimary: Font, newFallback: Font) {
        if (newPrimary == primaryFont && newFallback == fallbackFont) return
        SwingUtilities.invokeLater {
            // Stored as requested; the alignment is applied where the font becomes attributes, so
            // the equality check above keeps comparing the callers' fonts.
            primaryFont  = newPrimary
            fallbackFont = newFallback
            font         = newPrimary
            fallbackListener.rescanEntireDocument()
        }
    }

    // -----------------------------------------------------------------------
    // Painting — hint text + character count overlay
    // -----------------------------------------------------------------------

    override fun paintComponent(g: Graphics) {
        // Painted on a copy. Rendering hints set on the Graphics Swing handed us outlive this
        // method and change how sibling components are drawn afterwards; AdvancedCaret already
        // saves and restores for the same reason, and this half of the file did not.
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            super.paintComponent(g2)

            // Use document.length (O(1)) instead of text.length (O(n) serialisation).
            val docLen = document.length
            val hasHint = docLen == 0 && hintText.isNotBlank()
            val hasCounter = showCharCount && docLen > 0
            if (!hasHint && !hasCounter) return

            // Only touched when something is actually drawn — paint runs on every caret blink.
            val insets = margin ?: EMPTY_INSETS
            val disabledFg = cachedDisabledFg
                ?: (UIManager.getColor("Label.disabledForeground") ?: Color.GRAY).also { cachedDisabledFg = it }
            val ltr = componentOrientation.isLeftToRight

            if (hasHint) {
                g2.font = font
                g2.color = disabledFg
                val fm = g2.fontMetrics
                val x = if (ltr) insets.left + 2 else width - insets.right - 2 - fm.stringWidth(hintText)
                val y = insets.top + fm.ascent
                g2.drawString(hintText, x, y)
            }

            if (hasCounter) {
                val counterFont = counterFont()
                val counterStr = counterText(docLen)
                g2.font = counterFont
                g2.color = disabledFg
                val fm = g2.getFontMetrics(counterFont)
                val x = if (ltr) width - insets.right - fm.stringWidth(counterStr) - 2 else insets.left + 2
                val y = height - insets.bottom - 2
                g2.drawString(counterStr, x, y)
            }
        } finally {
            g2.dispose()
        }
    }

    /** Derived once per font rather than on every paint — `deriveFont` is not free. */
    private fun counterFont(): Font {
        val base = font
        if (cachedCounterBase !== base || cachedCounterFont == null) {
            cachedCounterBase = base
            cachedCounterFont = base.deriveFont(base.size2D - 1f)
        }
        return cachedCounterFont!!
    }

    /** The count changes far less often than the pane repaints, so the string is kept. */
    private fun counterText(length: Int): String {
        if (cachedCounterValue != length) {
            cachedCounterValue = length
            cachedCounterText = length.toString()
        }
        return cachedCounterText
    }

    // -----------------------------------------------------------------------
    // Orientation
    // -----------------------------------------------------------------------

    /**
     * Opens the line and paragraph spacing to something readable.
     *
     * Swing's default sets lines directly against one another, which is legible for a form field
     * and tiring for a paragraph — and paragraphs here are the whole point. Applied to the
     * document's default style rather than across the text, so every paragraph inherits it and
     * nothing has to be rewritten when the text changes.
     *
     * Line spacing is a multiple of the line height, so it follows the font size and the zoom
     * without being told. The gap below a paragraph is in points and is scaled.
     */
    private fun applyReadingSpacing() {
        val default = styledDocument.getStyle(StyleContext.DEFAULT_STYLE) ?: return
        StyleConstants.setLineSpacing(default, LINE_SPACING)
        StyleConstants.setSpaceBelow(default, UIScale.scale(PARAGRAPH_GAP))
    }

    // -----------------------------------------------------------------------
    // Key bindings
    // -----------------------------------------------------------------------

    /**
     * Swaps the keyboard shortcut that triggers the translate action.
     * Called by the owning panel whenever the user changes the binding in Settings.
     * [old] is released and [new] registered; either may be null.
     *
     * Returns false, and installs nothing, when [new] is already taken by another action, so a
     * configured shortcut cannot silently disarm Copy, Paste or Undo. The caller can report that
     * rather than leave the user with a shortcut that does nothing.
     */
    fun setTranslateKeyStroke(old: KeyStroke?, new: KeyStroke?): Boolean =
        keyBindings.setTranslateKeyStroke(old, new)

    // -----------------------------------------------------------------------
    // Transfer handler (drag-and-drop images)
    // -----------------------------------------------------------------------

    /**
     * Drops are the window's business, not this pane's.
     *
     * This class used to install its own handler to catch dropped images. Because it accepted
     * every file list, a document dropped here was taken and then discarded — the frame never saw
     * it. The frame now handles both kinds for the whole window, and this pane keeps the stock
     * `JTextPane` handler, which is what makes dragging plain text into the editor work.
     */

    // -----------------------------------------------------------------------
    // Context menu
    // -----------------------------------------------------------------------

    private fun setupMouseListeners() {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // Explicitly claim focus on left-click so Ctrl+C and other keyboard
                // shortcuts work immediately after clicking a read-only output pane,
                // without requiring the user to Tab into it first.
                if (SwingUtilities.isLeftMouseButton(e)) requestFocusInWindow()
                showPopup(e)
            }
            override fun mouseReleased(e: MouseEvent) = showPopup(e)
            private fun showPopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    onBeforeContextMenuPopup?.invoke(contextMenu, e.point)
                    contextMenu.show(e.component, e.x, e.y)
                }
            }
        })
    }

    private fun createContextMenu(): JPopupMenu {
        val menu       = JPopupMenu()
        val undoAction = actionMap["undo"]
        val redoAction = actionMap["redo"]

        ctxUndoItem      = JMenuItem(undoAction)
        ctxRedoItem      = JMenuItem(redoAction)
        ctxCutItem       = JMenuItem("Cut").apply   { addActionListener { cut() } }
        ctxCopyItem      = JMenuItem("Copy").apply  { addActionListener { copy() } }
        ctxPasteItem     = JMenuItem("Paste").apply { addActionListener { paste() } }
        ctxTranslateItem = JMenuItem("Translate").apply {
            addActionListener { onTranslateRequest(selectedText ?: text) }
        }
        ctxListenItem = JMenuItem("Listen").apply {
            addActionListener { onListenRequest(selectedText ?: text) }
        }
        ctxSelectAllItem = JMenuItem("Select All").apply { addActionListener { selectAll() } }
        // Clear replaces the text rather than calling setText, so it goes through the undo
        // manager and can be taken back — losing a paragraph to a menu click with no way back
        // would be the worst thing this menu could do.
        ctxClearItem = JMenuItem("Clear").apply {
            addActionListener {
                if (!isEditable) return@addActionListener
                runCatching { document.remove(0, document.length) }
            }
        }

        menu.add(ctxUndoItem)
        menu.add(ctxRedoItem)
        menu.addSeparator()
        menu.add(ctxCutItem)
        menu.add(ctxCopyItem)
        menu.add(ctxPasteItem)
        menu.addSeparator()
        menu.add(ctxSelectAllItem)
        menu.add(ctxClearItem)
        menu.addSeparator()
        menu.add(ctxTranslateItem)
        menu.add(ctxListenItem)

        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                val hasText      = text.isNotBlank()
                val hasSelection = selectedText != null

                undoAction.isEnabled      = isEditable && undoManager.canUndo()
                redoAction.isEnabled      = isEditable && undoManager.canRedo()
                ctxCutItem.isEnabled      = isEditable && hasSelection
                ctxCopyItem.isEnabled     = hasSelection
                ctxPasteItem.isEnabled    = isEditable
                ctxTranslateItem.isEnabled = hasText
                ctxListenItem.isEnabled   = hasText
                ctxSelectAllItem.isEnabled = hasText
                ctxClearItem.isEnabled    = isEditable && hasText

                getContextMenuLabel?.let { get ->
                    ctxUndoItem.text      = get("undo")
                    ctxRedoItem.text      = get("redo")
                    ctxCutItem.text       = get("cut")
                    ctxCopyItem.text      = get("copy")
                    ctxPasteItem.text     = get("paste")
                    ctxSelectAllItem.text = get("select_all")
                    ctxClearItem.text     = get("clear")
                    ctxTranslateItem.text = get("translate")
                    ctxListenItem.text    = get("listen")
                }
            }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: PopupMenuEvent?) {}
        })

        return menu
    }

    // -----------------------------------------------------------------------
    // Overrides
    // -----------------------------------------------------------------------

    override fun setEditable(editable: Boolean) {
        super.setEditable(editable)
        // Keep the text cursor even when non-editable so the output feels like a text area.
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        // Some LAF/platform combinations drop focusability when isEditable = false; forcing it
        // true keeps keyboard shortcuts (Copy, Select All, …) working in read-only output panes.
        isFocusable = true
        // In read-only mode the system default caretColor can match the pane background in dark
        // themes, making the caret invisible. Always use the foreground color so it is visible
        // regardless of theme.
        caretColor = UIManager.getColor("Label.foreground") ?: Color.WHITE
    }

    override fun updateUI() {
        super.updateUI()
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        // A theme switch invalidates the cached colour and the font derived from the old one.
        // Swing calls this for us then, which is why the caches need no listener of their own.
        cachedCounterFont = null
        cachedCounterBase = null
        cachedDisabledFg = null
    }

    override fun getScrollableTracksViewportWidth(): Boolean =
        parent is JViewport && parent.width > 0

    override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int =
        font.size * 2

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Runs [block] immediately if already on the EDT, otherwise schedules it via invokeLater. */
    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    /**
     * Runs a document write without recording it in the undo history.
     *
     * The undo history belongs to the user's writing. Font fallback and paragraph alignment are
     * derived from the text, so an Undo that reverted one would appear to do nothing and would
     * consume a step meant for the user's own edit. `setCharacterAttributes` and
     * `setParagraphAttributes` both raise ordinary undoable edits, so the listener is detached.
     *
     * Internal because [TextPaneDirections] performs one of those writes.
     */
    internal fun <T> withoutUndo(block: () -> T): T {
        document.removeUndoableEditListener(undoManager)
        return try {
            block()
        } finally {
            document.addUndoableEditListener(undoManager)
        }
    }

    /**
     * Applies font family/size to a run while preserving all other character attributes.
     *
     * Lives here rather than in the fallback listener so the write stays outside the undo history.
     */
    internal fun applyFallbackFontAttributes(docOffset: Int, runLength: Int, font: Font) {
        if (runLength <= 0) return
        val doc = styledDocument
        // LabelView resolves the run font from family/style/size, so apply the ascent adjustment as
        // a size before writing the attributes.
        val aligned = font.metricAlignedTo(primaryFont)

        val existing: AttributeSet = doc.getCharacterElement(docOffset).attributes
        val last: AttributeSet = doc.getCharacterElement(docOffset + runLength - 1).attributes
        // Rewriting attributes that are already in place still raises a document change, and every
        // rescan would do it for the whole document.
        if (existing === last &&
            StyleConstants.getFontFamily(existing) == aligned.family &&
            StyleConstants.getFontSize(existing) == aligned.size
        ) return

        reusableAttrs.removeAttributes(reusableAttrs)
        reusableAttrs.addAttributes(existing)
        StyleConstants.setFontFamily(reusableAttrs, aligned.family)
        StyleConstants.setFontSize(reusableAttrs, aligned.size)
        withoutUndo { doc.setCharacterAttributes(docOffset, runLength, reusableAttrs, true) }
    }

    private companion object {
        /**
         * Extra leading, as a fraction of the line height.
         *
         * Enough to separate lines of Arabic, whose ascenders and descenders reach further than
         * Latin ones and collide at Swing's default of zero.
         */
        const val LINE_SPACING = 0.18f

        /** Gap below a paragraph, before scaling. */
        const val PARAGRAPH_GAP = 6f
    }
}
