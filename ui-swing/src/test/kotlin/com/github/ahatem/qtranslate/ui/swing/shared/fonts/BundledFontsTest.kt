package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import com.formdev.flatlaf.fonts.inter.FlatInterFont
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import java.awt.Font
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Arabic fallback only works if the bundled face is actually registered under the family name
 * a configuration asks for. That half has been wrong before: the fonts shipped in a module whose
 * code never loaded them. Not visible until someone translates into Arabic on a machine without an
 * Arabic font, which is not a case anyone runs by accident.
 *
 * The fallback's own reason for existing is not that Rubik lacks Arabic glyphs -- it turns out the
 * bundled file does carry some -- but that Rubik's Arabic shaping tables are incomplete, which
 * corrupts Swing's own line-breaking and caret placement past certain ligatures. That defect is
 * guarded structurally, by the text pane falling back to its own measurement whenever a run's
 * hit-testing is caught behaving inconsistently (see `WrappingEditorKit`), rather than by a claim
 * about glyph coverage this class used to make and which registering the real font disproves.
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

        // How the editor used to build it, before FontConfig.toFont() started loading a bundled
        // family on demand: a plain AWT constructor, which silently substitutes rather than failing
        // when a family is unknown.
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

    // -------------------------------------------------------------------------
    // FontConfig.toFont() — the resolution boundary, P10-C2
    // -------------------------------------------------------------------------

    @Test
    fun `a lazily registered family resolves through toFont without an eager install`() {
        // Only the lazy loader, the same call normal startup makes — no NotoNaskhArabicFont#install.
        NotoNaskhArabicFont.installLazy()

        val font = FontConfig(NotoNaskhArabicFont.FAMILY, 15).toFont()

        assertEquals(NotoNaskhArabicFont.FAMILY, font.family)
        assertEquals(-1, font.canDisplayUpTo("الترجمة"))
    }

    @Test
    fun `the default UI and editor family resolves through toFont`() {
        FlatInterFont.installLazy()

        val font = FontConfig(FlatInterFont.FAMILY, 15).toFont()

        assertEquals(FlatInterFont.FAMILY, font.family)
        assertEquals(-1, font.canDisplayUpTo("The quick brown fox"))
    }

    @Test
    fun `automatic fallback resolves to a logical font that covers common scripts`() {
        val font = FontConfig(FontConfig.AUTOMATIC, 15).toFont()

        assertEquals(Font.SANS_SERIF, font.family)
        assertEquals(-1, font.canDisplayUpTo("Automatic fallback"))
    }
}
