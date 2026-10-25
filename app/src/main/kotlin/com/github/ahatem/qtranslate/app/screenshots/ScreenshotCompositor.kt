package com.github.ahatem.qtranslate.app.screenshots

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.GradientPaint
import java.awt.RadialGradientPaint
import java.awt.Color
import java.awt.Font
import java.awt.MultipleGradientPaint
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.File
import java.text.AttributedString
import javax.imageio.ImageIO
import javax.swing.UIManager
import kotlin.math.min

/**
 * Lands every capture on one shared publication canvas.
 *
 * The window itself is never stretched to make the gallery uniform — scenes author it at whatever
 * size the layout genuinely reads best at. Uniformity is applied afterwards: the raw window paint
 * is centered on a fixed 2000×1250 (16:10) canvas over a warm gradient matched to the scene's
 * theme, with its corners rounded and a soft shadow beneath it, so the window sits on the
 * backdrop like a product shot rather than being pasted onto it.
 *
 * The backdrop is dressed as a proofing plate, after the identity the themes themselves come
 * from: a soft pool of light behind the window gives the shot depth, hairline registration ticks
 * sit in the canvas corners, and under the window runs a measurement rule with a single Proof Red
 * baseline tick beside a small tracked caption. Everything stays in the warm-neutral grammar
 * tones — Proof Red appears once per plate, marking the baseline, exactly as governed.
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
    private const val SHADOW_BLUR = 22
    private const val SHADOW_DROP = 10

    // Dark scenes: near-black canvas sinking to a slightly raised ink. Light scenes: warm paper
    // sinking toward its tint. Top-to-bottom in both, so the light source reads as above.
    private val darkTop = Color(0x101010)
    private val darkBottom = Color(0x1E1D1B)
    private val lightTop = Color(0xF2F0EA)
    private val lightBottom = Color(0xE4E1D8)

    // Graphics tones: the hairline ticks and rules are non-essential decoration, which the
    // identity lets recede below the text floor, so they use the guide tone. The caption is
    // readable text and uses the identity's readable grammar tone instead — 4.5:1 or better on
    // the canvas: #7E7C77 is 4.6:1 on #101010, #6E6B65 is 4.7:1 on #F2F0EA. The Proof Red tick
    // is a graphic mark, held at or above 3:1 (3.5:1 dark, 6.5:1 light).
    private val darkGuide = Color(0x4A4843)
    private val lightGuide = Color(0xBDBAB2)
    private val darkCaption = Color(0x7E7C77)
    private val lightCaption = Color(0x6E6B65)
    private val darkProofRed = Color(0xB04A43)
    private val lightProofRed = Color(0x913832)

    private const val CORNER_TICK_INSET = 44
    private const val CORNER_TICK_ARM = 26
    private const val BASELINE_RULE_DROP = 62
    private const val CAPTION_DROP = 30
    private const val CAPTION_SIZE = 22

    /**
     * Composites [shot] onto the canvas and writes `name.png` into [outputDir].
     *
     * The shot is scaled down to fit the margins when it is larger than the canvas allows —
     * the comparison board and the settings dialog are taller than a main window — and drawn
     * at 1:1 whenever it fits, never upscaled.
     */
    fun write(name: String, shot: BufferedImage, isDark: Boolean, outputDir: File) {
        val guide = if (isDark) darkGuide else lightGuide
        val captionTone = if (isDark) darkCaption else lightCaption
        val proofRed = if (isDark) darkProofRed else lightProofRed

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

        lightPool(g, x, y, width, height, isDark)
        g.drawImage(shadowFor(width, height), x - SHADOW_BLUR * 2,
            y - SHADOW_BLUR * 2 + SHADOW_DROP, null)
        // The window is kept exactly as the app painted it — square corners, crisp edge.
        g.drawImage(shot, x, y, width, height, null)

        cornerTicks(g, guide)
        val ruleY = baselineRule(g, x, y + height, width, guide, proofRed)
        caption(g, name, x, ruleY, captionTone)

        g.dispose()

        val target = File(outputDir, "$name.png")
        ImageIO.write(canvas, "png", target)
        println("wrote ${target.name}  ${canvas.width}x${canvas.height} (window ${shot.width}x${shot.height})")
    }

    /**
     * A soft pool of light behind the window, so the shot sits in depth rather than on a flat
     * wall. Warm and faint in both themes — on the dark canvas it lifts the ink, on the paper it
     * reads as a slightly brighter sheet under the window.
     */
    private fun lightPool(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, isDark: Boolean) {
        val radius = (maxOf(width, height) * 0.72f + 160f)
        val centerColor = if (isDark) Color(0xEF, 0xE8, 0xD8, 26) else Color(255, 255, 255, 120)
        val glow = RadialGradientPaint(
            java.awt.geom.Point2D.Float(x + width / 2f, y + height / 2f), radius,
            floatArrayOf(0f, 1f),
            arrayOf(centerColor, Color(0, 0, 0, 0)),
            MultipleGradientPaint.CycleMethod.NO_CYCLE
        )
        g.paint = glow
        g.fillRect(x - radius.toInt(), y - radius.toInt(),
            width + 2 * radius.toInt(), height + 2 * radius.toInt())
    }

    /** Hairline registration ticks in the four canvas corners, the way a proof plate is marked. */
    private fun cornerTicks(g: Graphics2D, grammar: Color) {
        g.paint = grammar
        val inset = CORNER_TICK_INSET
        val arm = CORNER_TICK_ARM
        val right = CANVAS_WIDTH - inset
        val bottom = CANVAS_HEIGHT - inset
        // Top-left, top-right, bottom-left, bottom-right Ls.
        g.drawLine(inset, inset, inset + arm, inset)
        g.drawLine(inset, inset, inset, inset + arm)
        g.drawLine(right - arm, inset, right, inset)
        g.drawLine(right, inset, right, inset + arm)
        g.drawLine(inset, bottom, inset + arm, bottom)
        g.drawLine(inset, bottom - arm, inset, bottom)
        g.drawLine(right - arm, bottom, right, bottom)
        g.drawLine(right, bottom - arm, right, bottom)
    }

    /**
     * A measurement rule under the window with one Proof Red tick on it — the plate's single
     * chromatic mark, the way the identity's baseline registration reads. The tick aligns with
     * the window's leading edge, so it marks the shot itself, not the canvas. Returns the rule's
     * y so the caption can hang from the same measurement line.
     */
    private fun baselineRule(g: Graphics2D, windowX: Int, windowBottom: Int, windowWidth: Int,
                             grammar: Color, proofRed: Color): Int {
        val y = minOf(windowBottom + BASELINE_RULE_DROP, CANVAS_HEIGHT - 82)
        g.paint = grammar
        g.drawLine(windowX, y, windowX + windowWidth, y)
        g.paint = proofRed
        g.fillRect(windowX, y - 8, 4, 17)
        return y
    }

    /** Small tracked micro-caption under the rule, aligned to the window like plate metadata. */
    private fun caption(g: Graphics2D, name: String, windowX: Int, ruleY: Int, tone: Color) {
        val text = "QTRANSLATE · ${name.uppercase().replace("-", " · ")} · 2×"
        // The app's own interface font, not a harness constant: the plate metadata matches the
        // product whatever face or platform a capture machine uses. Sized to micro type.
        val uiFont: java.awt.Font? = UIManager.getFont("defaultFont") ?: UIManager.getFont("Label.font")
        val font = if (uiFont != null) Font(uiFont.family, Font.PLAIN, CAPTION_SIZE)
                   else Font(Font.MONOSPACED, Font.PLAIN, CAPTION_SIZE)
        val attributed = AttributedString(text, mapOf(
            TextAttribute.FONT to font,
            TextAttribute.FOREGROUND to tone,
            TextAttribute.TRACKING to 0.18f
        ))
        g.paint = tone
        g.drawString(attributed.iterator, windowX.toFloat(), (ruleY + CAPTION_DROP + 8).toFloat())
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
            val gr = (p ushr 8 and 0xFF) + noise
            val b = (p and 0xFF) + noise
            pixels[i] = (0xFF shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (gr.coerceIn(0, 255) shl 8) or
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
        g.fillRect(pad, pad, width, height)
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
