package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.FontUtils
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import java.awt.Container
import java.awt.Font
import java.awt.image.BufferedImage
import java.text.BreakIterator
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.text.AbstractDocument
import javax.swing.text.Element
import javax.swing.text.StyleConstants
import javax.swing.text.View

/** Test harness for the shaped-text suites: real panes, laid out and painted headlessly. */
internal object ShapedText {

    fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /**
     * A pane whose text gets [width] pixels to wrap in, with [primary] and [fallback] configured the
     * way the settings would configure them.
     *
     * [width] is the room the text has, not the component's size: the scroll pane's border, the
     * scroll bar and the pane's margin all scale with whatever look and feel an earlier test left
     * installed.
     */
    fun pane(text: String, width: Int, primary: String, fallback: String): AdvancedTextPane {
        // Registered as AppUiSetup does, so Font(name, ...) resolves to the bundled file.
        RubikSansFont.installLazy()
        FontUtils.getCompositeFont(RubikSansFont.FAMILY, Font.PLAIN, 15)
        NotoNaskhArabicFont.install()

        val pane = onEdt { AdvancedTextPane(onTextChanged = {}, onTranslateRequest = {}, onListenRequest = {}) }
        onEdt {
            JScrollPane(pane)
            pane.render(text, emptyList(), isEditable = true)
            pane.updateFontsAndRescanDocument(Font(primary, Font.PLAIN, 15), Font(fallback, Font.PLAIN, 15))
        }
        resize(pane, width)
        awaitFontRuns(pane)
        resize(pane, width)
        return pane
    }

    /** Gives the pane's text [width] pixels to wrap in; see [pane]. */
    fun resize(pane: AdvancedTextPane, width: Int) {
        onEdt {
            val scroll = pane.parent.parent as JScrollPane
            val chrome = scroll.insets.left + scroll.insets.right +
                scroll.verticalScrollBar.preferredSize.width +
                pane.insets.left + pane.insets.right
            scroll.setSize(width + chrome, 600)
        }
        settle(pane)
        // Swing's text UI answers no keyboard navigation for a component it has never painted.
        onEdt {
            val image = BufferedImage(maxOf(1, pane.width), maxOf(1, pane.height), BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try { pane.paint(g) } finally { g.dispose() }
        }
    }

    /** The font-fallback pass runs on a timer; waits until it has stopped rewriting runs. */
    private fun awaitFontRuns(pane: AdvancedTextPane, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        var stable = 0
        while (System.currentTimeMillis() < deadline && stable < 5) {
            Thread.sleep(20)
            val runs = onEdt { runs(pane).joinToString() }
            stable = if (runs == last) stable + 1 else 0
            last = runs
        }
    }

    /** Lays the tree out until the rows stop changing between passes. */
    private fun settle(pane: AdvancedTextPane, timeoutMs: Long = 5000) {
        val scroll = onEdt { pane.parent.parent as JScrollPane }
        val deadline = System.currentTimeMillis() + timeoutMs
        var previous: String? = null
        var stable = 0
        while (System.currentTimeMillis() < deadline && stable < 3) {
            onEdt { layoutTree(scroll) }
            val signature = onEdt { rows(pane).joinToString("|") { "${it.startOffset}-${it.endOffset}:${it.getPreferredSpan(View.X_AXIS)}" } }
            stable = if (signature == previous) stable + 1 else 0
            previous = signature
        }
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    /** `start-end:family` for each character run of the document. */
    fun runs(pane: AdvancedTextPane): List<String> {
        val doc = pane.styledDocument
        val found = ArrayList<String>()
        var offset = 0
        while (offset < doc.length) {
            val element = doc.getCharacterElement(offset)
            found += "${element.startOffset}-${element.endOffset}:${StyleConstants.getFontFamily(element.attributes)}"
            offset = element.endOffset
        }
        return found
    }

    /** The first paragraph's rows, in document order. Call on the EDT. */
    fun rows(pane: AdvancedTextPane): List<View> {
        val paragraph = pane.ui.getRootView(pane).getView(0).getView(0)
        return (0 until paragraph.viewCount).map { paragraph.getView(it) }.sortedBy { it.startOffset }
    }

    /** Whether the first paragraph is laid out by the measured last resort. Call on the EDT. */
    fun measuresRuns(pane: AdvancedTextPane): Boolean =
        (pane.ui.getRootView(pane).getView(0).getView(0) as WrappingEditorKit.WrappingParagraphView).measuresRuns
}

/**
 * Whether a click on the caret drawn for [offset] may legitimately return [back]. Call on the EDT.
 *
 * A caret belongs to one model position except in two cases, both on the caret's own row:
 * - a position inside a grapheme cluster resolves to one of that cluster's edges;
 * - an embedded run read against its surroundings (left-to-right "319" inside right-to-left text)
 *   ends at the edge where it began, so its start and the position just after it share one caret.
 *   Each side's caret comes from its own run's layout, so they can differ by a pixel of rounding.
 */
internal fun AdvancedTextPane.caretIsEquivalent(offset: Int, back: Int): Boolean {
    if (back == offset) return true
    if (modelToView2D(offset).y != modelToView2D(back).y) return false
    val doc = styledDocument
    val text = doc.getText(0, doc.length)

    val clusters = BreakIterator.getCharacterInstance().apply { setText(text) }
    if (offset in 1 until text.length && !clusters.isBoundary(offset)) {
        return back == clusters.preceding(offset) || back == clusters.following(offset)
    }

    val start = minOf(offset, back)
    val end = maxOf(offset, back)
    val bidi = (doc as? AbstractDocument)?.bidiRootElement ?: return false
    if (end >= doc.length) return false
    val run = bidi.getElement(bidi.getElementIndex(start))
    if (run.startOffset != start || run.endOffset != end) return false
    val runLevel = run.bidiLevel()
    val afterLevel = bidi.getElement(bidi.getElementIndex(end)).bidiLevel()
    return runLevel > afterLevel && runLevel % 2 != afterLevel % 2
}

private fun Element.bidiLevel(): Int = StyleConstants.getBidiLevel(attributes)
