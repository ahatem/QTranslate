package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.formdev.flatlaf.IntelliJTheme
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QTranslateThemeTest {

    @Test
    fun `bundled QTranslate themes load with the expected mode`() {
        assertTheme("themes/qtranslate-light.theme.json", "QTranslate Light", false)
        assertTheme("themes/qtranslate-dark.theme.json", "QTranslate Dark", true)
    }

    @Test
    fun `platform defaults use QTranslate themes`() {
        assertEquals("custom:qtranslate_light", ThemeManager.platformDefaultLightThemeId())
        assertEquals("custom:qtranslate_dark", ThemeManager.platformDefaultDarkThemeId())
    }

    @Test
    fun `missing custom theme resource fails instead of reporting success`() {
        val theme = createCustomTheme("missing", "Missing", false, "themes/missing.theme.json")
        assertFailsWith<IllegalArgumentException> { theme.apply() }
    }

    @Test
    fun `theme text and primary actions meet WCAG contrast targets`() {
        // QTranslate Light — warm paper
        assertContrast("#2A2521", "#FAF6EF", 7.0)   // body text on canvas
        assertContrast("#2A2521", "#FFFDF9", 7.0)   // body text on input surface
        assertContrast("#6A6158", "#FAF6EF", 4.5)   // muted text
        assertContrast("#8F8677", "#FAF6EF", 3.0)   // disabled text
        assertContrast("#FFFFFF", "#0E7C6B", 4.5)   // label on primary button
        assertContrast("#0E7C6B", "#FAF6EF", 4.5)   // accent used as text
        assertContrast("#2A2521", "#CFE6DF", 7.0)   // text over a selection

        // QTranslate Dark — deep ink
        assertContrast("#E6E4DF", "#10161A", 7.0)
        assertContrast("#E6E4DF", "#161E23", 7.0)
        assertContrast("#97A3A8", "#10161A", 4.5)
        assertContrast("#5F6C71", "#10161A", 3.0)
        assertContrast("#06201C", "#45C4B0", 4.5)
        assertContrast("#45C4B0", "#10161A", 4.5)
        assertContrast("#E6E4DF", "#1E4E4C", 7.0)
    }

    @Test
    fun `status colours are readable on both canvases`() {
        listOf("#1F6FB2", "#2E7D51", "#8F5714", "#B3413C").forEach {
            assertContrast(it, "#FAF6EF", 4.5)
        }
        listOf("#6FB2E8", "#5FC08A", "#E0B15C", "#EF7B84").forEach {
            assertContrast(it, "#10161A", 4.5)
        }
    }

    private fun assertTheme(resource: String, expectedName: String, expectedDark: Boolean) {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(resource))
        stream.use {
            val laf = IntelliJTheme.createLaf(it)
            assertEquals(expectedName, laf.name)
            assertEquals(expectedDark, laf.isDark)
        }
    }

    private fun assertContrast(foreground: String, background: String, minimum: Double) {
        val ratio = contrastRatio(Color.decode(foreground), Color.decode(background))
        assertTrue(ratio >= minimum, "$foreground on $background has contrast $ratio; expected at least $minimum")
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val lighter = maxOf(relativeLuminance(first), relativeLuminance(second))
        val darker = minOf(relativeLuminance(first), relativeLuminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
