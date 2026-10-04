package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import com.formdev.flatlaf.fonts.inter.FlatInterFont
import com.formdev.flatlaf.util.FontUtils
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.FontRuns
import java.awt.Font
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The order of the fallback chain.
 *
 * Measured against deliberately limited font pairs: the default Inter plus the logical automatic
 * fallback cover none of the reported scripts, so anything that renders came from a bundled face
 * rather than from the host's own fonts.
 */
class FontRunsPriorityTest {

    private val bengali = "বাংলা ভাষা"
    private val hindi = "हिन्दी भाषा"
    private val nepali = "नेपाली भाषा"
    private val thai = "ภาษาไทย"

    private val samples = mapOf(
        "Bengali" to (bengali to PortableFallbackFonts.BENGALI),
        "Hindi" to (hindi to PortableFallbackFonts.DEVANAGARI),
        "Nepali" to (nepali to PortableFallbackFonts.DEVANAGARI),
        "Thai" to (thai to PortableFallbackFonts.THAI),
    )

    /** The part of a sample that is script rather than the space a phrase is written with. */
    private val scriptOnly = Regex("""[\p{L}\p{M}]+""")

    private fun nonSpace(text: String): IntArray = text.indices.filter { !text[it].isWhitespace() }.toIntArray()

    @BeforeTest
    fun installBundledFaces() {
        FlatInterFont.installLazy()
        FontUtils.getCompositeFont(FlatInterFont.FAMILY, Font.PLAIN, 15)
        assertTrue(PortableFallbackFonts.installAll(), "the bundled rescue faces should load")
    }

    /** The bundled face at the size the resolver hands it out at, so identity comparisons hold. */
    private fun rescue(family: String): Font =
        PortableFallbackFonts.candidates(15).first { it.family == family }

    // ---------------------------------------------------------------------------------------------
    // A. The configured primary wins
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `text the primary can draw stays in the primary`() {
        val primary = Font(FlatInterFont.FAMILY, Font.PLAIN, 15)
        val fonts = FontRuns.resolve("The quick brown fox", primary, primary)

        assertTrue(fonts.all { it === primary }, "every cluster stays in the primary")
    }

    // ---------------------------------------------------------------------------------------------
    // B. The configured fallback wins before any rescue face
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a cluster the configured fallback can draw uses the fallback, not a rescue face`() {
        val primary = Font(FlatInterFont.FAMILY, Font.PLAIN, 15)
        val fallback = rescue(PortableFallbackFonts.THAI)

        val fonts = FontRuns.resolve(thai, primary, fallback)

        assertTrue(fonts.all { it === fallback }, "Thai is drawn by the configured fallback, not by a rescue face")
    }

    @Test
    fun `a configured Arabic fallback still draws Arabic, and leaves the rest to the rescue chain`() {
        // The scenario from the appearance setting: Inter primary, Noto Naskh Arabic fallback.
        val primary = Font(FlatInterFont.FAMILY, Font.PLAIN, 15)
        val fallback = Font(NotoNaskhArabicFont.FAMILY, Font.PLAIN, 15)
        val arabic = "الترجمة"

        val arabicFonts = FontRuns.resolve(arabic, primary, fallback)
        assertTrue(arabicFonts.all { it === fallback }, "Arabic keeps the user's configured fallback")

        val thaiFonts = FontRuns.resolve(thai, primary, fallback)
        assertTrue(
            thaiFonts.all { it?.family == PortableFallbackFonts.THAI },
            "Thai moves to the rescue face rather than to the Arabic fallback"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // C. The rescue chain is reached only after both configured fonts have failed
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a script neither configured font can draw reaches the bundled rescue face`() {
        val primary = Font(FlatInterFont.FAMILY, Font.PLAIN, 15)
        val fallback = Font(NotoNaskhArabicFont.FAMILY, Font.PLAIN, 15)

        for ((script, sampleAndRescue) in samples) {
            val (sample, rescueFamily) = sampleAndRescue
            val fonts = FontRuns.resolve(sample, primary, fallback)

            assertEquals(
                sample.length,
                fonts.size,
                "$script: every character resolved to a face"
            )
            assertTrue(
                fonts.none { it == null },
                "$script: neither configured font nor a rescue face covers the sample"
            )
            val drawn = nonSpace(sample).map { fonts[it] }.toSet()
            assertEquals(
                setOf(rescue(rescueFamily)),
                drawn,
                "$script is drawn by one bundled face, and by no substitute"
            )
        }
    }

    @Test
    fun `each rescue face covers its own script`() {
        assertEquals(-1, rescue(PortableFallbackFonts.BENGALI).canDisplayUpTo(bengali))
        assertEquals(-1, rescue(PortableFallbackFonts.DEVANAGARI).canDisplayUpTo(hindi))
        assertEquals(-1, rescue(PortableFallbackFonts.DEVANAGARI).canDisplayUpTo(nepali))
        assertEquals(-1, rescue(PortableFallbackFonts.THAI).canDisplayUpTo(thai))
    }

    // ---------------------------------------------------------------------------------------------
    // D. Latin stays in the primary in mixed text
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `Latin stays in the primary while a script moves to its rescue face`() {
        val primary = Font(FlatInterFont.FAMILY, Font.PLAIN, 15)
        val fallback = Font(NotoNaskhArabicFont.FAMILY, Font.PLAIN, 15)

        for ((script, sampleAndRescue) in samples) {
            val (sample, rescueFamily) = sampleAndRescue
            val text = "Hello $sample world"
            val fonts = FontRuns.resolve(text, primary, fallback)
            val from = "Hello ".length
            val until = from + sample.length

            assertEquals(primary, fonts[0], "$script: the word before the script stays in the primary")
            assertEquals(primary, fonts[text.length - 1], "$script: the word after it too")
            assertEquals(
                setOf(rescueFamily),
                scriptOnly.findAll(sample).flatMap { match ->
                    (from + match.range.first until from + match.range.last + 1).map { fonts[it]!!.family }
                }.toSet(),
                "$script: only the script itself moves"
            )
            assertTrue(
                fonts.sliceArray(0 until from).all { it === primary },
                "$script: the Latin and the space before the script stay in the primary"
            )
            assertTrue(
                fonts.sliceArray(until until fonts.size).all { it === primary },
                "$script: and so does the space and the word after it"
            )
        }
    }

    @Test
    fun `a mixed sentence keeps the Latin runs and the script run in separate faces`() {
        val primary = Font(FlatInterFont.FAMILY, Font.PLAIN, 15)
        val text = "Hello ภาษาไทย world"

        val fonts = FontRuns.resolve(text, primary, primary)
        val drawn = FontRuns.distinct(fonts)

        assertEquals(2, drawn.size, "two faces in the line: $drawn")
        assertEquals(primary, drawn[0], "the primary draws the Latin around it")
        assertEquals(PortableFallbackFonts.THAI, drawn[1].family, "the bundled Thai face draws the script")
        assertTrue(
            drawn.drop(1).all { it === drawn[1] },
            "and the whole script is that one face, not a face per character"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // E. Nothing is left unresolved
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the reported scripts all resolve under a deliberately limited pair`() {
        val primary = Font(FlatInterFont.FAMILY, Font.PLAIN, 15)
        val fallback = Font(NotoNaskhArabicFont.FAMILY, Font.PLAIN, 15)

        for ((script, sampleAndRescue) in samples) {
            val (sample, _) = sampleAndRescue
            val fonts = FontRuns.resolve(sample, primary, fallback)
            assertTrue(fonts.none { it == null }, "$script: no cluster is left to a substitute font")
        }
    }

    @Test
    fun `a grapheme cluster is never split across two faces`() {
        val primary = Font(FlatInterFont.FAMILY, Font.PLAIN, 15)
        // Devanagari's clusters are base letter plus marks; neither part is drawable by the primary.
        val text = "हिन्दी"

        val fonts = FontRuns.resolve(text, primary, primary)

        assertEquals(text.length, fonts.size)
        assertTrue(
            fonts.all { it?.family == PortableFallbackFonts.DEVANAGARI },
            "every character of the word shares one face"
        )
    }
}
