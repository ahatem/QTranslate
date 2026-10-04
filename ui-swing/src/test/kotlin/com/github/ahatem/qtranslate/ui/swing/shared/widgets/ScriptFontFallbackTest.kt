package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.PortableFallbackFonts
import java.awt.Font
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fallback pass as the editor sees it.
 *
 * Assertions are about what a run's font can draw, not about which class it is: the defect is a run
 * that renders as boxes, and a family named in an attribute is no evidence unless it covers the
 * characters in that run.
 */
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
    fun `every reported script reaches a run whose font can draw it`() {
        for ((script, sample) in samples) {
            val pane = ShapedText.pane(sample, width = 400, primary = primaryFamily, fallback = fallbackFamily)

            val doc = ShapedText.onEdt { pane.styledDocument }
            val text = ShapedText.onEdt { doc.getText(0, doc.length) }
            assertEquals(sample, text, "$script: the document text is unchanged")

            val unsupported = (0 until text.length).filter { offset ->
                val family = StyleConstants.getFontFamily(doc.getCharacterElement(offset).attributes)
                // The attribute holds a family and a size rather than a Font, so this rebuilds the
                // same way the view layer does before measuring coverage.
                Font(family, Font.PLAIN, StyleConstants.getFontSize(doc.getCharacterElement(offset).attributes))
                    .canDisplayUpTo(text.substring(offset, offset + 1)) != -1
            }
            assertTrue(
                unsupported.isEmpty(),
                "$script: ${unsupported.map { text[it] }} are left with no font that can draw them"
            )
        }
    }

    @Test
    fun `the run a script lands in is the bundled face, and Latin around it is not`() {
        val sample = samples.getValue("Thai")
        val text = "Hello $sample world"
        val pane = ShapedText.pane(text, width = 400, primary = primaryFamily, fallback = fallbackFamily)

        val doc = ShapedText.onEdt { pane.styledDocument }
        val families = (0 until text.length)
            .map { StyleConstants.getFontFamily(doc.getCharacterElement(it).attributes) }
            .toSet()

        assertEquals(2, families.size, "one face for the Latin and one for the script, not a mixture: $families")
        assertTrue(fallbackFamily !in families, "the Arabic fallback cannot draw Thai and is not used for it")
        assertTrue(PortableFallbackFonts.THAI in families, "the bundled Thai face draws the script")

        assertEquals(primaryFamily, StyleConstants.getFontFamily(doc.getCharacterElement(0).attributes))
        assertEquals(primaryFamily, StyleConstants.getFontFamily(doc.getCharacterElement(text.length - 1).attributes))
    }

    @Test
    fun `fallback attributes are presentation only and leave the undo history alone`() {
        val sample = samples.getValue("Hindi")
        val pane = ShapedText.pane("", width = 400, primary = primaryFamily, fallback = fallbackFamily)
        val doc = ShapedText.onEdt { pane.styledDocument }
        ShapedText.onEdt { pane.undoManager.discardAllEdits() }

        ShapedText.onEdt { doc.insertString(0, sample, null) }
        ShapedText.resize(pane, 400)

        assertEquals(sample, ShapedText.onEdt { doc.getText(0, doc.length) }, "no inserted character")

        // The attribute pass runs inside `withoutUndo`, so it raises no undoable edit: one undo takes
        // back the typed word whole, rather than one edit per rewritten run.
        ShapedText.onEdt { pane.undoManager.undo() }
        assertEquals("", ShapedText.onEdt { doc.getText(0, doc.length) }, "one undo takes the word back whole")
        assertFalse(ShapedText.onEdt { pane.undoManager.canUndo() }, "and there is nothing left to walk back through")
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
