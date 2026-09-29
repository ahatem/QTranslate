package com.github.ahatem.qtranslate.ui.swing.shared.textpane

import java.text.BreakIterator
import java.util.Locale
import javax.swing.text.AbstractDocument
import javax.swing.text.Document

/**
 * Where a line may legally end, decided from the paragraph around a position. A view can start or end
 * inside a word (at a font, style or bidi run boundary), so the view's own text is not enough.
 *
 * [Locale.ROOT]: the JDK's line-break rules only differ by locale for dictionary-based scripts, and a
 * translation is not in the OS locale anyway.
 */
internal object LineBreakOpportunities {

    /** Enough text either side for every rule the iterator applies to see what it needs. */
    private const val CONTEXT = 64

    /**
     * The last legal line-break opportunity in `(from, limit]`, or [from] if there is none.
     *
     * A result equal to [from] means a line starting at [from] cannot end before [limit] without
     * cutting a word.
     */
    fun lastIn(document: Document, from: Int, limit: Int): Int {
        if (limit <= from) return from
        val paragraph = (document as? AbstractDocument)?.getParagraphElement(from)
        val paragraphStart = paragraph?.startOffset ?: 0
        val paragraphEnd = paragraph?.endOffset ?: document.length
        val contextStart = maxOf(paragraphStart, from - CONTEXT)
        val contextEnd = minOf(paragraphEnd, limit + CONTEXT, document.length)
        if (limit > contextEnd) return from

        val breaker = BreakIterator.getLineInstance(Locale.ROOT)
        breaker.setText(document.getText(contextStart, contextEnd - contextStart))
        val relative = limit - contextStart
        val spot = if (breaker.isBoundary(relative)) relative else breaker.preceding(relative)
        if (spot == BreakIterator.DONE) return from
        return maxOf(from, contextStart + spot)
    }
}
