package com.github.ahatem.qtranslate.ui.swing.settings.panels

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.LineBreakMeasurer
import java.text.AttributedString
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.BreakIterator
import javax.swing.JComponent

/**
 * Wrapped, styled text on a Settings page: an explanation (italic, secondary) or a lead paragraph.
 *
 * ### Why not a JLabel with HTML
 *
 * That is what this replaced, and it had two defects that only showed up in Arabic:
 *
 * 1. Swing's HTML renderer does not break *italic* Arabic. Measured on this dialog, the same
 *    sentence wrapped into two lines as upright text and stayed on one line inside `<i>` or under
 *    an italic component font, whatever width the body declared — so a long Arabic explanation was
 *    drawn as one line wider than its row and clipped at both ends.
 * 2. Even after the panel computed its own line breaks, the renderer scaled the declared CSS width
 *    by the display scale while the measurement had used `UIScale`, so at 150% the two disagreed by
 *    a few pixels and clipped the last glyph of the first line.
 *
 * A `LineBreakMeasurer` plus `TextLayout` has neither problem: the same font, the same measure and
 * the same `FontRenderContext` that lay out the text also draw it, and `TextLayout` is what gives
 * Arabic its joined letterforms and right-to-left order. The component is therefore exact by
 * construction rather than by agreement between two renderers.
 *
 * @param sourceText the text as written, kept for search and for tests.
 * @param textFont the font the text is both measured and drawn in.
 * @param color the text color.
 * @param measure the width to wrap at, in device pixels.
 */
internal class SettingsTextBlock(
    val sourceText: String,
    private val textFont: Font,
    color: Color,
    private val measure: Int,
) : JComponent() {

    init {
        font = textFont
        foreground = color
        isOpaque = false
        // A text block is read, not operated: it must not take focus from the settings around it.
        isFocusable = false
    }

    /** The lines as they will be drawn, used by tests to check the wrap rather than guess it. */
    fun renderedLines(): List<String> = layouts().map { it.source }

    override fun getPreferredSize(): Dimension {
        val laid = layouts()
        val height = laid.sumOf { (it.ascent + it.descent + it.leading).toDouble() }.toInt()
        return Dimension(measure, height.coerceAtLeast(1))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = textFont
            g2.color = foreground
            val leftToRight = componentOrientation.isLeftToRight
            var y = 0f
            for (line in layouts()) {
                y += line.ascent
                // Lines sit at the leading edge, which is the right edge in a right-to-left
                // interface — the same alignment the rest of the row uses.
                val x = if (leftToRight) 0f else (width - line.layout.advance).toFloat()
                line.layout.draw(g2, x, y)
                y += line.descent + line.leading
            }
        } finally {
            g2.dispose()
        }
    }

    private data class LaidLine(
        /** The text this line was broken from, so a test can see the wrap it got. */
        val source: String,
        val layout: TextLayout,
        val ascent: Float,
        val descent: Float,
        val leading: Float,
    )

    private fun layouts(): List<LaidLine> {
        val frc = getFontMetrics(textFont).fontRenderContext
        val lines = mutableListOf<LaidLine>()
        for (paragraph in sourceText.split("\n")) {
            if (paragraph.isBlank()) {
                // An empty line still takes a line's height, so a deliberate gap survives.
                val blank = TextLayout(" ", textFont, frc)
                lines += LaidLine("", blank, blank.ascent, blank.descent, blank.leading)
                continue
            }
            // The font has to travel with the text: a TextLayout built from an attributed
            // iterator without attributes falls back to the graphics default font, which is a
            // different size from the one this block was measured with.
            val attributed = AttributedString(paragraph).apply {
                addAttribute(TextAttribute.FONT, textFont)
            }
            val measurer = LineBreakMeasurer(
                attributed.iterator,
                BreakIterator.getLineInstance(),
                frc,
            )
            val limit = measure.toFloat()
            while (measurer.position < paragraph.length) {
                val lineStart = measurer.position
                val layout = measurer.nextLayout(limit)
                lines += LaidLine(
                    paragraph.substring(lineStart, measurer.position),
                    layout, layout.ascent, layout.descent, layout.leading,
                )
            }
        }
        return lines
    }
}
