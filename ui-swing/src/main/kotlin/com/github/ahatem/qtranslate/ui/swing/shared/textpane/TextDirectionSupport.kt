package com.github.ahatem.qtranslate.ui.swing.shared.textpane

import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import java.awt.ComponentOrientation
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

internal class TextPaneDirections(private val pane: AdvancedTextPane) {

    private var isTextRtl = false

    // Per-paragraph direction as of the last pass, parallel to the root element's children, so a
    // keystroke only re-measures the paragraph it touched.
    private val paragraphRtl = ArrayList<Boolean>()
    private var rtlParagraphCount = 0
    private val rtlParagraphAttributes = SimpleAttributeSet()
        .apply { StyleConstants.setAlignment(this, StyleConstants.ALIGN_RIGHT) }
    private val ltrParagraphAttributes = SimpleAttributeSet()
        .apply { StyleConstants.setAlignment(this, StyleConstants.ALIGN_LEFT) }

    /**
     * Aligns each paragraph to its own direction, and the component to the document's.
     *
     * Alignment is per paragraph because a translation can mix an Arabic paragraph with an English
     * one; direction within a line is Swing's own Bidi layout. Only paragraphs intersecting
     * `[dirtyStart, dirtyEnd)` are re-measured, though the majority is decided over all of them, and
     * a paragraph added or removed forces the full pass.
     */
    fun apply(dirtyStart: Int = 0, dirtyEnd: Int = Int.MAX_VALUE) {
        val styledDocument = pane.styledDocument
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
            pane.withoutUndo {
                styledDocument.setParagraphAttributes(
                    start, length, if (rtl) rtlParagraphAttributes else ltrParagraphAttributes, false
                )
            }
            documentTouched = true
        }

        // The component follows the majority: it decides which side the scrollbar and the caret's
        // home position sit on.
        val documentIsRtl = rtlParagraphCount * 2 > paragraphCount
        if (documentIsRtl != isTextRtl) {
            isTextRtl = documentIsRtl
            pane.componentOrientation =
                if (documentIsRtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
            documentTouched = true
        }

        if (documentTouched) {
            pane.revalidate()
            pane.repaint()
        }
    }
}
