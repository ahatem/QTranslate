package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.fonts.inter.FlatInterFont
import com.formdev.flatlaf.util.FontUtils
import java.awt.Font
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeyChipFontTest {
    private val monoFont = Font(Font.MONOSPACED, Font.ITALIC, 12)
    private val uiFont = Font(Font.SANS_SERIF, Font.PLAIN, 13)

    @Test
    fun `Latin unassigned label retains the preferred mono font and covers the full text`() {
        val text = "(none)"
        val chosen = keyChipFont(text, monoFont, uiFont)
        assertSame(monoFont, chosen)
        assertEquals(-1, chosen.canDisplayUpTo(text))
    }

    @Test
    fun `ordinary shortcut tokens retain mono appearance and full coverage`() {
        val preferred = monoFont.deriveFont(Font.PLAIN)
        listOf("Ctrl", "Alt", "Shift", "Q", "F14").forEach { text ->
            val chosen = keyChipFont(text, preferred, uiFont)
            assertSame(preferred, chosen, text)
            assertEquals(-1, chosen.canDisplayUpTo(text), text)
        }
    }

    /** Returns false when the host has no system font that could cover [text] at all. */
    private fun checkLocalizedFallback(text: String): Boolean {
        FlatInterFont.installLazy()
        FontUtils.loadFontFamily(FlatInterFont.FAMILY)
        // The bundled Inter font is a portable missing-glyph case; installed mono families differ
        // between hosts and some already cover CJK.
        val limited = Font(FlatInterFont.FAMILY, Font.ITALIC, 12)
        assertTrue(limited.canDisplayUpTo(text) >= 0, "the preferred font must lack part of $text")
        val compositeUi = FontUtils.getCompositeFont(uiFont.family, limited.style, limited.size)
        if (compositeUi.canDisplayUpTo(text) != -1) return false

        val chosen = keyChipFont(text, limited, uiFont)
        assertEquals(-1, chosen.canDisplayUpTo(text), "chosen font must cover the whole label $text")
        assertEquals(limited.size, chosen.size, text)
        assertEquals(limited.style, chosen.style, text)
        return true
    }

    @Test
    fun `localized unassigned labels fall back to a font covering the whole label`() {
        val checked = listOf("(なし)", "(无)", "(無)").map { checkLocalizedFallback(it) }
        assumeTrue(checked.any { it }, "host has no system font coverage for CJK")
    }
}
