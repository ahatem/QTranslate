package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.ui.swing.shared.util.blend
import java.awt.Color
import java.awt.Font
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.min

/**
 * The four levels a Settings page has to be readable at, expressed once as tokens.
 *
 * The dialog previously used whatever the look and feel happened to hand out: a section heading
 * was the panel's own font one point larger, a sub-heading was the same font shrunk and dimmed,
 * an ordinary label was plain, and an explanation was the *disabled* color at a smaller size.
 * Two of those levels came out within a pixel and a shade of each other, so the page read as one
 * undifferentiated list: the owner could not tell a group heading from the name of a setting, and
 * the explanations sat in a color that either competed with the text above it or all but vanished,
 * depending on which theme was loaded.
 *
 * So the levels are separated on purpose, and the separation is measured rather than guessed:
 *
 * | level              | weight | size    | color                     |
 * |--------------------|--------|---------|---------------------------|
 * | section heading    | bold   | base+1  | primary                   |
 * | sub-section heading| bold   | base    | primary + accent marker   |
 * | setting label      | plain  | base    | primary                   |
 * | explanation        | italic | base-1  | secondary (>= 4.5:1)      |
 *
 * The secondary color is *derived*, not picked: [secondaryText] mutes the primary text color
 * toward the panel background as far as it can while staying above [HINT_MIN_CONTRAST], the WCAG
 * AA ratio for normal-size text. That keeps the explanations visibly quieter than the labels —
 * which is the whole point of them — without letting a dark theme's `Label.disabledForeground`
 * decide, as it did, whether they were loud or invisible.
 */
internal object SettingsVisuals {

    /**
     * WCAG AA for normal-size text, expressed as a contrast ratio.
     *
     * Explanations are one point smaller than body text, so they are not "large text" and the 3:1
     * allowance does not apply to them. They are also, by design, read once and skipped; drifting
     * below this is how explanatory text stops being read at all.
     */
    const val HINT_MIN_CONTRAST = 4.5f

    /** WCAG AA for non-text UI graphics, which is what the accent marker is. */
    const val MARKER_MIN_CONTRAST = 3f

    /** How far a color may be muted toward the background. Past this it stops reading as text. */
    private const val MAX_MUTE = 0.45f

    /** Fineness of the mute search. Twenty-two steps is finer than any screen can show. */
    private const val MUTE_STEP = 0.02f

    /** The color ordinary text is drawn in, and the anchor every other level is measured from. */
    fun primaryText(): Color = UIManager.getColor("Label.foreground") ?: Color(0xDD, 0xDD, 0xDD)

    /** The page's background, which contrast is measured against. */
    fun background(): Color = UIManager.getColor("Panel.background") ?: Color(0x2B, 0x2B, 0x2B)

    /** The theme's accent, which in the application's own identity is the gold. */
    fun accent(): Color = UIManager.getColor("Component.accentColor")
        ?: UIManager.getColor("Component.focusColor")
        ?: Color(0xB8, 0x94, 0x3A)

    /**
     * The explanation color: the primary text color, muted as far as the contrast floor allows.
     *
     * Computed rather than declared because Settings can be themed — the same value has to be
     * comfortably secondary on a near-black canvas and on a near-white one, and a fixed hex value
     * can only be one of those.
     */
    fun secondaryText(): Color = mutedToward(primaryText(), background(), HINT_MIN_CONTRAST)

    /**
     * The accent marker beside a sub-section heading.
     *
     * It is a graphic rather than text, so it only has to clear [MARKER_MIN_CONTRAST]. Where the
     * theme's accent cannot, it is pushed toward the text color — which by definition can — so a
     * pale accent on a pale canvas still leaves a visible mark instead of a suggestion of one.
     */
    fun accentMarker(): Color = atLeast(accent(), background(), MARKER_MIN_CONTRAST)

    /** The hairline that connects a sub-section's settings to its heading. */
    fun rail(): Color = UIManager.getColor("Component.borderColor") ?: Color(0x3D, 0x3D, 0x38)

    /** Body text: the size and weight every other level is a variation of. */
    fun bodyFont(base: Font): Font = base.deriveFont(Font.PLAIN, base.size.toFloat())

    /** A section heading: the largest text on a page, and the only bold-plus-larger one. */
    fun sectionTitleFont(base: Font): Font = base.deriveFont(Font.BOLD, base.size + 1f)

    /**
     * A sub-section heading: bold at body size.
     *
     * Deliberately *not* smaller than body text. Shrinking it was what made it read as a de-emphasised
     * label rather than as the name of a group; a heading earns its rank from weight, a marker and
     * the indentation of what it owns, not from being harder to read than the rows beneath it.
     */
    fun subSectionTitleFont(base: Font): Font = base.deriveFont(Font.BOLD, base.size.toFloat())

    /** An explanation. Smaller and italic, and never so small that it stops being readable. */
    fun hintFont(base: Font): Font = base.deriveFont(Font.ITALIC, base.size - 1f)

    /** WCAG relative luminance of [color]. */
    fun relativeLuminance(color: Color): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /** WCAG contrast ratio between two opaque colors, from 1.0 (identical) to 21.0. */
    fun contrastRatio(first: Color, second: Color): Float {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return ((max(a, b) + 0.05) / (min(a, b) + 0.05)).toFloat()
    }

    /**
     * [foreground] muted toward [background] as far as [minContrast] allows.
     *
     * Walks outward from the untouched color and keeps the last step that still clears the floor,
     * so the result is the *quietest* color that is still readable rather than the first one that
     * happens to be. Returning the untouched color when nothing else clears the floor is the
     * honest failure: a theme with no headroom gets primary-colored explanations, which are plain
     * but legible, instead of grey ones that are not.
     */
    fun mutedToward(foreground: Color, background: Color, minContrast: Float): Color {
        if (contrastRatio(foreground, background) < minContrast) return foreground
        var accepted = foreground
        var amount = MUTE_STEP
        while (amount <= MAX_MUTE) {
            val candidate = foreground.blend(background, amount)
            if (contrastRatio(candidate, background) < minContrast) break
            accepted = candidate
            amount += MUTE_STEP
        }
        return accepted
    }

    /**
     * [color] moved toward [toward] until it clears [minContrast] against [background].
     *
     * Used for graphics, where the theme's own accent is the right hue but not necessarily a
     * visible one on every canvas.
     */
    fun atLeast(color: Color, background: Color, minContrast: Float, toward: Color = primaryText()): Color {
        if (contrastRatio(color, background) >= minContrast) return color
        var amount = MUTE_STEP
        while (amount <= 1f) {
            val candidate = color.blend(toward, amount)
            if (contrastRatio(candidate, background) >= minContrast) return candidate
            amount += MUTE_STEP
        }
        return toward
    }
}
