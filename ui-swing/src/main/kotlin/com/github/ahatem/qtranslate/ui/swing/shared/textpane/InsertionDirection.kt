package com.github.ahatem.qtranslate.ui.swing.shared.textpane

import javax.swing.text.AbstractDocument
import javax.swing.text.Document
import javax.swing.text.Position
import javax.swing.text.StyleConstants

/**
 * Which way text inserted at the caret would run.
 *
 * This is Swing's own answer, not a second opinion: [javax.swing.text.DefaultCaret] decides whether to
 * paint its bidirectional flag by asking the same question of the same structure, and the caret here
 * must not disagree with the text around it about which direction an insertion goes.
 *
 * [at] answers `null` where Swing paints no flag at all, which is every document that holds a single
 * direction. That keeps ordinary text free of the mark; there is nothing ambiguous about an insertion
 * point in a document with one direction.
 */
internal object InsertionDirection {

    /**
     * Whether the character the caret is next to reads left to right, or `null` when this document
     * has only one direction and therefore nothing to indicate.
     *
     * @param dot the caret's position in the model.
     * @param bias which side of [dot] the caret leans to. At a boundary between two directions the
     *   two biases name different characters, which is exactly the case the flag exists to show.
     */
    fun at(document: Document, dot: Int, bias: Position.Bias): Boolean? {
        val abstractDocument = document as? AbstractDocument ?: return null
        val bidi = abstractDocument.bidiRootElement ?: return null
        // More than one run of one level means more than one direction is present. Swing's caret
        // gates its flag on the same count.
        if (bidi.elementCount <= 1) return null

        // A backward caret sits after the character at dot - 1, and that character is the one the
        // insertion continues. `DefaultCaret.isPositionLTR` shifts the position the same way.
        var position = dot
        if (bias == Position.Bias.Backward && --position < 0) position = 0
        position = position.coerceIn(0, abstractDocument.length)

        val level = StyleConstants.getBidiLevel(bidi.getElement(bidi.getElementIndex(position)).attributes)
        return level % 2 == 0
    }
}
