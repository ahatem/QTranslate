package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.util.UIScale
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Rectangle

/**
 * How wide a floating popup should be allowed to get.
 *
 * A popup sized purely as a fraction of the screen is wrong at both ends: on a wide monitor it
 * becomes a single line of text stretched across half a metre, which is genuinely hard to read
 * because the eye loses its place on the return sweep — the reason books and well-set web pages
 * cap their measure. And a popup sized purely to its content is a sliver when the content is one
 * word.
 *
 * So the width is bounded by a comfortable line length, measured in characters of the font
 * actually in use, and then by the screen as a safety net for very large text or very small
 * displays.
 */
object PopupSizing {

    /**
     * Characters per line the width aims not to exceed.
     *
     * Typographic advice for a comfortable measure runs from about 45 to 75 characters. The upper
     * end suits a popup, which is read in a glance rather than for minutes at a time.
     */
    private const val IDEAL_LINE_CHARACTERS = 72

    /** Never wider than this share of the screen, whatever the font says. */
    private const val MAX_SCREEN_FRACTION = 0.42

    /** Never taller than this share, so a long translation scrolls instead of filling the screen. */
    private const val MAX_HEIGHT_FRACTION = 0.45

    /**
     * Wide enough that a one-word translation still looks like a considered answer rather than a
     * tooltip that ran out of room.
     */
    private const val MIN_WIDTH = 380

    private const val MIN_HEIGHT = 140

    /**
     * The widest the text area should be drawn, in pixels.
     *
     * Uses the average character width of [font] rather than a fixed pixel count, so the measure
     * holds when the user picks a larger font or a different family.
     */
    fun maxTextWidth(metrics: FontMetrics, screen: Rectangle): Int {
        val averageCharacter = metrics.charWidth('n').coerceAtLeast(1)
        val comfortable = averageCharacter * IDEAL_LINE_CHARACTERS
        val screenCap = (screen.width * MAX_SCREEN_FRACTION).toInt()
        return minOf(comfortable, screenCap).coerceAtLeast(minWidth())
    }

    fun maxHeight(screen: Rectangle): Int = (screen.height * MAX_HEIGHT_FRACTION).toInt()

    /** Scaled, because a fixed pixel floor is a different size on every display. */
    fun minWidth(): Int = UIScale.scale(MIN_WIDTH)

    fun minHeight(): Int = UIScale.scale(MIN_HEIGHT)

    /** Convenience for callers that have a font rather than metrics to hand. */
    fun maxTextWidth(font: Font, metricsProvider: (Font) -> FontMetrics, screen: Rectangle): Int =
        maxTextWidth(metricsProvider(font), screen)
}
