package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import java.awt.Font
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Arabic fallback only works if the bundled face is actually registered under the family name
 * the default configuration asks for. Both halves have been wrong before: the fonts shipped in a
 * module whose code never loaded them, and the default fallback pointed at Rubik, which has no
 * Arabic glyphs. Neither failure is visible until someone translates into Arabic on a machine
 * without an Arabic font, which is not a case anyone runs by accident.
 */
class BundledFontsTest {

    @Test
    fun `the bundled Arabic face registers under the name the default configuration uses`() {
        assertTrue(NotoNaskhArabicFont.install(), "both Noto Naskh Arabic styles should load")

        val families = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        assertTrue(
            NotoNaskhArabicFont.FAMILY in families,
            "'${NotoNaskhArabicFont.FAMILY}' should be registered, but was not among the available families"
        )
    }

    @Test
    fun `asking for the family by name yields that font rather than a substitute`() {
        NotoNaskhArabicFont.install()

        // How the editor builds it: FontConfig.toFont() is a plain AWT constructor, which silently
        // substitutes rather than failing when a family is unknown.
        val font = Font(NotoNaskhArabicFont.FAMILY, Font.PLAIN, 15)

        assertEquals(NotoNaskhArabicFont.FAMILY, font.family)
    }

    @Test
    fun `the bundled Arabic face covers Arabic`() {
        NotoNaskhArabicFont.install()
        val font = Font(NotoNaskhArabicFont.FAMILY, Font.PLAIN, 15)

        // "الترجمة" — the word this font exists to render.
        assertEquals(-1, font.canDisplayUpTo("الترجمة"))
    }

    @Test
    fun `the interface face does not cover Arabic, which is why a fallback is configured`() {
        RubikSansFont.installLazy()
        val rubik = Font(RubikSansFont.FAMILY, Font.PLAIN, 15)

        // Guards the reasoning behind the default: if Rubik ever gains Arabic coverage this test
        // fails and the separate fallback can be reconsidered.
        assertTrue(
            rubik.family != RubikSansFont.FAMILY || rubik.canDisplayUpTo("الت") != -1,
            "Rubik unexpectedly covers Arabic"
        )
    }
}
