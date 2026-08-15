package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContent
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContentClassifier
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
        // Copied once, then scanned in place. The previous version called text.substring(pos)
        // once per run, copying everything still to be scanned each time, which made a run-heavy
        // document quadratic in allocation — while this method's own documentation claimed it
        // allocated nothing per character. canDisplayUpTo takes an offset only for char arrays,
        // which is why this is an array rather than the string.
        val chars = text.toCharArray()
        val end = chars.size
        var pos = 0

        while (pos < end) {
            val primaryFail = primary.canDisplayUpTo(chars, pos, end)

            if (primaryFail == -1) {
                applyRunAttributes(doc, offset + pos, end - pos, primary)
                break
            }
            if (primaryFail > pos) {
                applyRunAttributes(doc, offset + pos, primaryFail - pos, primary)
                pos = primaryFail
                continue
            }

            val fallbackFail = fallback.canDisplayUpTo(chars, pos, end)
            if (fallbackFail == -1) {
                applyRunAttributes(doc, offset + pos, end - pos, fallback)
                break
            }
            if (fallbackFail > pos) {
                applyRunAttributes(doc, offset + pos, fallbackFail - pos, fallback)
                pos = fallbackFail
                continue
            }

            // Neither font can display this code point — skip it and let the system handle it.
            pos += Character.charCount(Character.codePointAt(chars, pos))
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

/** Shared because it is only ever read; a fresh one per paint was pure garbage. */
private val EMPTY_INSETS = Insets(0, 0, 0, 0)

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
    /** Ctrl+V with a document on the clipboard. Null falls back to pasting its path as text. */
    private val onDocumentPasted: ((File) -> Unit)? = null,
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
        applyReadingSpacing()
        // Enable Swing's built-in focus traversal so plain Tab / Shift+Tab are consumed by
        // the KeyboardFocusManager and routed through TextPaneCycleFocusPolicy.
        // By default the JDK also includes Ctrl+Tab / Shift+Ctrl+Tab in the traversal sets,
        // which prevents those keystrokes from reaching the InputMap binding that inserts a
        // literal tab character.  Override both sets to contain only the unmodified Tab strokes.
        focusTraversalKeysEnabled = true
        setFocusTraversalKeys(
            java.awt.KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
            setOf(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0))
        )
        setFocusTraversalKeys(
            java.awt.KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
            setOf(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK))
        )
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

                // Aligned while the listeners are detached. Writing paragraph attributes fires
                // changedUpdate, which the fallback listener answers by rescanning the whole
                // document — so doing this afterwards paid for a full rescan of text that had
                // just been scanned.
                applyParagraphDirections()

                document.addDocumentListener(documentListener)
                document.addDocumentListener(fallbackListener)
            }

            if (lastRenderedCorrections != corrections) {
                updateHighlights(corrections)
                lastRenderedCorrections = corrections
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

            // Deferred because this fires inside a document listener, and aligning paragraphs
            // writes attributes, which must not happen while the document's write lock is held.
            //
            // Unconditional now rather than gated on the pane's overall direction changing: a
            // typed paragraph can switch direction on its own without the document's majority
            // moving, and that case used to go unaligned. The pass itself skips paragraphs that
            // are already correct, so the common keystroke costs a direction test per paragraph
            // and no document write at all.
            SwingUtilities.invokeLater { applyParagraphDirections() }
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

    /**
     * Aligns each paragraph to its own direction, and the component to the document's.
     *
     * Direction used to be one flag for the whole pane, with the alignment written across the
     * entire document. A translation that mixes an Arabic paragraph with an English one then got
     * a single alignment for both, and the wrong one for half of it. Mixed direction *within* a
     * line was always fine — Swing's own Bidi handles that — but paragraphs were not.
     *
     * Writing per paragraph is also cheaper than it sounds. The old call rewrote attributes over
     * every character; this touches each paragraph once, and only the ones whose alignment
     * actually changes, so a document already laid out correctly costs nothing.
     */
    private fun applyParagraphDirections() {
        val root = styledDocument.defaultRootElement
        val rtlAttributes = SimpleAttributeSet().also {
            StyleConstants.setAlignment(it, StyleConstants.ALIGN_RIGHT)
        }
        val ltrAttributes = SimpleAttributeSet().also {
            StyleConstants.setAlignment(it, StyleConstants.ALIGN_LEFT)
        }

        var rtlParagraphs = 0
        for (i in 0 until root.elementCount) {
            val paragraph = root.getElement(i)
            val start = paragraph.startOffset
            val length = (paragraph.endOffset - start).coerceAtMost(styledDocument.length - start)
            if (length <= 0) continue

            val paragraphText = runCatching { styledDocument.getText(start, length) }.getOrNull() ?: continue
            val rtl = paragraphText.isRTL()
            if (rtl) rtlParagraphs++

            val wanted = if (rtl) StyleConstants.ALIGN_RIGHT else StyleConstants.ALIGN_LEFT
            if (StyleConstants.getAlignment(paragraph.attributes) == wanted) continue
            styledDocument.setParagraphAttributes(start, length, if (rtl) rtlAttributes else ltrAttributes, false)
        }

        // The component follows the majority, since it decides which side the scrollbar and the
        // caret's home position sit on, and those belong to the pane rather than to a paragraph.
        val documentIsRtl = rtlParagraphs * 2 > root.elementCount
        if (documentIsRtl != isTextRtl) {
            isTextRtl = documentIsRtl
            componentOrientation =
                if (documentIsRtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
        }

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

        // Tab focus traversal — JTextPane normally inserts a literal tab; override that so
        // keyboard-only users can navigate out of the pane.
        //   Tab         → move focus to the next component in the traversal cycle
        //   Shift+Tab   → move focus to the previous component
        //   Ctrl+Tab    → insert a literal tab character (escape hatch for power users)
        val tabForwardAction = object : AbstractAction("tab-forward") {
            override fun actionPerformed(e: ActionEvent) = transferFocus()
        }
        val tabBackwardAction = object : AbstractAction("tab-backward") {
            override fun actionPerformed(e: ActionEvent) = transferFocusBackward()
        }
        val tabInsertAction = object : AbstractAction("tab-insert") {
            override fun actionPerformed(e: ActionEvent) {
                if (isEditable) replaceSelection("\t")
            }
        }
        actionMap.put("tab-forward",  tabForwardAction)
        actionMap.put("tab-backward", tabBackwardAction)
        actionMap.put("tab-insert",   tabInsertAction)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0),                                       "tab-forward")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK),              "tab-backward")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK),               "tab-insert")

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

        // Explicitly wire standard text shortcuts in the component-level WHEN_FOCUSED InputMap.
        // Although these are already in the LAF's parent InputMap, setting the EditorKit
        // (WrappingEditorKit) triggers a UI reinstall whose InputMap parent chain can be
        // momentarily incomplete on some JVM/LAF combinations, causing the shortcuts to
        // silently fail.  Wiring them here makes the binding deterministic regardless of
        // reinstallation order.
        // CutAction and PasteAction are self-guarding: they no-op when isEditable = false.
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy-to-clipboard")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "select-all")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), "cut-to-clipboard")

        if (onImageDropped != null) {
            // Paste stays on the pane rather than moving to the frame with drops: it targets
            // whatever has focus, so it is genuinely this component's business. It shares the
            // classifier so pasting and dropping agree on what a thing is.
            val pasteAction = createAction(
                "PasteImageOrText",
                KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)
            ) {
                val contents = runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                }.getOrNull()

                when (val content = contents?.let(DroppedContentClassifier::classify)) {
                    is DroppedContent.Picture -> onImageDropped.invoke(content.image)
                    is DroppedContent.Document -> onDocumentPasted?.invoke(content.file) ?: paste()
                    else -> paste()
                }
            }
            actionMap.put("paste-image-or-text", pasteAction)
            inputMap.put(pasteAction.getValue(Action.ACCELERATOR_KEY) as KeyStroke, "paste-image-or-text")
        } else {
            // Output / read-only panes: wire plain text paste so it is always available
            // through the component-level InputMap (PasteAction is a no-op when !isEditable).
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "paste-from-clipboard")
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
                    get("undo")?.let      { ctxUndoItem.text      = it }
                    get("redo")?.let      { ctxRedoItem.text      = it }
                    get("cut")?.let       { ctxCutItem.text       = it }
                    get("copy")?.let      { ctxCopyItem.text      = it }
                    get("paste")?.let     { ctxPasteItem.text     = it }
                    get("select_all")?.let { ctxSelectAllItem.text = it }
                    get("clear")?.let     { ctxClearItem.text     = it }
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
        // Some LAF/platform combinations drop focusability when isEditable = false.
        // Forcing it true ensures keyboard shortcuts (Ctrl+C, Ctrl+A, …) continue to work
        // in read-only output panes.
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

    private fun createAction(name: String, accelerator: KeyStroke, action: (ActionEvent) -> Unit): Action =
        object : AbstractAction(name) {
            init { putValue(ACCELERATOR_KEY, accelerator) }
            override fun actionPerformed(e: ActionEvent) = action(e)
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
