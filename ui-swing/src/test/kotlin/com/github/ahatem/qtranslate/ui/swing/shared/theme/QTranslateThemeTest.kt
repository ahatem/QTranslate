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
        // QTranslate Light — warm parchment; gold carries fills, bronze carries accent text
        assertContrast("#1E1D1A", "#F6F2E9", 7.0)   // body text on canvas
        assertContrast("#1E1D1A", "#FBF8F1", 7.0)   // body text on editor surface
        assertContrast("#5F5B4C", "#F6F2E9", 4.5)   // muted text
        assertContrast("#807B6A", "#F6F2E9", 3.0)   // disabled text
        assertContrast("#1E1D1A", "#B8943A", 4.5)   // label on the gold primary button
        assertContrast("#8A6A1E", "#F6F2E9", 4.5)   // bronze as link and accent text
        assertContrast("#1E1D1A", "#EADFBF", 7.0)   // text over a selection

        // QTranslate Dark — brand gold on near-black
        assertContrast("#BCAC8F", "#0C0C0C", 7.0)   // body text on canvas
        assertContrast("#BCAC8F", "#181714", 7.0)   // body text on editor surface
        assertContrast("#8D8975", "#0C0C0C", 4.5)   // muted text
        assertContrast("#726F5C", "#0C0C0C", 3.0)   // disabled text
        assertContrast("#14120A", "#B8943A", 4.5)   // label on the gold primary button
        assertContrast("#B8943A", "#0C0C0C", 4.5)   // gold as accent text
        assertContrast("#D6B063", "#0C0C0C", 4.5)   // link
        assertContrast("#BCAC8F", "#4A3A18", 4.5)   // text over a selection
    }

    /**
     * Secondary text is drawn on raised surfaces too — menu accelerators, table headers,
     * popups — where there is less contrast available than on the canvas. Checking only
     * against the canvas hides failures on exactly the surfaces where muted text is
     * hardest to read.
     */
    @Test
    fun `muted and disabled text stay readable on every surface`() {
        listOf("#0C0C0C", "#181714", "#201F1A").forEach { surface ->
            assertContrast("#8D8975", surface, 4.5)
            assertContrast("#726F5C", surface, 3.0)
            assertContrast("#BCAC8F", surface, 7.0)
        }
        listOf("#F6F2E9", "#FBF8F1", "#EAE4D6").forEach { surface ->
            assertContrast("#5F5B4C", surface, 4.5)
            assertContrast("#807B6A", surface, 3.0)
            assertContrast("#1E1D1A", surface, 7.0)
        }
    }

    @Test
    fun `status colours are readable on both canvases`() {
        listOf("#4A6B1F", "#8F5714", "#B0342F").forEach {
            assertContrast(it, "#F6F2E9", 4.5)
        }
        listOf("#94AE62", "#E0913C", "#D06B62").forEach {
            assertContrast(it, "#0C0C0C", 4.5)
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
