package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.*
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.text.*

class WavyUnderlineHighlighter : DefaultHighlighter() {

    override fun setDrawsLayeredHighlights(newValue: Boolean) {
        if (!newValue) {
            throw IllegalArgumentException("This highlighter only supports layered drawing.")
        }
        super.setDrawsLayeredHighlights(true)
    }

    /**
     * @param colorProvider Called on every paint to fetch the current theme color,
     *   so the underline always matches the active look-and-feel.
     */
    class WavyUnderlinePainter(private val colorProvider: () -> Color) : LayerPainter() {

        companion object {
            private const val WAVE_AMPLITUDE = 2 // Height of the wave crest/trough
            private const val WAVE_PERIOD = 4    // Width of a full wave cycle (peak + trough)

            /**
             * Upper bound on the cached strips. One entry per underline area currently
             * painted; the least recently painted falls out when corrections change.
             */
            private const val MAX_CACHED_STRIPS = 256

            /**
             * Strips larger than this are drawn directly. Correction ranges are words,
             * so anything this big is a degenerate range, not an underline.
             */
            private const val MAX_STRIP_WIDTH = 2048
            private const val MAX_STRIP_HEIGHT = 256
        }

        /**
         * The rendered pixels of one underline. The wave is a pure function of its
         * drawing area, the theme color and the device scale, so those three are the
         * key: anything that changes the output — text, font, layout, viewport,
         * theme, corrections, scaling — misses the cache and re-renders instead of
         * going stale.
         */
        private data class StripKey(
            val x: Int,
            val y: Int,
            val width: Int,
            val height: Int,
            val argb: Int,
            val scaleXBits: Long,
            val scaleYBits: Long,
        )

        private val strips = object : LinkedHashMap<StripKey, BufferedImage>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<StripKey, BufferedImage>): Boolean =
                size > MAX_CACHED_STRIPS
        }

        /** Read from the test suite only; the count of entries currently held. */
        internal fun cachedStripCount(): Int = strips.size

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
                val color = colorProvider()
                val strip = cachedStrip(g2d, area, color)
                if (strip != null) {
                    g2d.drawImage(strip, area.x, area.y, null)
                } else {
                    g2d.color = color
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2d.draw(createWavePath(area))
                }
            } finally {
                g2d.dispose()
            }

            return area
        }

        /**
         * The pre-rendered strip for [area], or null when this paint should draw
         * directly (degenerate transform or oversized area).
         */
        private fun cachedStrip(g2d: Graphics2D, area: Rectangle, color: Color): BufferedImage? {
            if (area.width <= 0 || area.height <= 0 ||
                area.width > MAX_STRIP_WIDTH || area.height > MAX_STRIP_HEIGHT
            ) return null
            val transform = g2d.transform
            // Only scale + translate can be replayed by blitting. Anything else
            // (rotation, shear) is never produced by text painting, but draw it
            // directly rather than risk a stale replay.
            if (transform.shearX != 0.0 || transform.shearY != 0.0) return null
            val scaleX = transform.scaleX
            val scaleY = transform.scaleY
            if (scaleX <= 0.0 || scaleY <= 0.0) return null

            val key = StripKey(
                area.x, area.y, area.width, area.height, color.rgb,
                scaleXBits = scaleX.toBits(), scaleYBits = scaleY.toBits()
            )
            strips[key]?.let { return it }

            val rendered = renderStrip(area, color, scaleX, scaleY) ?: return null
            strips[key] = rendered
            return rendered
        }

        /**
         * Renders the wave for [area] into an image at device resolution, so blitting
         * it under the same transform reproduces the vector drawing pixel for pixel.
         */
        private fun renderStrip(area: Rectangle, color: Color, scaleX: Double, scaleY: Double): BufferedImage? {
            val deviceWidth = (area.width * scaleX).toInt().coerceAtLeast(1)
            val deviceHeight = (area.height * scaleY).toInt().coerceAtLeast(1)
            if (deviceWidth > MAX_STRIP_WIDTH * 4 || deviceHeight > MAX_STRIP_HEIGHT * 4) return null
            val strip = BufferedImage(deviceWidth, deviceHeight, BufferedImage.TYPE_INT_ARGB)
            val ig = strip.createGraphics()
            try {
                ig.scale(scaleX, scaleY)
                ig.translate(-area.x, -area.y)
                ig.color = color
                ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                ig.draw(createWavePath(area))
            } finally {
                ig.dispose()
            }
            return strip
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
