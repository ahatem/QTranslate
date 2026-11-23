package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.formdev.flatlaf.IntelliJTheme
import com.github.ahatem.qtranslate.ui.swing.testing.SilentLogger
import java.awt.Color
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QTranslateThemeTest {

    @Test
    fun `bundled themes load with the expected name and mode`() {
        assertTheme("themes/qtranslate-light.theme.json", "QTranslate Light", false)
        assertTheme("themes/qtranslate-dark.theme.json", "QTranslate Dark", true)
        assertTheme("themes/ioskeley-light.theme.json", "Ioskeley Light", false)
        assertTheme("themes/ioskeley-dark.theme.json", "Ioskeley Dark", true)
        assertTheme("themes/ioskeley-light-proof.theme.json", "Ioskeley Light Proof", false)
        assertTheme("themes/ioskeley-dark-proof.theme.json", "Ioskeley Dark Proof", true)
    }

    @Test
    fun `missing custom theme resource fails instead of reporting success`() {
        val theme = createCustomTheme("missing", "Missing", false, "themes/missing.theme.json")
        assertFailsWith<IllegalArgumentException> { theme.apply() }
    }

    @Test
    fun `GPL themes are external and saved selections continue to resolve`() {
        assertNull(javaClass.classLoader.getResource("themes/Salmon.theme.json"))
        assertNull(javaClass.classLoader.getResource("themes/clean_sheet.theme.json"))

        val appDataDirectory = createTempDirectory("qtranslate-themes").toFile()
        try {
            val themesDirectory = File(appDataDirectory, "themes").apply { mkdirs() }
            File(repositoryRoot, "themes/Salmon.theme.json")
                .copyTo(File(themesDirectory, "Salmon.theme.json"))
            File(repositoryRoot, "themes/clean_sheet.theme.json")
                .copyTo(File(themesDirectory, "clean_sheet.theme.json"))

            val manager = ThemeManager(appDataDirectory, SilentLogger)

            val salmon = manager.findThemeById("custom:salmon_light")
            assertEquals("external:salmon.theme", salmon.id)
            assertEquals("Salmon", salmon.name)
            assertEquals(false, salmon.isDark)

            val cleanSheet = manager.findThemeById("custom:clean_sheet")
            assertEquals("external:clean_sheet.theme", cleanSheet.id)
            assertEquals("Clean Sheet", cleanSheet.name)
            assertEquals(false, cleanSheet.isDark)
        } finally {
            appDataDirectory.deleteRecursively()
        }
    }

    /** Colours come from the theme files themselves, so a palette edit that drops below a floor fails here. */
    @Test
    fun `bundled theme colours meet their contrast floors`() {
        fun surfaces(vararg names: String) = names.flatMap {
            listOf(Floor("mutedForeground", it, 4.5), Floor("disabledForeground", it, 3.0), Floor("foreground", it, 7.0))
        }

        val cases = mapOf(
            "qtranslate-light" to surfaces("canvas", "surface", "surfaceMuted") + listOf(
                Floor("accentForeground", "accent", 4.5),
                Floor("bronze", "canvas", 4.5),
                Floor("link", "canvas", 4.5),
                Floor("foreground", "selection", 7.0),
                Floor("success", "canvas", 4.5),
                Floor("warning", "canvas", 4.5),
                Floor("error", "canvas", 4.5),
            ),
            "qtranslate-dark" to surfaces("canvas", "surface", "surfaceRaised") + listOf(
                Floor("accentForeground", "accent", 4.5),
                Floor("accent", "canvas", 4.5),
                Floor("link", "canvas", 4.5),
                Floor("foreground", "selection", 4.5),
                Floor("success", "canvas", 4.5),
                Floor("warning", "canvas", 4.5),
                Floor("error", "canvas", 4.5),
            ),
            // Proof Red is a graphic mark, never text, so it only needs the non-text floor.
            "ioskeley-light" to surfaces("canvas", "surface", "surfaceRaised") + listOf(
                Floor("foreground", "selection", 4.5),
                Floor("accent", "canvas", 3.0),
            ),
            "ioskeley-dark" to surfaces("canvas", "surface", "surfaceRaised") + listOf(
                Floor("foreground", "selection", 4.5),
                Floor("accent", "canvas", 3.0),
            ),
        )

        for ((theme, floors) in cases) {
            val colors = themeColors(theme)
            for (floor in floors) {
                val foreground = assertNotNull(colors[floor.foreground], "$theme defines ${floor.foreground}")
                val background = assertNotNull(colors[floor.background], "$theme defines ${floor.background}")
                val ratio = contrastRatio(foreground, background)
                assertTrue(
                    ratio >= floor.minimum,
                    "$theme: ${floor.foreground} on ${floor.background} has contrast $ratio, expected at least ${floor.minimum}"
                )
            }
        }
    }

    private class Floor(val foreground: String, val background: String, val minimum: Double)

    private fun themeColors(name: String): Map<String, Color> {
        val text = assertNotNull(javaClass.classLoader.getResourceAsStream("themes/$name.theme.json")).use {
            it.reader().readText()
        }
        val block = text.substringAfter("\"colors\"").substringAfter("{").substringBefore("}")
        return Regex("""\"(\w+)\"\s*:\s*\"(#[0-9A-Fa-f]{6})\"""").findAll(block)
            .associate { it.groupValues[1] to Color.decode(it.groupValues[2]) }
    }

    private fun assertTheme(resource: String, expectedName: String, expectedDark: Boolean) {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(resource))
        stream.use {
            val laf = IntelliJTheme.createLaf(it)
            assertEquals(expectedName, laf.name)
            assertEquals(expectedDark, laf.isDark)
        }
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

    private val repositoryRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
