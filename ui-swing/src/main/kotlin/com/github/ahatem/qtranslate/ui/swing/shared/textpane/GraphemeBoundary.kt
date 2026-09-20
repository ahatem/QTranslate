package com.github.ahatem.qtranslate.ui.swing.shared.textpane

import java.text.BreakIterator

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
        const val LOOKAHEAD = 64
    }
}
