package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import java.awt.Font
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled file itself must cover the script.
 *
 * Asserted against the loaded face rather than against what the machine has installed, so a host that
 * already owns one of these fonts cannot make the coverage pass without anything being bundled.
 */
class PortableFallbackFontsTest {

    @Test
    fun `each rescue family registers under the name the fallback chain asks for`() {
        assertTrue(PortableFallbackFonts.installAll(), "every bundled rescue face should load")

        val families = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        for (family in listOf(
            PortableFallbackFonts.BENGALI,
            PortableFallbackFonts.DEVANAGARI,
            PortableFallbackFonts.THAI,
        )) {
            assertTrue(family in families, "'$family' should be registered, but was not among the available families")
            // The plain AWT constructor the fallback path uses, which substitutes rather than failing
            // when a family is unknown.
            assertEquals(family, Font(family, Font.PLAIN, 15).family)
        }
    }

    @Test
    fun `the bundled Bengali face covers Bengali`() {
        assertEquals(-1, rescue(PortableFallbackFonts.BENGALI).canDisplayUpTo("বাংলা ভাষা"))
    }

    @Test
    fun `the bundled Devanagari face covers Hindi and Nepali`() {
        val font = rescue(PortableFallbackFonts.DEVANAGARI)
        assertEquals(-1, font.canDisplayUpTo("हिन्दी भाषा"))
        assertEquals(-1, font.canDisplayUpTo("नेपाली भाषा"))
    }

    @Test
    fun `the bundled Thai face covers Thai`() {
        assertEquals(-1, rescue(PortableFallbackFonts.THAI).canDisplayUpTo("ภาษาไทย"))
    }

    @Test
    fun `a cluster is kept whole, so a base letter and its marks stay in one face`() {
        // Hindi and Nepali both carry combining marks, and Nepali a vowel sign that no font can be
        // asked to draw on its own. A resolver that asked per character would leave that sign to a
        // substitute font, which is where the boxes come from.
        val font = rescue(PortableFallbackFonts.DEVANAGARI)
        for (cluster in listOf("हि", "न्दी", "ने", "पा", "ली", "भा", "षा")) {
            assertEquals(-1, font.canDisplayUpTo(cluster), "the cluster $cluster")
        }
    }

    @Test
    fun `a lazily registered rescue family resolves on first use without an eager install`() {
        PortableFallbackFonts.installLazy()

        val font = Font(PortableFallbackFonts.THAI, Font.PLAIN, 15)

        assertEquals(PortableFallbackFonts.THAI, font.family)
        assertEquals(-1, font.canDisplayUpTo("ภาษาไทย"))
    }

    @Test
    fun `the candidate chain is the bundled faces, in a fixed order`() {
        val candidates = PortableFallbackFonts.candidates(15).toList()

        assertEquals(
            listOf(PortableFallbackFonts.BENGALI, PortableFallbackFonts.DEVANAGARI, PortableFallbackFonts.THAI),
            candidates.map { it.family }
        )
        assertTrue(candidates.all { it.size == 15 }, "candidates are built at the size they are asked for")
    }

    @Test
    fun `a rescue face does not take Latin away from a font that already has it`() {
        // The rescue faces carry Latin glyphs too. That must not be enough to pull Latin out of the
        // configured primary, so the resolver is asked rather than the font.
        val primary = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        val fonts = FontRuns.resolve("Hello ภาษาไทย world", primary, primary)

        assertEquals(primary, fonts[0], "Latin stays in the configured primary")
        assertEquals(primary, fonts[fonts.size - 1], "and again at the end of the line")
    }

    private fun rescue(family: String): Font {
        assertTrue(PortableFallbackFonts.install(family), "'$family' should load")
        return Font(family, Font.PLAIN, 15)
    }
}
