package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import java.awt.Font
import java.text.BreakIterator

/**
 * Chooses the font each grapheme cluster of a run is drawn with.
 *
 * A pure function of the text and the fonts, so every text surface reaches the same decision without
 * sharing a rendering layer. [FontFallbackDocumentListener] applies it to a translated pane,
 * [com.github.ahatem.qtranslate.ui.swing.shared.widgets.DefinitionStrip] to a dictionary definition.
 */
object FontRuns {

    /**
     * Per character of [text]: [primary] where it can draw that character's grapheme cluster, else
     * [fallback] where that can, else the first bundled rescue face that covers the cluster, else
     * null. Decided cluster by cluster so a base letter and its marks are never drawn by two fonts,
     * and so the text goes back to the primary as soon as the primary can draw it again.
     *
     * A rescue face is reached only when neither configured font can draw the cluster, so the user's
     * own choices keep priority and Latin stays in the primary even though a rescue face carries
     * Latin glyphs too.
     */
    fun resolve(
        text: String,
        primary: Font,
        fallback: Font,
        rescue: Sequence<Font> = PortableFallbackFonts.candidates(primary.size),
    ): Array<Font?> {
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
                    // Re-walked per cluster, and it has to be: the chain is ordered, so a face reached
                    // for one cluster is still the answer for the next one.
                    else -> rescue.firstOrNull { it.canDisplayUpTo(chars, start, end) == -1 }
                },
                start, end
            )
            start = end
            end = clusters.next()
        }
        return fonts
    }

    /** The distinct fonts of [fonts] in order, with null dropped. */
    fun distinct(fonts: Array<Font?>): List<Font> {
        val found = ArrayList<Font>()
        for (font in fonts) if (font != null && found.none { it === font }) found += font
        return found
    }
}
