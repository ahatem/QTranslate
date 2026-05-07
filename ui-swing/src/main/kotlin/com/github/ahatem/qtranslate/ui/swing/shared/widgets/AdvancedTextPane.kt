package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.*
import javax.swing.undo.UndoManager
import kotlin.math.max
import kotlin.math.roundToInt


private fun Font.alignTo(base: Font): Font {
    val baseMetrics = FontRenderContext(null, true, true).let { base.getLineMetrics("A", it) }
    val currentMetrics = getLineMetrics("A", FontRenderContext(null, true, true))
    val ratio = baseMetrics.ascent / currentMetrics.ascent
    return this.deriveFont(AffineTransform.getScaleInstance(1.0, ratio.toDouble()))
}

class WrappingEditorKit : StyledEditorKit() {
    private val viewFactory = WrappingViewFactory()

    override fun getViewFactory() = viewFactory

    class WrappingViewFactory : ViewFactory {
        override fun create(elem: Element): View = when (elem.name) {
            AbstractDocument.ContentElementName  -> SafeLabelView(elem)
            AbstractDocument.ParagraphElementName -> WrappingParagraphView(elem)
            AbstractDocument.SectionElementName  -> BoxView(elem, View.Y_AXIS)
            StyleConstants.ComponentElementName  -> ComponentView(elem)
            StyleConstants.IconElementName       -> IconView(elem)
            else                                 -> LabelView(elem)
        }
    }

    private class SafeLabelView(elem: Element) : LabelView(elem) {

        override fun getMinimumSpan(axis: Int): Float =
            if (axis == X_AXIS) super.getPreferredSpan(axis) / 4 else super.getMinimumSpan(axis)

        override fun getBreakWeight(axis: Int, pos: Float, len: Float): Int =
            if (axis == X_AXIS) GoodBreakWeight else super.getBreakWeight(axis, pos, len)

        override fun breakView(axis: Int, p0: Int, pos: Float, len: Float): View? {
            if (axis != X_AXIS) return super.breakView(axis, p0, pos, len)

            val standard = super.breakView(axis, p0, pos, len)
            if (standard != null && standard !== this && standard.getPreferredSpan(X_AXIS) <= len) {
                return standard
            }

            // Fallback for extremely long unbreakable runs — never split a Unicode cluster.
            checkPainter()
            val p1 = glyphPainter.getBoundedPosition(this, p0, pos, len)
            val safeEnd = findClusterBoundary(p0, p1)
            return if (safeEnd > p0) createFragment(p0, safeEnd) else standard
        }

        private fun findClusterBoundary(start: Int, proposedEnd: Int): Int {
            val text = document.getText(start, proposedEnd - start)
            var end = text.length
            while (end > 0 && Character.isLowSurrogate(text[end - 1])) end--
            return start + end
        }
    }

    class WrappingParagraphView(elem: Element) : ParagraphView(elem) {
        override fun layout(width: Int, height: Int) {
            super.layout(width, height)
            for (i in 0 until viewCount) {
                val child = getView(i)
                child.setSize(width.toFloat(), child.getPreferredSpan(Y_AXIS))
            }
        }

        override fun getMinimumSpan(axis: Int): Float =
            if (axis == X_AXIS) 0f else super.getMinimumSpan(axis)

        override fun getMaximumSpan(axis: Int): Float =
            if (axis == X_AXIS) Float.MAX_VALUE else super.getMaximumSpan(axis)
    }
}

class AdvancedCaret(
    private val caretWidth: kotlin.Float = 3f,
    private val blinkRate: Int = 600,
    private val verticalInset: kotlin.Float = 3f
) : DefaultCaret(), ActionListener {

    private val blinkTimer = Timer(blinkRate, this)
    private var isVisibleNow = true

    init { blinkTimer.initialDelay = blinkRate }

    override fun install(c: JTextComponent) {
        super.install(c)
        isVisibleNow = true
        blinkTimer.start()
    }

    override fun deinstall(c: JTextComponent) {
        blinkTimer.stop()
        super.deinstall(c)
    }

    override fun actionPerformed(e: ActionEvent?) {
        isVisibleNow = !isVisibleNow
        component?.repaint()
    }

    override fun paint(g: Graphics?) {
        if (!isVisible || !isVisibleNow) return
        val comp = component ?: return
        val g2 = g as? Graphics2D ?: return

        val oldStroke = g2.stroke
        val oldColor  = g2.color
        val oldHints  = g2.renderingHints

        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
            g2.stroke = BasicStroke(caretWidth)
            g2.color  = comp.caretColor

            val viewRect = comp.ui.modelToView2D(comp, dot, Position.Bias.Forward) ?: return
            val x      = viewRect.x.roundToInt()
            val yStart = (viewRect.y + verticalInset).roundToInt()
            val yEnd   = (viewRect.y + viewRect.height - verticalInset).roundToInt()
            g2.drawLine(x, yStart, x, yEnd)
        } catch (_: BadLocationException) {
        } finally {
            g2.stroke = oldStroke
            g2.color  = oldColor
            g2.setRenderingHints(oldHints)
        }
    }

    override fun damage(r: Rectangle?) {
        r ?: return
        x = r.x; y = r.y; width = r.width; height = r.height
        component?.repaint(x, y, width, height)
    }
}

class FontFallbackDocumentListener(
    private val textPane: AdvancedTextPane,
    private val batchDelayMs: Int = 50
) : DocumentListener {

    @Volatile private var applying = false

    private var pendingOffset = 0
    private var pendingLength = 0
    private var pendingTimer:  Timer? = null
    private val reusableAttrs = SimpleAttributeSet()

    override fun insertUpdate(e: DocumentEvent)  { schedule(e.offset, e.length) }
    override fun removeUpdate(e: DocumentEvent)  { schedule(max(0, e.offset - 1), 1) }
    override fun changedUpdate(e: DocumentEvent) { schedule(0, textPane.document.length) }

    fun rescanEntireDocument() { schedule(0, textPane.document.length) }

    private fun schedule(offset: Int, length: Int) {
        if (applying) return

        if (pendingLength == 0) {
            pendingOffset = offset
            pendingLength = length
        } else {
            val start = minOf(pendingOffset, offset)
            val end   = maxOf(pendingOffset + pendingLength, offset + length)
            pendingOffset = start
            pendingLength = end - start
        }

        pendingTimer?.stop()
        pendingTimer = Timer(batchDelayMs) {
            val o = pendingOffset; val l = pendingLength
            pendingOffset = 0;     pendingLength = 0
            (it.source as Timer).stop()
            SwingUtilities.invokeLater { applyFontFallbackSafe(o, l) }
        }.apply { isRepeats = false; start() }
    }

    private fun applyFontFallbackSafe(offset: Int, length: Int) {
        if (length <= 0 || applying) return
        applying = true
        try {
            val doc       = textPane.styledDocument
            val docLen    = doc.length
            val safeOff   = offset.coerceIn(0, docLen)
            val safeLen   = length.coerceIn(0, docLen - safeOff)
            if (safeLen <= 0) return
            applyFontFallback(doc, safeOff, safeLen, textPane.primaryFont, textPane.fallbackFont)
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            applying = false
        }
    }

    /**
     * Segments [offset, offset+length) into runs that primary can display and
     * runs that need the fallback font, then applies font attributes per-run.
     * Uses [Font.canDisplayUpTo] for O(n) scanning with no per-character allocations.
     */
    private fun applyFontFallback(doc: StyledDocument, offset: Int, length: Int, primary: Font, fallback: Font) {
        if (length <= 0) return
        val text = doc.getText(offset, length)
        var pos  = 0
        while (pos < text.length) {
            val remaining        = text.substring(pos)
            val primaryFailIndex = primary.canDisplayUpTo(remaining)

            if (primaryFailIndex == -1) {
                applyRunAttributes(doc, offset + pos, remaining.length, primary)
                break
            }
            if (primaryFailIndex > 0) {
                applyRunAttributes(doc, offset + pos, primaryFailIndex, primary)
                pos += primaryFailIndex
                continue
            }

            val fallbackFailIndex = fallback.canDisplayUpTo(remaining)
            if (fallbackFailIndex == -1) {
                applyRunAttributes(doc, offset + pos, remaining.length, fallback)
                break
            }
            if (fallbackFailIndex > 0) {
                applyRunAttributes(doc, offset + pos, fallbackFailIndex, fallback)
                pos += fallbackFailIndex
                continue
            }

            // Neither font can display this code point — skip it and let the system handle it.
            val cpLen = Character.charCount(remaining.codePointAt(0))
            pos += cpLen
        }
    }

    /** Applies font family/size to a run while preserving all other character attributes. */
    private fun applyRunAttributes(doc: StyledDocument, docOffset: Int, runLength: Int, font: Font) {
        if (runLength <= 0) return
        val existing: AttributeSet = doc.getCharacterElement(docOffset).attributes
        reusableAttrs.removeAttributes(reusableAttrs)
        reusableAttrs.addAttributes(existing)
        StyleConstants.setFontFamily(reusableAttrs, font.family)
        StyleConstants.setFontSize(reusableAttrs, font.size)
        doc.setCharacterAttributes(docOffset, runLength, reusableAttrs, true)
    }
}

private fun toBufferedImage(image: Image): BufferedImage {
    if (image is BufferedImage) return image
    val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
    val g = buffered.createGraphics()
    g.drawImage(image, 0, 0, null)
    g.dispose()
    return buffered
}

private fun File.isImageFile(): Boolean =
    extension.lowercase() in setOf("png", "jpg", "jpeg", "bmp", "gif", "tiff", "tif", "webp")

class AdvancedTextPane(
    private val onTextChanged: (text: String) -> Unit,
    private val onTranslateRequest: (text: String) -> Unit,
    private val onListenRequest: (text: String) -> Unit,
    private val onImageDropped: ((BufferedImage) -> Unit)? = null,
) : JTextPane() {

    private val undoManager by lazy { UndoManager() }

    // Color supplier so the painter always reads the current theme color — no stale color after theme switch.
    private val wavyPainter: Highlighter.HighlightPainter =
        WavyUnderlineHighlighter.WavyUnderlinePainter { UIManager.getColor("Actions.Red") ?: Color.RED }

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

    private var isTextRtl = false
    private var lastRenderedText: String? = null
    private var lastRenderedCorrections: List<Correction> = emptyList()
    private var lastEmittedText: String? = null

    private val documentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = onUserTextChange()
        override fun removeUpdate(e: DocumentEvent?) = onUserTextChange()
        override fun changedUpdate(e: DocumentEvent?) = Unit
    }

    init {
        document.putProperty("container", this)

        highlighter  = WavyUnderlineHighlighter()
        editorKit    = WrappingEditorKit()
        caret        = AdvancedCaret()
        focusTraversalKeysEnabled = true
        margin = Insets(6, 6, 6, 6)

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
        setupKeyBindings()
        setupMouseListeners()
        setupTransferHandler()
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

            var textWasChanged = false

            if (lastRenderedText != text) {
                document.removeDocumentListener(documentListener)
                document.removeDocumentListener(fallbackListener)

                this.text = text
                lastRenderedText  = text
                lastEmittedText   = text
                undoManager.discardAllEdits()
                textWasChanged = true

                document.addDocumentListener(documentListener)
                document.addDocumentListener(fallbackListener)
            }

            if (lastRenderedCorrections != corrections) {
                updateHighlights(corrections)
                lastRenderedCorrections = corrections
            }

            if (textWasChanged) {
                val rtl = text.isRTL()
                if (rtl != isTextRtl) updateOrientation(rtl)
            }
        }
    }

    private fun onUserTextChange() {
        val currentText = text
        if (currentText != lastEmittedText) {
            lastEmittedText  = currentText
            // Keep lastRenderedText in sync with what the user typed so that the
            // subsequent render() call (which arrives via the state-flow cycle) does
            // NOT replace the document content — that would reset the caret to 0 and
            // cause characters to appear in wrong positions.
            lastRenderedText = currentText
            onTextChanged(currentText)

            val rtl = currentText.isRTL()
            // Use invokeLater — this fires inside a document listener and
            // updateOrientation() calls setParagraphAttributes(), which must
            // not run while the document write-lock is still held.
            if (rtl != isTextRtl) SwingUtilities.invokeLater { updateOrientation(rtl) }
        }
    }

    fun updateFontsAndRescanDocument(newPrimary: Font, newFallback: Font) {
        if (newPrimary == primaryFont && newFallback == fallbackFont) return
        SwingUtilities.invokeLater {
            primaryFont  = newPrimary
            fallbackFont = newFallback.alignTo(primaryFont)
            font         = newPrimary
            fallbackListener.rescanEntireDocument()
        }
    }

    // -----------------------------------------------------------------------
    // Painting — hint text + character count overlay
    // -----------------------------------------------------------------------

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        super.paintComponent(g)

        // Use document.length (O(1)) instead of text.length (O(n) serialisation).
        val docLen     = document.length
        val insets     = margin ?: Insets(0, 0, 0, 0)
        val disabledFg = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        val ltr        = componentOrientation.isLeftToRight

        // Placeholder / hint text — drawn only when the pane is empty.
        if (docLen == 0 && hintText.isNotBlank()) {
            g2.font  = font
            g2.color = disabledFg
            val fm = g2.fontMetrics
            val x  = if (ltr) insets.left + 2 else width - insets.right - 2 - fm.stringWidth(hintText)
            val y  = insets.top + fm.ascent
            g2.drawString(hintText, x, y)
        }

        // Character count — drawn in the bottom corner when enabled and there is text.
        if (showCharCount && docLen > 0) {
            val counterFont = font.deriveFont(font.size2D - 1f)
            val counterStr  = docLen.toString()
            g2.font  = counterFont
            g2.color = disabledFg
            val fm = g2.getFontMetrics(counterFont)
            val x  = if (ltr) width - insets.right - fm.stringWidth(counterStr) - 2 else insets.left + 2
            val y  = height - insets.bottom - 2
            g2.drawString(counterStr, x, y)
        }
    }

    // -----------------------------------------------------------------------
    // Highlights
    // -----------------------------------------------------------------------

    private fun updateHighlights(corrections: List<Correction>) {
        highlighter.removeAllHighlights()
        corrections.forEach { correction ->
            runCatching {
                highlighter.addHighlight(correction.startIndex, correction.endIndex, wavyPainter)
            }.onFailure {
                System.err.println("Failed to add highlight for: $correction. Reason: ${it.message}")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Orientation
    // -----------------------------------------------------------------------

    private fun updateOrientation(isRtlNow: Boolean) {
        if (isRtlNow == isTextRtl) return
        componentOrientation = if (isRtlNow) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
        isTextRtl = isRtlNow

        val attributes = SimpleAttributeSet()
        StyleConstants.setAlignment(attributes, if (isRtlNow) StyleConstants.ALIGN_RIGHT else StyleConstants.ALIGN_LEFT)
        styledDocument.setParagraphAttributes(0, styledDocument.length, attributes, false)
        revalidate()
        repaint()
    }

    // -----------------------------------------------------------------------
    // Key bindings
    // -----------------------------------------------------------------------

    private fun setupKeyBindings() {
        val undoAction = createAction("Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)) {
            if (undoManager.canUndo()) undoManager.undo()
        }
        val redoAction = createAction("Redo", KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)) {
            if (undoManager.canRedo()) undoManager.redo()
        }

        // Translate action — keystroke is set dynamically via setTranslateKeyStroke() so the
        // user-configured binding is always used; selected text is preferred over full pane text.
        val translateAction = object : AbstractAction("Translate") {
            override fun actionPerformed(e: ActionEvent) {
                val textToTranslate = selectedText?.takeIf { it.isNotBlank() } ?: text
                if (textToTranslate.isNotBlank()) onTranslateRequest(textToTranslate)
            }
        }

        actionMap.put("undo", undoAction)
        actionMap.put("redo", redoAction)
        actionMap.put("translate", translateAction)
        inputMap.put(undoAction.getValue(Action.ACCELERATOR_KEY) as KeyStroke, "undo")
        inputMap.put(redoAction.getValue(Action.ACCELERATOR_KEY) as KeyStroke, "redo")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "none")

        if (onImageDropped != null) {
            val pasteAction = createAction(
                "PasteImageOrText",
                KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)
            ) {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val image = runCatching {
                    if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))
                        clipboard.getData(DataFlavor.imageFlavor) as? Image
                    else null
                }.getOrNull()
                if (image != null) onImageDropped.invoke(toBufferedImage(image)) else paste()
            }
            actionMap.put("paste-image-or-text", pasteAction)
            inputMap.put(pasteAction.getValue(Action.ACCELERATOR_KEY) as KeyStroke, "paste-image-or-text")
        }
    }

    /**
     * Swaps the keyboard shortcut that triggers the translate action.
     * Called by the owning panel whenever the user changes the binding in Settings.
     * [old] is removed, [new] is registered — both may be null (no-op for that half).
     */
    fun setTranslateKeyStroke(old: KeyStroke?, new: KeyStroke?) {
        old?.let { inputMap.remove(it) }
        new?.let { inputMap.put(it, "translate") }
    }

    // -----------------------------------------------------------------------
    // Transfer handler (drag-and-drop images)
    // -----------------------------------------------------------------------

    private fun setupTransferHandler() {
        if (onImageDropped == null) return
        val original = transferHandler
        transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                if (isEditable) {
                    if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) return true
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return true
                }
                return original?.canImport(support) ?: false
            }

            override fun importData(support: TransferSupport): Boolean {
                if (isEditable) {
                    if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                        val image = runCatching {
                            support.transferable.getTransferData(DataFlavor.imageFlavor) as? Image
                        }.getOrNull()
                        if (image != null) { onImageDropped.invoke(toBufferedImage(image)); return true }
                    }
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @Suppress("UNCHECKED_CAST")
                        val files = runCatching {
                            support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                        }.getOrNull()
                        val imageFile = files?.firstOrNull { it.isImageFile() }
                        if (imageFile != null) {
                            val image = runCatching { ImageIO.read(imageFile) }.getOrNull()
                            if (image != null) { onImageDropped.invoke(image); return true }
                        }
                    }
                }
                return original?.importData(support) ?: false
            }
        }
        dropTarget?.isActive = true
    }

    // -----------------------------------------------------------------------
    // Context menu
    // -----------------------------------------------------------------------

    private fun setupMouseListeners() {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent)  = showPopup(e)
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

        menu.add(ctxUndoItem)
        menu.add(ctxRedoItem)
        menu.addSeparator()
        menu.add(ctxCutItem)
        menu.add(ctxCopyItem)
        menu.add(ctxPasteItem)
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

                getContextMenuLabel?.let { get ->
                    get("undo")?.let      { ctxUndoItem.text      = it }
                    get("redo")?.let      { ctxRedoItem.text      = it }
                    get("cut")?.let       { ctxCutItem.text       = it }
                    get("copy")?.let      { ctxCopyItem.text      = it }
                    get("paste")?.let     { ctxPasteItem.text     = it }
                    get("translate")?.let { ctxTranslateItem.text = it }
                    get("listen")?.let    { ctxListenItem.text    = it }
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
    }

    override fun updateUI() {
        super.updateUI()
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
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

    private fun createAction(name: String, accelerator: KeyStroke, action: (ActionEvent) -> Unit): Action =
        object : AbstractAction(name) {
            init { putValue(ACCELERATOR_KEY, accelerator) }
            override fun actionPerformed(e: ActionEvent) = action(e)
        }
}
