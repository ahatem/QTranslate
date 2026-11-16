package com.github.ahatem.qtranslate.ui.swing.settings.panels

import java.awt.Font
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The numbers behind the Settings hierarchy, tested without a window.
 *
 * The owner's report was that the levels of the page looked alike. Fixing that with hand-picked
 * greys would have made it one theme's opinion, so the rules are measured instead: the explanation
 * is the *quietest* color that still clears WCAG AA, and the accent marker is the quietest that
 * still clears the non-text threshold. These tests hold those rules, not a particular hex value.
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

    /**
     * The two identity themes, checked by name.
     *
     * The palettes above are representative; these are the ones the application actually ships, so
     * the rule is verified against the exact colours a user sees rather than only against samples.
     */
    @Test
    fun `both identity themes give a readable, secondary explanation color`() {
        val themes = mapOf(
            "qtranslate-dark" to (Color(0x18, 0x17, 0x14) to Color(0xBC, 0xAC, 0x8F)),
            "qtranslate-light" to (Color(0xF7, 0xF5, 0xF0) to Color(0x2A, 0x28, 0x23)),
        )
        themes.forEach { (name, palette) ->
            val (background, foreground) = palette
            val explanation = SettingsVisuals.mutedToward(foreground, background, SettingsVisuals.HINT_MIN_CONTRAST)
            val contrast = SettingsVisuals.contrastRatio(explanation, background)
            assertTrue(contrast >= 4.5f, "$name explains itself at $contrast:1, above the 4.5 floor")
            assertTrue(contrast < 7f, "and stays quieter than its labels at $contrast:1")
        }
    }

    @Test
    fun `an explanation is never muted further than the contrast floor allows`() {
        val (background, foreground) = dark
        val explanation = SettingsVisuals.mutedToward(foreground, background, 4.5f)
        // The next step past what was accepted must break the floor, or the search stopped early.
        val oneStepFurther = SettingsVisuals.blend(explanation, background, 0.02f)
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

    @Test
    fun `the four levels differ in weight and size, not only in color`() {
        val base = Font("Dialog", Font.PLAIN, 12)

        val section = SettingsVisuals.sectionTitleFont(base)
        val subSection = SettingsVisuals.subSectionTitleFont(base)
        val body = SettingsVisuals.bodyFont(base)
        val hint = SettingsVisuals.hintFont(base)

        assertTrue(section.isBold, "a section heading is heavier than a setting's name")
        assertTrue(section.size > body.size, "and larger")
        assertTrue(subSection.isBold, "a sub-section heading is heavier too")
        assertEquals(body.size, subSection.size, "but not larger: it ranks below the section, not above the text")
        assertTrue(hint.isItalic, "an explanation is set apart by its italics")
        assertEquals(body.size - 1, hint.size, "and only one point smaller")
    }
}
