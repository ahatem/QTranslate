package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.*
import java.awt.geom.Path2D
import javax.swing.text.*

class WavyUnderlineHighlighter : DefaultHighlighter() {

    override fun setDrawsLayeredHighlights(newValue: Boolean) {
        if (!newValue) {
            throw IllegalArgumentException("This highlighter only supports layered drawing.")
        }
        super.setDrawsLayeredHighlights(true)
    }

    class WavyUnderlinePainter(private val color: Color) : LayerPainter() {

        companion object {
            private const val WAVE_AMPLITUDE = 2 // Height of the wave crest/trough
            private const val WAVE_PERIOD = 4    // Width of a full wave cycle (peak + trough)
        }

        override fun paint(g: Graphics, p0: Int, p1: Int, bounds: Shape, c: JTextComponent) {}


        override fun paintLayer(
            g: Graphics,
            startOffset: Int,
            endOffset: Int,
            bounds: Shape,
            textComponent: JTextComponent,
            view: View
        ): Shape? {
            val area = getDrawingArea(startOffset, endOffset, bounds, view) ?: return null
            val g2d = g.create() as Graphics2D

            try {
                g2d.color = this.color
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val wavePath = createWavePath(area)
                g2d.draw(wavePath)

            } finally {
                g2d.dispose()
            }

            return area
        }

        private fun createWavePath(area: Rectangle): Path2D.Float {
            val path = Path2D.Float()

            // Calculate the vertical center of the wave, slightly below the text baseline.
            val waveCenterY = area.y + area.height - WAVE_AMPLITUDE
            val startX = area.x.toFloat()
            val endX = (area.x + area.width).toFloat()

            // Start the path at the beginning of the highlight area.
            path.moveTo(startX, waveCenterY.toFloat())

            // A half-period is one crest or one trough.
            val halfPeriod = (WAVE_PERIOD / 2.0).toFloat()
            var currentX = startX
            var direction = 1 // 1 for a crest (up), -1 for a trough (down)

            // Build the wave segment by segment using quadratic curves.
            while (currentX < endX) {
                val controlX = currentX + halfPeriod / 2f
                val controlY = (waveCenterY - (WAVE_AMPLITUDE * direction)).toFloat()
                val nextX = currentX + halfPeriod

                path.quadTo(controlX, controlY, nextX, waveCenterY.toFloat())

                currentX = nextX
                direction *= -1 // Flip direction for the next segment.
            }

            return path
        }

        private fun getDrawingArea(
            startOffset: Int,
            endOffset: Int,
            bounds: Shape,
            view: View
        ): Rectangle? {
            if (startOffset == view.startOffset && endOffset == view.endOffset) {
                return bounds.bounds
            }

            return try {
                val shape =
                    view.modelToView(startOffset, Position.Bias.Forward, endOffset, Position.Bias.Backward, bounds)
                shape.bounds
            } catch (e: BadLocationException) {
                null
            }
        }
    }
}