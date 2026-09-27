package com.github.ahatem.qtranslate.ui.swing.shared.textpane

import java.text.BreakIterator
import java.util.Locale
import javax.swing.text.AbstractDocument
import javax.swing.text.Document

/**
 * Where a line may legally end, decided from the paragraph around a position rather than from
 * whichever view fragment happens to hold it.
 *
 * A view can start or end in the middle of a word -- a font-fallback run, a styling change or a bidi
 * run boundary splits the text wherever the attributes change, not where the words do -- so the
 * fragment on its own would make its first character look like the start of a word. The rules are
 * applied to the document text on both sides of the candidate instead.
 *
 * The iterator is created for [Locale.ROOT]. The JDK's line-break rules are the same for every locale
 * except those that need a word dictionary, and the text in a translation pane is not in the user's
 * OS locale anyway, so nothing is gained by asking for it.
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
