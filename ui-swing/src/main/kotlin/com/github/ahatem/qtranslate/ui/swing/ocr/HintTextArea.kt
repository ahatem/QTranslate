package com.github.ahatem.qtranslate.ui.swing.ocr

import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.text.AttributedString
import javax.swing.JTextArea
import javax.swing.UIManager

/** An empty-state hint that never becomes editable or copyable document content. */
internal class HintTextArea : JTextArea() {
    var hint: String = ""
        set(value) { field = value; repaint() }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (document.length != 0 || hint.isBlank()) return
        val copy = g.create() as Graphics2D
        copy.color = UIManager.getColor("TextField.placeholderForeground")
            ?: UIManager.getColor("Label.disabledForeground") ?: foreground
        val iterator = AttributedString(hint).apply { addAttribute(TextAttribute.FONT, font) }.iterator
        val lines = LineBreakMeasurer(iterator, copy.fontRenderContext)
        val available = (width - insets.left - insets.right - 6).coerceAtLeast(1).toFloat()
        var y = (insets.top + 3).toFloat()
        while (lines.position < iterator.endIndex) {
            val line = lines.nextLayout(available) ?: break
            y += line.ascent
            if (y > height) break
            val x = if (componentOrientation.isLeftToRight) (insets.left + 3).toFloat()
                else width - insets.right - 3 - line.advance
            line.draw(copy, x, y)
            y += line.descent + line.leading
        }
        copy.dispose()
    }
}
