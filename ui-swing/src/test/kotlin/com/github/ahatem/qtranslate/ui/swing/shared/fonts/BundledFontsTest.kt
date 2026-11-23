package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import com.formdev.flatlaf.fonts.inter.FlatInterFont
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import java.awt.Font
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bundled Arabic and Inter faces register under the family names configuration asks for. */
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
    fun `a lazily registered family resolves through toFont`() {
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
