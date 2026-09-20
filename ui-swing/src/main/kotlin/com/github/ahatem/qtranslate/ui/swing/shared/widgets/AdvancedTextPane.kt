package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContent
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContentClassifier
import java.awt.*
import java.awt.event.*
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.io.File
import java.text.BreakIterator
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.*
import javax.swing.undo.UndoManager
import kotlin.math.max
import kotlin.math.roundToInt


/**
 * The same font at the point size whose ascent matches [base]'s.
 *
 * Baseline alignment cannot be carried as a vertical `AffineTransform`: `LabelView` resolves a
 * run's font through `StyleContext.getFont`, which reads only family, style and size. Size is the
 * one metric the rendering path honours, so the adjustment is applied as a point size.
 */
private fun Font.metricAlignedTo(base: Font): Font {
    if (this === base) return this
    val renderContext = FontRenderContext(null, true, true)
    val baseAscent = base.getLineMetrics("A", renderContext).ascent
    val currentAscent = getLineMetrics("A", renderContext).ascent
    if (baseAscent <= 0f || currentAscent <= 0f) return this
    val alignedSize = (size2D * (baseAscent / currentAscent)).roundToInt().coerceAtLeast(1)
    return if (alignedSize == size) this else deriveFont(alignedSize.toFloat())
}

/**
 * Finds grapheme-cluster boundaries for line breaking.
 *
 * A line must not break inside a cluster: separating a combining mark from its base, or cutting a
 * zero-width-joiner emoji sequence or a regional-indicator flag in half, renders as two mangled
 * glyphs. [BreakIterator] applies the Unicode segmentation rules.
 */
internal class GraphemeBoundary {

    private val iterator: BreakIterator = BreakIterator.getCharacterInstance()

    /**
     * The largest cluster boundary in [text] at or below [proposedEnd].
     *
     * [text] must extend past [proposedEnd], otherwise its end is itself a boundary and nothing
     * would be trimmed.
     */
    fun lastAtOrBelow(text: String, proposedEnd: Int): Int {
        if (proposedEnd <= 0) return 0
        iterator.setText(text)
        var boundary = 0
        var next = iterator.first()
        while (next != BreakIterator.DONE && next <= proposedEnd) {
            boundary = next
            next = iterator.next()
        }
        return boundary
    }

    companion object {
        // Bounded lookahead used to inspect continuation beyond the candidate break.
        const val LOOKAHEAD = 64
    }
}

/**
 * The platform's menu shortcut modifier: Command on macOS, Ctrl elsewhere.
 *
 * `Toolkit.getMenuShortcutKeyMaskEx` is headful-only: `HeadlessToolkit` throws `HeadlessException`,
 * so asking it during construction would make the pane impossible to build in a headless JVM. With
 * no headful toolkit the modifier is derived from the platform instead, which is the value the
 * toolkit reports on a desktop.
 */
internal object MenuShortcutModifier {

    fun current(): Int = resolve(
        headless = GraphicsEnvironment.isHeadless(),
        osName = System.getProperty("os.name"),
        toolkitMask = { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx },
    )

    /** [toolkitMask] is consulted only when a headful toolkit is available. */
    fun resolve(headless: Boolean, osName: String?, toolkitMask: () -> Int): Int =
        if (headless) fallback(osName) else toolkitMask()

    fun fallback(osName: String?): Int =
        if (osName.orEmpty().startsWith("Mac", ignoreCase = true)) InputEvent.META_DOWN_MASK
        else InputEvent.CTRL_DOWN_MASK
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

        private val graphemeBoundary = GraphemeBoundary()

        override fun getMinimumSpan(axis: Int): Float =
            if (axis == X_AXIS) super.getPreferredSpan(axis) / 4 else super.getMinimumSpan(axis)

        override fun getBreakWeight(axis: Int, pos: Float, len: Float): Int =
            if (axis == X_AXIS) GoodBreakWeight else super.getBreakWeight(axis, pos, len)

        /**
         * The painter's choice can land on a code unit in the middle of a grapheme cluster, so the
         * end it returns is moved back to a cluster boundary before it is used.
         */
        override fun breakView(axis: Int, p0: Int, pos: Float, len: Float): View? {
            if (axis != X_AXIS) return super.breakView(axis, p0, pos, len)

            val standard = super.breakView(axis, p0, pos, len) ?: return null

            if (standard === this) return this

            val end = standard.endOffset
            val safeEnd = safeBreakEnd(p0, end)
            if (safeEnd == end) return standard
            return if (safeEnd > p0) createFragment(p0, safeEnd) else null
        }

        /** [proposedEnd] moved back to the nearest grapheme-cluster boundary at or below it. */
        private fun safeBreakEnd(start: Int, proposedEnd: Int): Int {
            if (proposedEnd <= start) return start
            val doc = document
            // Look past the candidate end so a cluster straddling it is recognised and dropped
            // rather than split at the fragment edge.
            val lookaheadEnd = (proposedEnd + GraphemeBoundary.LOOKAHEAD).coerceAtMost(doc.length)
            val text = runCatching { doc.getText(start, lookaheadEnd - start) }.getOrNull()
                ?: return proposedEnd
            return start + graphemeBoundary.lastAtOrBelow(text, proposedEnd - start)
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

/**
 * A wider, vertically inset caret for comfortable reading.
 *
 * Only the appearance is custom. Blinking is left to [DefaultCaret], which starts and stops its
 * flasher with focus and editability; a second timer would run regardless of focus and compete
 * with it for visibility.
 *
 * Dimensions are in logical pixels and scaled for the display.
 */
class AdvancedCaret(
    private val caretWidth: kotlin.Float = 3f,
    blinkRate: Int = 600,
    private val verticalInset: kotlin.Float = 3f
) : DefaultCaret() {

    init { setBlinkRate(blinkRate) }

    override fun paint(g: Graphics?) {
        if (!isVisible) return
        val comp = component ?: return
        val g2 = g as? Graphics2D ?: return

        val oldStroke = g2.stroke
        val oldColor  = g2.color
        val oldHints  = g2.renderingHints

        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
            g2.stroke = BasicStroke(UIScale.scale(caretWidth))
            g2.color  = comp.caretColor

            val inset = UIScale.scale(verticalInset)
            val viewRect = comp.ui.modelToView2D(comp, dot, Position.Bias.Forward) ?: return
            val x      = viewRect.x.roundToInt()
            val yStart = (viewRect.y + inset).roundToInt()
            val yEnd   = (viewRect.y + viewRect.height - inset).roundToInt()
            g2.drawLine(x, yStart, x, yEnd)
        } catch (_: BadLocationException) {
        } finally {
            g2.stroke = oldStroke
            g2.color  = oldColor
            g2.setRenderingHints(oldHints)
        }
    }
}

class FontFallbackDocumentListener(
    private val textPane: AdvancedTextPane,
    private val batchDelayMs: Int = 50
) : DocumentListener {

    private var applying = false

    private var pendingOffset = 0
    private var pendingLength = 0
    private var pendingTimer:  Timer? = null

    override fun insertUpdate(e: DocumentEvent)  { schedule(e.offset, e.length) }
    override fun removeUpdate(e: DocumentEvent)  { schedule(max(0, e.offset - 1), 1) }

    /**
     * Ignored: an attribute change does not alter which characters a font can display, so the
     * ranges already chosen remain correct and a rescan would only rewrite the same attributes
     * document-wide. A genuine font change goes through [rescanEntireDocument] instead.
     */
    override fun changedUpdate(e: DocumentEvent) {}

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
        } catch (_: BadLocationException) {
            // The document was replaced between scheduling and running. Font fallback is
            // best-effort presentation, so dropping this pass is correct; the next edit reschedules.
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
                applyRunAttributes(offset + pos, end - pos, primary)
                break
            }
            if (primaryFail > pos) {
                applyRunAttributes(offset + pos, primaryFail - pos, primary)
                pos = primaryFail
                continue
            }

            val fallbackFail = fallback.canDisplayUpTo(chars, pos, end)
            if (fallbackFail == -1) {
                applyRunAttributes(offset + pos, end - pos, fallback)
                break
            }
            if (fallbackFail > pos) {
                applyRunAttributes(offset + pos, fallbackFail - pos, fallback)
                pos = fallbackFail
                continue
            }

            // Neither font can display this code point — skip it and let the system handle it.
            pos += Character.charCount(Character.codePointAt(chars, pos))
        }
    }

    /** Applies font family/size to a run while preserving all other character attributes. */
    private fun applyRunAttributes(docOffset: Int, runLength: Int, font: Font) {
        if (runLength <= 0) return
        textPane.applyFallbackFontAttributes(docOffset, runLength, font)
    }
}

/** Shared because it is only ever read; a fresh one per paint was pure garbage. */
private val EMPTY_INSETS = Insets(0, 0, 0, 0)

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

    /** Internal so tests can assert what the undo history does and does not contain. */
    internal val undoManager by lazy { UndoManager() }

    /** Scratch attribute set for font-fallback runs, which are written in batches. */
    private val reusableAttrs = SimpleAttributeSet()

    /**
     * Highlighter tags for the spell-check underlines.
     *
     * Kept so a correction update removes only its own layer rather than every highlight.
     */
    private val correctionHighlights = ArrayList<Any>()

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

    /**
     * Direction of each paragraph as of the last alignment pass, parallel to the root element's
     * children, so a keystroke re-measures only the paragraph it touched.
     */
    private val paragraphRtl = ArrayList<Boolean>()
    private var rtlParagraphCount = 0
    private val rtlParagraphAttributes = SimpleAttributeSet()
        .apply { StyleConstants.setAlignment(this, StyleConstants.ALIGN_RIGHT) }
    private val ltrParagraphAttributes = SimpleAttributeSet()
        .apply { StyleConstants.setAlignment(this, StyleConstants.ALIGN_LEFT) }

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
            java.awt.KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
            setOf(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0))
        )
        setFocusTraversalKeys(
            java.awt.KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
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

            if (lastRenderedText != text) {
                // Only the user-edit callback is detached, so a programmatic render is not echoed
                // back through the state flow as typing. The font-fallback listener stays attached
                // so the new text is scanned for characters the primary font cannot draw.
                document.removeDocumentListener(documentListener)

                this.text = text
                lastRenderedText  = text
                lastEmittedText   = text
                undoManager.discardAllEdits()

                applyParagraphDirections()

                document.addDocumentListener(documentListener)
            }

            if (lastRenderedCorrections != corrections) {
                updateHighlights(corrections)
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
            SwingUtilities.invokeLater { applyParagraphDirections(offset, offset + length) }
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
    // Highlights
    // -----------------------------------------------------------------------

    /**
     * Replaces the spell-check underline layer.
     *
     * Ranges are validated against the document first: a correction can describe text the user has
     * since shortened, and stale ranges are dropped rather than allowed to reach a paint path that
     * would throw.
     */
    private fun updateHighlights(corrections: List<Correction>) {
        correctionHighlights.forEach { tag -> runCatching { highlighter.removeHighlight(tag) } }
        correctionHighlights.clear()

        val docLength = document.length
        for (correction in corrections) {
            val start = correction.startIndex
            val end = correction.endIndex
            if (start < 0 || end > docLength || start >= end) continue
            runCatching { highlighter.addHighlight(start, end, wavyPainter) }
                .onSuccess { correctionHighlights.add(it) }
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
     * Alignment is per paragraph because a translation can mix an Arabic paragraph with an English
     * one. Direction *within* a line is Swing's own Bidi layout.
     *
     * Only paragraphs intersecting `[dirtyStart, dirtyEnd)` are re-measured; the component-wide
     * majority is still decided over all of them. A paragraph added or removed forces the full pass.
     */
    private fun applyParagraphDirections(dirtyStart: Int = 0, dirtyEnd: Int = Int.MAX_VALUE) {
        val root = styledDocument.defaultRootElement
        val paragraphCount = root.elementCount

        var from = dirtyStart
        var to = dirtyEnd
        if (paragraphRtl.size != paragraphCount) {
            paragraphRtl.clear()
            repeat(paragraphCount) { paragraphRtl.add(false) }
            rtlParagraphCount = 0
            from = 0
            to = Int.MAX_VALUE
        }

        var documentTouched = false
        for (index in 0 until paragraphCount) {
            val paragraph = root.getElement(index)
            val start = paragraph.startOffset
            val end = paragraph.endOffset
            val length = (end - start).coerceAtMost(styledDocument.length - start)
            val cached = paragraphRtl[index]

            if (length <= 0) {
                // An empty paragraph carries no direction and counts toward the majority as
                // non-RTL.
                if (cached) {
                    paragraphRtl[index] = false
                    rtlParagraphCount--
                    documentTouched = true
                }
                continue
            }

            val rtl = if (start <= to && end >= from) {
                val paragraphText = runCatching { styledDocument.getText(start, length) }.getOrNull()
                    ?: continue
                paragraphText.isRTL()
            } else {
                cached
            }

            if (rtl != cached) {
                paragraphRtl[index] = rtl
                if (rtl) rtlParagraphCount++ else rtlParagraphCount--
                documentTouched = true
            }

            val wanted = if (rtl) StyleConstants.ALIGN_RIGHT else StyleConstants.ALIGN_LEFT
            if (StyleConstants.getAlignment(paragraph.attributes) == wanted) continue
            withoutUndo {
                styledDocument.setParagraphAttributes(
                    start, length, if (rtl) rtlParagraphAttributes else ltrParagraphAttributes, false
                )
            }
            documentTouched = true
        }

        // The component follows the majority, since it decides which side the scrollbar and the
        // caret's home position sit on, and those belong to the pane rather than to a paragraph.
        val documentIsRtl = rtlParagraphCount * 2 > paragraphCount
        if (documentIsRtl != isTextRtl) {
            isTextRtl = documentIsRtl
            componentOrientation =
                if (documentIsRtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
            documentTouched = true
        }

        if (documentTouched) {
            revalidate()
            repaint()
        }
    }

    // -----------------------------------------------------------------------
    // Key bindings
    // -----------------------------------------------------------------------

    private fun setupKeyBindings() {
        // The primary shortcut modifier is Ctrl on Windows and Linux, Command on macOS.
        val menuMask = MenuShortcutModifier.current()

        // Redo differs by platform: Ctrl+Y on Windows and Linux, Shift+Cmd+Z on macOS.
        val undoStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask)
        val redoStroke = if (menuMask == InputEvent.CTRL_DOWN_MASK) {
            KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)
        } else {
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask or InputEvent.SHIFT_DOWN_MASK)
        }

        val undoAction = createAction("Undo", undoStroke) {
            if (undoManager.canUndo()) undoManager.undo()
        }
        val redoAction = createAction("Redo", redoStroke) {
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
        actionMap.put(TRANSLATE_ACTION, translateAction)
        inputMap.put(undoStroke, "undo")
        inputMap.put(redoStroke, "redo")
        // Accept the other redo convention as well: Ctrl+Shift+Z where the menu key is Ctrl, or
        // Ctrl+Y elsewhere.
        val alternativeRedo = KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask or InputEvent.SHIFT_DOWN_MASK)
        if (alternativeRedo != redoStroke) inputMap.put(alternativeRedo, "redo")
        if (menuMask != InputEvent.CTRL_DOWN_MASK) {
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo")
        }
        // Ctrl+H would otherwise reach the look and feel's delete-previous binding. Ctrl, not the
        // menu key, since Cmd+H is the system Hide command on macOS.
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "none")

        // Explicitly wire standard text shortcuts in the component-level WHEN_FOCUSED InputMap.
        // Although these are already in the LAF's parent InputMap, setting the EditorKit
        // (WrappingEditorKit) triggers a UI reinstall whose InputMap parent chain can be
        // momentarily incomplete on some JVM/LAF combinations, causing the shortcuts to
        // silently fail.  Wiring them here makes the binding deterministic regardless of
        // reinstallation order.
        // CutAction and PasteAction are self-guarding: they no-op when isEditable = false.
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask), "copy-to-clipboard")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, menuMask), "select-all")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, menuMask), "cut-to-clipboard")

        val pasteStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask)
        if (onImageDropped != null) {
            // Paste stays on the pane rather than moving to the frame with drops: it targets
            // whatever has focus, so it is genuinely this component's business. It shares the
            // classifier so pasting and dropping agree on what a thing is.
            val pasteAction = createAction("PasteImageOrText", pasteStroke) {
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
            inputMap.put(pasteStroke, "paste-image-or-text")
        } else {
            // Output / read-only panes: wire plain text paste so it is always available
            // through the component-level InputMap (PasteAction is a no-op when !isEditable).
            inputMap.put(pasteStroke, "paste-from-clipboard")
        }
    }

    /**
     * Swaps the keyboard shortcut that triggers the translate action.
     * Called by the owning panel whenever the user changes the binding in Settings.
     * [old] is released and [new] registered; either may be null.
     *
     * Returns false, and installs nothing, when [new] is already taken by another action, so a
     * configured shortcut cannot silently disarm Copy, Paste or Undo. The caller can report that
     * rather than leave the user with a shortcut that does nothing.
     */
    fun setTranslateKeyStroke(old: KeyStroke?, new: KeyStroke?): Boolean {
        // Validated first: a refused rebind must leave the previous binding untouched.
        if (new != null) {
            val occupiedBy = inputMap.get(new)
            if (occupiedBy != null && occupiedBy != TRANSLATE_ACTION) return false
        }

        // Only a stroke this action owns may be released.
        if (old != null && old != new && inputMap.get(old) == TRANSLATE_ACTION) {
            inputMap.remove(old)
        }

        // An unchanged stroke is already in place; a null [new] clears the binding.
        if (new != null && new != old) {
            inputMap.put(new, TRANSLATE_ACTION)
        }
        return true
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
     */
    private fun <T> withoutUndo(block: () -> T): T {
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

        /** Action name for the configurable translate command. */
        const val TRANSLATE_ACTION = "translate"
    }
}
