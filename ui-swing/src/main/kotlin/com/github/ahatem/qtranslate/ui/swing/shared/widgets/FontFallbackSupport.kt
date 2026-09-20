package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.Font
import java.awt.font.FontRenderContext
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
 * Rescans edited text for characters the primary font cannot draw and hands the runs to the pane.
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
