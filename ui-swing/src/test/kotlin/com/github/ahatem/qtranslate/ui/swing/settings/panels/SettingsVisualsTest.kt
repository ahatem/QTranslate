package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.ui.swing.shared.util.blend
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Settings hierarchy's colour rules, measured rather than hand-picked: an explanation is the
 * quietest colour that still clears WCAG AA, and the accent marker the quietest that clears the
 * non-text threshold.
 */
class SettingsVisualsTest {

    private val dark = Color(0x18, 0x17, 0x14) to Color(0xBC, 0xAC, 0x8F)
    private val light = Color(0xF7, 0xF5, 0xF0) to Color(0x2A, 0x28, 0x23)
    private val flatDark = Color(0x2B, 0x2B, 0x2B) to Color(0xDF, 0xDF, 0xDF)

    @Test
    fun `contrast ratio matches the wcag reference values`() {
        assertEquals(21f, SettingsVisuals.contrastRatio(Color.BLACK, Color.WHITE), 0.01f)
        assertEquals(1f, SettingsVisuals.contrastRatio(Color.GRAY, Color.GRAY), 0.01f)
    }

    @Test
    fun `an explanation is quieter than its label on every palette, and still readable`() {
        listOf(dark, light, flatDark).forEach { (background, foreground) ->
            val explanation = SettingsVisuals.mutedToward(foreground, background, SettingsVisuals.HINT_MIN_CONTRAST)
            val labelContrast = SettingsVisuals.contrastRatio(foreground, background)
            val explanationContrast = SettingsVisuals.contrastRatio(explanation, background)

            assertTrue(
                explanationContrast >= SettingsVisuals.HINT_MIN_CONTRAST,
                "an explanation stays readable at $explanationContrast:1 on $background",
            )
            assertTrue(
                explanationContrast < labelContrast,
                "and is visibly secondary to its label ($explanationContrast vs $labelContrast on $background)",
            )
        }
    }

    @Test
    fun `an explanation is never muted further than the contrast floor allows`() {
        val (background, foreground) = dark
        val explanation = SettingsVisuals.mutedToward(foreground, background, 4.5f)
        // The next step past what was accepted must break the floor, or the search stopped early.
        val oneStepFurther = explanation.blend(background, 0.02f)
        assertTrue(
            SettingsVisuals.contrastRatio(oneStepFurther, background) < 4.5f,
            "the muted color is the quietest one that still passes, not merely a quiet one",
        )
    }

    @Test
    fun `a palette with no headroom keeps its label color rather than inventing a dim one`() {
        val lowContrast = Color(0x77, 0x77, 0x77) to Color(0x88, 0x88, 0x88)
        val explanation = SettingsVisuals.mutedToward(lowContrast.second, lowContrast.first, 7f)
        assertEquals(lowContrast.second, explanation, "nothing can be muted, so nothing is")
    }

    @Test
    fun `an accent too pale to see is pushed until it is visible`() {
        val background = Color(0xF7, 0xF5, 0xF0)
        val paleAccent = Color(0xE8, 0xD9, 0xA8)
        assertTrue(SettingsVisuals.contrastRatio(paleAccent, background) < SettingsVisuals.MARKER_MIN_CONTRAST)

        val marker = SettingsVisuals.atLeast(paleAccent, background, SettingsVisuals.MARKER_MIN_CONTRAST)
        assertTrue(
            SettingsVisuals.contrastRatio(marker, background) >= SettingsVisuals.MARKER_MIN_CONTRAST,
            "a marker that cannot be seen is not a marker",
        )
    }
}
