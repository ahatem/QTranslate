package com.github.ahatem.qtranslate.app.screenshots

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.GradientPaint
import java.awt.Color
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.File
import javax.imageio.ImageIO

/**
 * Lands every capture on one shared publication canvas.
 *
 * The window itself is never stretched to make the gallery uniform — scenes author it at whatever
 * size the layout genuinely reads best at. Uniformity is applied afterwards: the raw window paint
 * is centered on a fixed 2000×1250 (16:10) canvas over a warm gradient matched to the scene's
 * theme, with its corners rounded and a soft shadow beneath it, so the window sits on the
 * backdrop like a product shot rather than being pasted onto it.
 *
 * The gradient tones stay inside the warm-neutral family the Ioskeley/QTranslate palettes already
 * use — a backdrop is grammar, and grammar recedes; no hue, no glow.
 *
 * Everything here is plain AWT: the repo carries no image libraries, and a shadow, a gradient and
 * a rounded clip do not warrant adding one.
 */
internal object ScreenshotCompositor {

    /** The canvas every capture lands on: 16:10, generous margins at 2× capture scale. */
    private const val CANVAS_WIDTH = 2000
    private const val CANVAS_HEIGHT = 1250

    /** Breathing room between the window and the canvas edge. */
    private const val MARGIN = 90

    /** Corner rounding, device pixels at the harness's 2× scale. */
    private const val CORNER_RADIUS = 24f

    /** Shadow blur radius and the downward offset that lifts the window off the backdrop. */
    private const val SHADOW_BLUR = 22
    private const val SHADOW_DROP = 10

    // Dark scenes: near-black canvas sinking to a slightly raised ink. Light scenes: warm paper
    // sinking toward its tint. Top-to-bottom in both, so the light source reads as above.
    private val darkTop = Color(0x101010)
    private val darkBottom = Color(0x1E1D1B)
    private val lightTop = Color(0xF2F0EA)
    private val lightBottom = Color(0xE4E1D8)

    /**
     * Composites [shot] onto the canvas and writes `name.png` into [outputDir].
     *
     * The shot is scaled down to fit the margins when it is larger than the canvas allows —
     * the comparison board and the settings dialog are taller than a main window — and drawn
     * at 1:1 whenever it fits, never upscaled.
     */
    fun write(name: String, shot: BufferedImage, isDark: Boolean, outputDir: File) {
        val canvas = BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

        g.paint = GradientPaint(0f, 0f, if (isDark) darkTop else lightTop,
            0f, CANVAS_HEIGHT.toFloat(), if (isDark) darkBottom else lightBottom)
        g.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
        dither(canvas)

        val availableWidth = CANVAS_WIDTH - 2 * MARGIN
        val availableHeight = CANVAS_HEIGHT - 2 * MARGIN
        val scale = minOf(1f, availableWidth.toFloat() / shot.width, availableHeight.toFloat() / shot.height)
        val width = (shot.width * scale).toInt()
        val height = (shot.height * scale).toInt()
        val x = (CANVAS_WIDTH - width) / 2
        val y = (CANVAS_HEIGHT - height) / 2

        g.drawImage(shadowFor(width, height), x - SHADOW_BLUR * 2,
            y - SHADOW_BLUR * 2 + SHADOW_DROP, null)

        g.clip = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(),
            CORNER_RADIUS, CORNER_RADIUS)
        g.drawImage(shot, x, y, width, height, null)
        g.dispose()

        val target = File(outputDir, "$name.png")
        ImageIO.write(canvas, "png", target)
        println("wrote ${target.name}  ${canvas.width}x${canvas.height} (window ${shot.width}x${shot.height})")
    }

    /**
     * Breaks up gradient banding. The backdrop spans only ~14 tonal levels from edge to edge,
     * and a flat 8-bit render shows each one as a visible stripe across a dark backdrop. A
     * deterministic ±2 noise per pixel (hashed from the position, so regenerations are
     * reproducible) makes the eye read the ramp as continuous.
     */
    private fun dither(canvas: BufferedImage) {
        val width = canvas.width
        val pixels = canvas.getRGB(0, 0, width, canvas.height, null, 0, width)
        for (i in pixels.indices) {
            val noise = ((i * 2654435761L ushr 33) % 5).toInt() - 2
            if (noise == 0) continue
            val p = pixels[i]
            val r = (p ushr 16 and 0xFF) + noise
            val g = (p ushr 8 and 0xFF) + noise
            val b = (p and 0xFF) + noise
            pixels[i] = (0xFF shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
        }
        canvas.setRGB(0, 0, width, canvas.height, pixels, 0, width)
    }

    /**
     * A blurred black plate the size of the window. Drawn offset downward by [SHADOW_DROP], it
     * reads as the shadow the window casts on the backdrop. Built once per shot — the blur is
     * the only expensive step here.
     */
    private fun shadowFor(width: Int, height: Int): BufferedImage {
        val pad = SHADOW_BLUR * 2
        val plate = BufferedImage(width + 2 * pad, height + 2 * pad, BufferedImage.TYPE_INT_ARGB)
        val g = plate.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = Color(0, 0, 0, 90)
        g.fill(RoundRectangle2D.Float(pad.toFloat(), pad.toFloat(), width.toFloat(), height.toFloat(),
            CORNER_RADIUS, CORNER_RADIUS))
        g.dispose()
        // Separable gaussian: one horizontal pass, then one vertical. A 2-D kernel over a
        // full plate would be (2r+1)² taps per pixel for the same result.
        val coefficients = gaussianCoefficients(SHADOW_BLUR)
        val horizontal = ConvolveOp(Kernel(coefficients.size, 1, coefficients), ConvolveOp.EDGE_NO_OP, null)
        val vertical = ConvolveOp(Kernel(1, coefficients.size, coefficients), ConvolveOp.EDGE_NO_OP, null)
        return vertical.filter(horizontal.filter(plate, null), null)
    }

    private fun gaussianCoefficients(radius: Int): FloatArray {
        val sigma = radius / 2.0
        val coefficients = FloatArray(2 * radius + 1)
        var sum = 0f
        for (i in coefficients.indices) {
            val d = i - radius
            coefficients[i] = Math.exp(-(d * d) / (2 * sigma * sigma)).toFloat()
            sum += coefficients[i]
        }
        for (i in coefficients.indices) coefficients[i] /= sum
        return coefficients
    }
}
