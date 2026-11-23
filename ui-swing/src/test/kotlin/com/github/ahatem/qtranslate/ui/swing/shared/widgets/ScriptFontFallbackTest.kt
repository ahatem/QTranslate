package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.PortableFallbackFonts
import java.awt.Font
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Asserts what a run's font can draw, since a family named in an attribute proves nothing about coverage. */
class ScriptFontFallbackTest {

    private val primaryFamily = com.formdev.flatlaf.fonts.inter.FlatInterFont.FAMILY
    private val fallbackFamily = NotoNaskhArabicFont.FAMILY

    private val samples = mapOf(
        "Bengali" to "বাংলা ভাষা",
        "Hindi" to "हिन्दी भाषा",
        "Nepali" to "नेपाली भाषा",
        "Thai" to "ภาษาไทย",
    )

    @Test
    fun `every reported script reaches a run whose font can draw it, and Latin stays in the primary`() {
        for ((script, sample) in samples) {
            val pane = ShapedText.pane(sample, width = 400, primary = primaryFamily, fallback = fallbackFamily)

            val doc = ShapedText.onEdt { pane.styledDocument }
            val text = ShapedText.onEdt { doc.getText(0, doc.length) }
            assertEquals(sample, text, "$script: the document text is unchanged")

            val unsupported = (0 until text.length).filter { offset ->
                val family = StyleConstants.getFontFamily(doc.getCharacterElement(offset).attributes)
                Font(family, Font.PLAIN, StyleConstants.getFontSize(doc.getCharacterElement(offset).attributes))
                    .canDisplayUpTo(text.substring(offset, offset + 1)) != -1
            }
            assertTrue(
                unsupported.isEmpty(),
                "$script: ${unsupported.map { text[it] }} are left with no font that can draw them"
            )
        }

        // A script moves to its bundled face while Latin around it stays in the primary.
        val thai = samples.getValue("Thai")
        val text = "Hello $thai world"
        val mixedPane = ShapedText.pane(text, width = 400, primary = primaryFamily, fallback = fallbackFamily)

        val mixedDoc = ShapedText.onEdt { mixedPane.styledDocument }
        val mixedFamilies = (0 until text.length)
            .map { StyleConstants.getFontFamily(mixedDoc.getCharacterElement(it).attributes) }
            .toSet()

        assertEquals(2, mixedFamilies.size, "one face for the Latin and one for the script, not a mixture: $mixedFamilies")
        assertTrue(fallbackFamily !in mixedFamilies, "the Arabic fallback cannot draw Thai and is not used for it")
        assertTrue(PortableFallbackFonts.THAI in mixedFamilies, "the bundled Thai face draws the script")

        assertEquals(primaryFamily, StyleConstants.getFontFamily(mixedDoc.getCharacterElement(0).attributes))
        assertEquals(primaryFamily, StyleConstants.getFontFamily(mixedDoc.getCharacterElement(text.length - 1).attributes))
    }

    @Test
    fun `a font change rescans the document and every script still has a face that draws it`() {
        // Set the pane to a font pair that cannot draw anything, then back to one that can, and
        // confirm the rescan reaches the runs rather than leaving the earlier ones behind.
        val pane = ShapedText.pane(
            samples.getValue("Bengali"),
            width = 400,
            primary = NotoNaskhArabicFont.FAMILY,
            fallback = NotoNaskhArabicFont.FAMILY,
        )
        ShapedText.resize(pane, 400)
        ShapedText.onEdt { pane.updateFontsAndRescanDocument(Font(primaryFamily, Font.PLAIN, 15), Font(fallbackFamily, Font.PLAIN, 15)) }

        val doc = ShapedText.onEdt { pane.styledDocument }
        val text = samples.getValue("Bengali")
        val families = (0 until text.length)
            .map { StyleConstants.getFontFamily(doc.getCharacterElement(it).attributes) }
            .toSet()

        assertTrue(fallbackFamily !in families, "the earlier pair left no run behind")
        assertTrue(PortableFallbackFonts.BENGALI in families, "the rescan moved the script to its bundled face")
    }
}
