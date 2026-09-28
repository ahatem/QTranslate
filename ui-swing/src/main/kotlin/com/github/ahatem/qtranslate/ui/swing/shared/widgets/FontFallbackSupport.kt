package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.textpane.ShapedCarets
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.AttributedString
import java.text.BreakIterator
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.BadLocationException
import javax.swing.text.StyledDocument
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The same font at the point size whose ascent matches [base]'s.
 *
 * Baseline alignment cannot be carried as a vertical `AffineTransform`: `LabelView` resolves a
 * run's font through `StyleContext.getFont`, which reads only family, style and size. Size is the
 * one metric the rendering path honours, so the adjustment is applied as a point size.
 */
internal fun Font.metricAlignedTo(base: Font): Font {
    if (this === base) return this
    val renderContext = FontRenderContext(null, true, true)
    val baseAscent = base.getLineMetrics("A", renderContext).ascent
    val currentAscent = getLineMetrics("A", renderContext).ascent
    if (baseAscent <= 0f || currentAscent <= 0f) return this
    val alignedSize = (size2D * (baseAscent / currentAscent)).roundToInt().coerceAtLeast(1)
    return if (alignedSize == size) this else deriveFont(alignedSize.toFloat())
}

/**
 * Rescans edited text for characters the primary font cannot draw, or cannot shape soundly, and
 * hands the runs to the pane.
 */
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
     * Left empty on purpose: an attribute change cannot change which characters a font can draw, so
     * a rescan would only rewrite the same runs document-wide. A real font change goes through
     * [rescanEntireDocument].
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
            // Whole paragraphs: whether a script needs the fallback is decided per paragraph, so an
            // edit to one word cannot leave that word in a different face from its neighbours.
            val start = doc.getParagraphElement(safeOff).startOffset
            val end   = doc.getParagraphElement(safeOff + safeLen - 1).endOffset.coerceAtMost(docLen)
            applyFontFallback(doc, start, end - start, textPane.primaryFont, textPane.fallbackFont)
        } catch (_: BadLocationException) {
            // The document was replaced before this pass ran. Fallback is presentation only, so
            // dropping it is fine and the next edit reschedules.
        } finally {
            applying = false
        }
    }

    private fun applyFontFallback(doc: StyledDocument, offset: Int, length: Int, primary: Font, fallback: Font) {
        if (length <= 0) return
        val text = doc.getText(offset, length)
        val fonts = coverageChoice(text, primary, fallback)
        val renderContext = textPane.getFontMetrics(primary).fontRenderContext
        ShapingAwareFallback(text, fonts, primary, fallback, fallback.metricAlignedTo(primary), renderContext).apply()

        var runStart = 0
        while (runStart < fonts.size) {
            val font = fonts[runStart]
            var runEnd = runStart + 1
            while (runEnd < fonts.size && fonts[runEnd] === font) runEnd++
            // Neither font supports these code points; leave them unchanged.
            if (font != null) applyRunAttributes(offset + runStart, runEnd - runStart, font)
            runStart = runEnd
        }
    }

    /**
     * Per character: [primary] where it can draw the grapheme cluster the character belongs to, else
     * [fallback] where that can, else null. Decided cluster by cluster so a base letter and its marks
     * are never drawn by two fonts, and so the text goes back to the primary as soon as the primary
     * can draw it again.
     */
    private fun coverageChoice(text: String, primary: Font, fallback: Font): Array<Font?> {
        // A char array so canDisplayUpTo can check a cluster without allocating substrings.
        val chars = text.toCharArray()
        val fonts = arrayOfNulls<Font>(chars.size)
        val clusters = BreakIterator.getCharacterInstance().apply { setText(text) }
        var start = clusters.first()
        var end = clusters.next()
        while (end != BreakIterator.DONE) {
            fonts.fill(
                when {
                    primary.canDisplayUpTo(chars, start, end) == -1 -> primary
                    fallback.canDisplayUpTo(chars, start, end) == -1 -> fallback
                    else -> null
                },
                start, end
            )
            start = end
            end = clusters.next()
        }
        return fonts
    }

    private fun applyRunAttributes(docOffset: Int, runLength: Int, font: Font) {
        if (runLength <= 0) return
        textPane.applyFallbackFontAttributes(docOffset, runLength, font)
    }
}

/**
 * Moves text the primary font covers but cannot shape soundly to the fallback font.
 *
 * A font can hold every glyph of a script and still produce a layout with broken hit-testing, which
 * breaks wrapping, the caret and the mouse together. [ShapedCarets.layoutIsSound] decides that from
 * the layout's own carets. The decision is per script within a paragraph, so one script's text is
 * all in one face and other scripts never move. The primary stays wherever it is sound, and the
 * fallback is used only where it is sound itself; otherwise the view layer's last resort applies.
 *
 * [fonts] holds [primary], [fallback] or null per character of [text] and is rewritten in place.
 * [text] must start at a paragraph boundary.
 */
internal class ShapingAwareFallback(
    private val text: String,
    private val fonts: Array<Font?>,
    private val primary: Font,
    private val fallback: Font,
    private val alignedFallback: Font,
    private val renderContext: FontRenderContext,
) {
    fun apply() {
        if (primary == fallback) return
        var paragraphStart = 0
        while (paragraphStart < text.length) {
            val newline = text.indexOf('\n', paragraphStart)
            val paragraphEnd = if (newline < 0) text.length else newline
            if (paragraphEnd > paragraphStart) applyToParagraph(paragraphStart, paragraphEnd)
            paragraphStart = paragraphEnd + 1
        }
    }

    private fun applyToParagraph(start: Int, end: Int) {
        for (runs in scriptRuns(start, end).groupBy { it.script }.values) {
            if (runs.none { run -> (run.start until run.end).any { fonts[it] === primary } }) continue
            if (isSound(start, end, fonts, runs)) continue

            val candidate = fonts.copyOf()
            var changed = false
            for (run in runs) {
                var i = run.start
                while (i < run.end) {
                    val codePoint = text.codePointAt(i)
                    val width = Character.charCount(codePoint)
                    if (candidate[i] === primary && fallback.canDisplay(codePoint)) {
                        candidate.fill(fallback, i, minOf(i + width, run.end))
                        changed = true
                    }
                    i += width
                }
            }
            if (changed && isSound(start, end, candidate, runs)) candidate.copyInto(fonts)
        }
    }

    /** Whether the paragraph `[start, end)`, laid out with [choice], is sound across every one of [runs]. */
    private fun isSound(start: Int, end: Int, choice: Array<Font?>, runs: List<ScriptRun>): Boolean {
        val paragraph = text.substring(start, end)
        val attributed = AttributedString(paragraph)
        var runStart = start
        while (runStart < end) {
            val font = choice[runStart]
            var runEnd = runStart + 1
            while (runEnd < end && choice[runEnd] === font) runEnd++
            val drawnWith = if (font === fallback) alignedFallback else primary
            attributed.addAttribute(TextAttribute.FONT, drawnWith, runStart - start, runEnd - start)
            runStart = runEnd
        }
        val layout = runCatching { TextLayout(attributed.iterator, renderContext) }.getOrNull() ?: return true
        return runs.all { ShapedCarets.layoutIsSound(paragraph, layout, it.start - start, it.end - start) }
    }

    private class ScriptRun(val start: Int, val end: Int, val script: Character.UnicodeScript)

    /**
     * The paragraph `[start, end)` cut into runs of one script each. Characters shared between
     * scripts (spaces, punctuation, digits, combining marks) belong to the run around them, and
     * any before the first letter to the run that letter starts.
     */
    private fun scriptRuns(start: Int, end: Int): List<ScriptRun> {
        val runs = ArrayList<ScriptRun>()
        var runStart = start
        var script: Character.UnicodeScript? = null
        var i = start
        while (i < end) {
            val codePoint = text.codePointAt(i)
            val own = Character.UnicodeScript.of(codePoint)
            val shared = own == Character.UnicodeScript.COMMON ||
                own == Character.UnicodeScript.INHERITED ||
                own == Character.UnicodeScript.UNKNOWN
            if (!shared) {
                if (script != null && own != script) {
                    runs += ScriptRun(runStart, i, script)
                    runStart = i
                }
                script = own
            }
            i += Character.charCount(codePoint)
        }
        runs += ScriptRun(runStart, end, script ?: Character.UnicodeScript.COMMON)
        return runs
    }
}
