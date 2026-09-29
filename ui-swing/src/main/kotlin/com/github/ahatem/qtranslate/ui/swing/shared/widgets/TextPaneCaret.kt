package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultCaret
import javax.swing.text.Position
import kotlin.math.roundToInt

/**
 * A wider, vertically inset caret for comfortable reading.
 *
 * Only the appearance is custom. Blinking is left to [DefaultCaret], which starts and stops its
 * flasher with focus and editability; a second timer would run regardless of focus and compete
 * with it for visibility.
 *
 * Dimensions are in logical pixels and scaled for the display.
 */
class AdvancedCaret(
    private val caretWidth: kotlin.Float = 3f,
    blinkRate: Int = 600,
    private val verticalInset: kotlin.Float = 3f
) : DefaultCaret() {

    init { setBlinkRate(blinkRate) }

    override fun paint(g: Graphics?) {
        if (!isVisible) return
        val comp = component ?: return
        val g2 = g as? Graphics2D ?: return

        val oldStroke = g2.stroke
        val oldColor  = g2.color
        val oldHints  = g2.renderingHints

        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
            g2.stroke = BasicStroke(UIScale.scale(caretWidth))
            g2.color  = comp.caretColor

            val inset = UIScale.scale(verticalInset)
            val viewRect = comp.ui.modelToView2D(comp, dot, Position.Bias.Forward) ?: return
            val x      = viewRect.x.roundToInt()
            val yStart = (viewRect.y + inset).roundToInt()
            val yEnd   = (viewRect.y + viewRect.height - inset).roundToInt()
            g2.drawLine(x, yStart, x, yEnd)
        } catch (_: BadLocationException) {
        } finally {
            g2.stroke = oldStroke
            g2.color  = oldColor
            g2.setRenderingHints(oldHints)
        }
    }
}
