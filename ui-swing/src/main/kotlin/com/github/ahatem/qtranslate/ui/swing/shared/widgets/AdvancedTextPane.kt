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

    private fun computeUnifiedLineMetrics(primary: Font, fallback: Font): Float {
        val frc = FontRenderContext(null, true, true)
        val p = primary.getLineMetrics("A", frc)
        val f = fallback.getLineMetrics("A", frc)
        return maxOf(p.height, f.height)
    }

    class WrappingViewFactory : ViewFactory {
        override fun create(elem: Element): View {
            /*val doc = elem.document
            val host = (doc as? AbstractDocument)?.getProperty("container") as? JTextPane
            val primaryFont = (host as? AdvancedTextPane)?.primaryFont ?: host?.font ?: Font("SansSerif", Font.PLAIN, 14)
            val fallbackFont = (host as? AdvancedTextPane)?.fallbackFont ?: Font("Dialog", Font.PLAIN, 14)*/

            return when (elem.name) {
                AbstractDocument.ContentElementName -> SafeLabelView(elem)
                AbstractDocument.ParagraphElementName -> WrappingParagraphView(elem)
                /*AbstractDocument.ParagraphElementName -> {
                    val lm = computeUnifiedLineMetrics(primaryFont, fallbackFont)
                    FixedLineHeightParagraphView(elem, lm)
                }*/
                AbstractDocument.SectionElementName -> BoxView(elem, View.Y_AXIS)
                StyleConstants.ComponentElementName -> ComponentView(elem)
                StyleConstants.IconElementName -> IconView(elem)
                else -> LabelView(elem)
            }
        }
    }

    private class SafeLabelView(elem: Element) : LabelView(elem) {

        override fun getMinimumSpan(axis: Int): Float {
            return if (axis == X_AXIS) super.getPreferredSpan(axis) / 4 else super.getMinimumSpan(axis)
        }

        override fun getBreakWeight(axis: Int, pos: Float, len: Float): Int {
            return if (axis == X_AXIS) GoodBreakWeight else super.getBreakWeight(axis, pos, len)
        }

        override fun breakView(axis: Int, p0: Int, pos: Float, len: Float): View? {
            if (axis != X_AXIS) return super.breakView(axis, p0, pos, len)

            // let Swing try its normal break logic first
            val standard = super.breakView(axis, p0, pos, len)

            if (standard != null && standard !== this && standard.getPreferredSpan(X_AXIS) <= len) {
                return standard
            }

            // fallback for extremely long unbreakable runs
            checkPainter()
            val p1 = glyphPainter.getBoundedPosition(this, p0, pos, len)

            // never break inside a Unicode cluster
            val safeEnd = findClusterBoundary(p0, p1)
            return if (safeEnd > p0) createFragment(p0, safeEnd) else standard
        }

        private fun findClusterBoundary(start: Int, proposedEnd: Int): Int {
            val text = document.getText(start, proposedEnd - start)
            var end = text.length
            while (end > 0 && Character.isLowSurrogate(text[end - 1])) {
                end--
            }
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

        override fun getMinimumSpan(axis: Int): Float {
            return if (axis == X_AXIS) 0f else super.getMinimumSpan(axis)
        }

        override fun getMaximumSpan(axis: Int): Float {
            return if (axis == X_AXIS) Float.MAX_VALUE else super.getMaximumSpan(axis)
        }
    }

    private class FixedLineHeightParagraphView(
        elem: Element,
        private val lineHeight: Float
    ) : ParagraphView(elem) {

        override fun layout(width: Int, height: Int) {
            super.layout(width, height)
            for (i in 0 until viewCount) {
                val child = getView(i)
                child.setSize(width.toFloat(), lineHeight)
            }
        }

        override fun getPreferredSpan(axis: Int): Float {
            return if (axis == View.Y_AXIS) viewCount * lineHeight else super.getPreferredSpan(axis)
        }
    }

}

class AdvancedCaret(
    private val caretWidth: kotlin.Float = 3f,
    private val blinkRate: Int = 600,
    private val verticalInset: kotlin.Float = 3f
) : DefaultCaret(), ActionListener {

    private val blinkTimer = Timer(blinkRate, this)
    private var isVisibleNow = true

    init {
        blinkTimer.initialDelay = blinkRate
    }

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
        val oldColor = g2.color
        val oldHints = g2.renderingHints

        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)

            g2.stroke = BasicStroke(caretWidth)
            g2.color = comp.caretColor

            val viewRect = comp.ui.modelToView2D(comp, dot, Position.Bias.Forward) ?: return

            val x = viewRect.x.roundToInt()
            val yStart = (viewRect.y + verticalInset).roundToInt()
            val yEnd = (viewRect.y + viewRect.height - verticalInset).roundToInt()

            g2.drawLine(x, yStart, x, yEnd)
        } catch (_: BadLocationException) {
        } finally {
            g2.stroke = oldStroke
            g2.color = oldColor
            g2.setRenderingHints(oldHints)
        }
    }

    override fun damage(r: Rectangle?) {
        r ?: return
        x = r.x
        y = r.y
        width = r.width
        height = r.height
        component?.repaint(x, y, width, height)
    }
}

class FontFallbackDocumentListener(
    private val textPane: AdvancedTextPane,
    private val batchDelayMs: Int = 50
) : DocumentListener {

    @Volatile
    private var applying = false

    private var pendingOffset: Int = 0
    private var pendingLength: Int = 0
    private var pendingTimer: Timer? = null

    private val reusableAttrs = SimpleAttributeSet()

    override fun insertUpdate(e: DocumentEvent) {
        schedule(e.offset, e.length)
    }

    override fun removeUpdate(e: DocumentEvent) {
        // When text removed, rescan surrounding area safely.
        schedule(max(0, e.offset - 1), 1)
    }

    override fun changedUpdate(e: DocumentEvent) {
        // Attribute change; rescan whole doc area conservatively
        schedule(0, textPane.document.length)
    }

    fun rescanEntireDocument() {
        schedule(0, textPane.document.length)
    }

    fun clearCache() {
        // nothing cached here; included for compatibility
    }

    private fun schedule(offset: Int, length: Int) {
        if (applying) return

        // coalesce ranges
        if (pendingLength == 0) {
            pendingOffset = offset
            pendingLength = length
        } else {
            val start = minOf(pendingOffset, offset)
            val end = maxOf(pendingOffset + pendingLength, offset + length)
            pendingOffset = start
            pendingLength = end - start
        }

        pendingTimer?.stop()
        pendingTimer = Timer(batchDelayMs) {
            val o = pendingOffset
            val l = pendingLength
            pendingOffset = 0
            pendingLength = 0
            (it.source as Timer).stop()
            SwingUtilities.invokeLater { applyFontFallbackSafe(o, l) }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun applyFontFallbackSafe(offset: Int, length: Int) {
        if (length <= 0) return
        if (applying) return

        applying = true
        try {
            val doc = textPane.styledDocument
            val docLen = doc.length
            val safeOffset = offset.coerceIn(0, docLen)
            val safeLength = length.coerceIn(0, docLen - safeOffset)
            if (safeLength <= 0) return

            val primary = textPane.primaryFont
            val fallback = textPane.fallbackFont

            applyFontFallback(doc, safeOffset, safeLength, primary, fallback)
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            applying = false
        }
    }

    /**
     * Core algorithm:
     * - Work on substring [offset, offset+length)
     * - Use Font.canDisplayUpTo to find maximal runs primary can render.
     * - When primary cannot render at some index, attempt fallback runs.
     * - Preserve existing attributes; only replace font family and size.
     */
    private fun applyFontFallback(
        doc: StyledDocument,
        offset: Int,
        length: Int,
        primary: Font,
        fallback: Font
    ) {
        if (length <= 0) return

        // Read full substring once
        val text = doc.getText(offset, length)
        var pos = 0
        while (pos < text.length) {
            val remaining = text.substring(pos)

            // Fast path: primary can render entire remaining -> set primary for rest and break
            val primaryFailIndex = primary.canDisplayUpTo(remaining)
            if (primaryFailIndex == -1) {
                applyRunAttributes(doc, offset + pos, remaining.length, primary)
                break
            }

            // primary can render [0, primaryFailIndex)
            if (primaryFailIndex > 0) {
                applyRunAttributes(doc, offset + pos, primaryFailIndex, primary)
                pos += primaryFailIndex
                continue
            }

            // primary cannot render the first char(s) at pos.
            // try fallback for the remaining substring
            val fallbackFailIndex = fallback.canDisplayUpTo(remaining)
            if (fallbackFailIndex == -1) {
                // fallback can render entire remaining; apply fallback and break
                applyRunAttributes(doc, offset + pos, remaining.length, fallback)
                break
            }

            if (fallbackFailIndex > 0) {
                // fallback can render [0, fallbackFailIndex)
                applyRunAttributes(doc, offset + pos, fallbackFailIndex, fallback)
                pos += fallbackFailIndex
                continue
            }

            // neither primary nor fallback can render first char.
            // move forward by one code point to let system fallback handle it.
            val cp = remaining.codePointAt(0)
            val cpLen = Character.charCount(cp)
            pos += cpLen
        }
    }

    /**
     * Apply font family/size to a run while preserving other attributes.
     * Uses a reusable SimpleAttributeSet to avoid allocations.
     */
    private fun applyRunAttributes(doc: StyledDocument, docOffset: Int, runLength: Int, font: Font) {
        if (runLength <= 0) return
        val elem = doc.getCharacterElement(docOffset)
        val existing: AttributeSet = elem.attributes

        reusableAttrs.removeAttributes(reusableAttrs)
        reusableAttrs.addAttributes(existing)

        // Set font family and size from the chosen Font
        StyleConstants.setFontFamily(reusableAttrs, font.family)
        StyleConstants.setFontSize(reusableAttrs, font.size)

        // Apply attributes. Use replace = true to prefer our font attrs while keeping others from existing
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

private fun File.isImageFile(): Boolean {
    val ext = extension.lowercase()
    return ext in setOf("png", "jpg", "jpeg", "bmp", "gif", "tiff", "tif", "webp")
}

class AdvancedTextPane(
    private val onTextChanged: (text: String) -> Unit,
    private val onTranslateRequest: (text: String) -> Unit,
    private val onListenRequest: (text: String) -> Unit,
    private val onImageDropped: ((BufferedImage) -> Unit)? = null,
) : JTextPane() {

    private val undoManager by lazy { UndoManager() }
    private val wavyPainter: Highlighter.HighlightPainter by lazy {
        WavyUnderlineHighlighter.WavyUnderlinePainter(
            UIManager.getColor("Actions.Red")
        )
    }

    var primaryFont: Font = Font("SansSerif", Font.PLAIN, 14)
        private set
    var fallbackFont: Font = Font("Dialog", Font.PLAIN, 14)
        private set

    private val contextMenu: JPopupMenu by lazy { createContextMenu() }
    private val fallbackListener: FontFallbackDocumentListener

    var onBeforeContextMenuPopup: ((menu: JPopupMenu, clickPosition: Point) -> Unit)? = null

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

        highlighter = WavyUnderlineHighlighter()
        editorKit = WrappingEditorKit()
        caret = AdvancedCaret()
        focusTraversalKeysEnabled = false
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


    fun render(text: String, corrections: List<Correction>, isEditable: Boolean) {
        if (this.text == text &&
            lastRenderedCorrections == corrections &&
            this.isEditable == isEditable
        ) {
            return
        }

        SwingUtilities.invokeLater {
            if (this.isEditable != isEditable) {
                this.isEditable = isEditable
            }

            var textWasChanged = false

            if (this.text != text) {
                // Your remove/add listener logic is excellent.
                document.removeDocumentListener(documentListener)
                document.removeDocumentListener(fallbackListener)

                this.text = text
                lastRenderedText = text
                lastEmittedText = text
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
                if (rtl != isTextRtl) {
                    updateOrientation(rtl)
                }
            }
        }
    }

    private fun onUserTextChange() {
        val currentText = text

        if (currentText != lastEmittedText) {
            lastEmittedText = currentText
            onTextChanged(currentText)

            val rtl = currentText.isRTL()
            if (rtl != isTextRtl) {
                SwingUtilities.invokeLater {
                    updateOrientation(rtl)
                }
            }
        }
    }

    fun updateFontsAndRescanDocument(newPrimary: Font, newFallback: Font) {
        if (newPrimary == this.primaryFont && newFallback == this.fallbackFont) {
            return
        }

        SwingUtilities.invokeLater {
            this.primaryFont = newPrimary
            this.fallbackFont = newFallback.alignTo(primaryFont)
            this.font = newPrimary

            fallbackListener.rescanEntireDocument()
        }
    }

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


    private fun updateOrientation(isRtlNow: Boolean) {
        if (isRtlNow == isTextRtl) return

        componentOrientation = if (isRtlNow) {
            ComponentOrientation.RIGHT_TO_LEFT
        } else {
            ComponentOrientation.LEFT_TO_RIGHT
        }
        isTextRtl = isRtlNow

        val doc = this.styledDocument
        val newAlignment = if (isRtlNow) {
            StyleConstants.ALIGN_RIGHT
        } else {
            StyleConstants.ALIGN_LEFT
        }

        val attributes = SimpleAttributeSet()
        StyleConstants.setAlignment(attributes, newAlignment)
        doc.setParagraphAttributes(0, doc.length, attributes, false)

        revalidate()
        repaint()
    }

    private fun setupKeyBindings() {
        val undoAction = createAction("Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)) {
            if (undoManager.canUndo()) undoManager.undo()
        }
        val redoAction = createAction("Redo", KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)) {
            if (undoManager.canRedo()) undoManager.redo()
        }

        actionMap.put("undo", undoAction)
        actionMap.put("redo", redoAction)

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
                if (image != null) {
                    onImageDropped.invoke(toBufferedImage(image))
                } else {
                    paste()
                }
            }
            actionMap.put("paste-image-or-text", pasteAction)
            inputMap.put(pasteAction.getValue(Action.ACCELERATOR_KEY) as KeyStroke, "paste-image-or-text")
        }
    }

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
                            support.transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
                        }.getOrNull()
                        if (image != null) {
                            onImageDropped.invoke(toBufferedImage(image))
                            return true
                        }
                    }
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @Suppress("UNCHECKED_CAST")
                        val files = runCatching {
                            support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                        }.getOrNull()
                        val imageFile = files?.firstOrNull { it.isImageFile() }
                        if (imageFile != null) {
                            val image = runCatching { ImageIO.read(imageFile) }.getOrNull()
                            if (image != null) {
                                onImageDropped.invoke(image)
                                return true
                            }
                        }
                    }
                }
                return original?.importData(support) ?: false
            }
        }
        dropTarget?.isActive = true
    }

    private fun setupMouseListeners() {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = showPopup(e)
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
        val menu = JPopupMenu()
        val undoAction = actionMap["undo"]
        val redoAction = actionMap["redo"]

        val cutItem = JMenuItem("Cut").apply { addActionListener { cut() } }
        val copyItem = JMenuItem("Copy").apply { addActionListener { copy() } }
        val pasteItem = JMenuItem("Paste").apply { addActionListener { paste() } }
        val translateItem = JMenuItem("Translate").apply {
            addActionListener { onTranslateRequest(selectedText ?: text) }
        }
        val listenItem = JMenuItem("Listen").apply {
            addActionListener { onListenRequest(selectedText ?: text) }
        }

        menu.add(JMenuItem(undoAction))
        menu.add(JMenuItem(redoAction))
        menu.addSeparator()
        menu.add(cutItem)
        menu.add(copyItem)
        menu.add(pasteItem)
        menu.addSeparator()
        menu.add(translateItem)
        menu.add(listenItem)

        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                val hasText = text.isNotBlank()
                val hasSelection = selectedText != null

                undoAction.isEnabled = isEditable && undoManager.canUndo()
                redoAction.isEnabled = isEditable && undoManager.canRedo()

                cutItem.isEnabled = isEditable && hasSelection
                copyItem.isEnabled = hasSelection
                pasteItem.isEnabled = isEditable

                translateItem.isEnabled = hasText
                listenItem.isEnabled = hasText
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: PopupMenuEvent?) {}
        })

        return menu
    }

    private fun createAction(name: String, accelerator: KeyStroke, action: (ActionEvent) -> Unit): Action {
        return object : AbstractAction(name) {
            init {
                putValue(ACCELERATOR_KEY, accelerator)
            }

            override fun actionPerformed(e: ActionEvent) {
                action(e)
            }
        }
    }

    override fun setEditable(editable: Boolean) {
        super.setEditable(editable)
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        super.paintComponent(g)
    }

    override fun updateUI() {
        super.updateUI()
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
    }

    override fun getScrollableTracksViewportWidth(): Boolean {
        return parent is JViewport && parent.width > 0
    }

    override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int {
        return font.size * 2
    }
}