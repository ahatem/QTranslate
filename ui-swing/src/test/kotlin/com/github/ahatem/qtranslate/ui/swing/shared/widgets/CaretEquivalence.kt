package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.text.BreakIterator
import javax.swing.text.AbstractDocument

/**
 * Whether a click on the caret drawn for `offset` may legitimately come back as `back`.
 *
 * One visual caret belongs to more than one model position in exactly two ways, and both are
 * accepted only on the caret's own row:
 * - inside a grapheme cluster, where a position between a base and its marks is drawn at an edge of
 *   that cluster and resolves to it;
 * - at the two logical ends of one bidi run. An embedded run of the opposite direction starts and is
 *   followed at the same visual edge: in right-to-left "إضافة / 319 عملية", the start of "319" and
 *   the space after it both sit at the left edge of "319". Each side's caret comes from its own
 *   run's layout, so the two can be a pixel apart; they are still one boundary.
 *
 * Anything else -- another word, another row, a position further along -- is a miss.
 */
internal fun AdvancedTextPane.caretIsEquivalent(offset: Int, back: Int): Boolean {
    if (back == offset) return true
    if (modelToView2D(offset).y != modelToView2D(back).y) return false
    val doc = styledDocument
    val text = doc.getText(0, doc.length)

    val clusters = BreakIterator.getCharacterInstance().apply { setText(text) }
    if (offset in 1 until text.length && !clusters.isBoundary(offset)) {
        if (back == clusters.preceding(offset) || back == clusters.following(offset)) return true
    }

    val bidi = (doc as? AbstractDocument)?.bidiRootElement ?: return false
    val run = bidi.getElement(bidi.getElementIndex(minOf(offset, back)))
    return run.startOffset == minOf(offset, back) && run.endOffset == maxOf(offset, back)
}
