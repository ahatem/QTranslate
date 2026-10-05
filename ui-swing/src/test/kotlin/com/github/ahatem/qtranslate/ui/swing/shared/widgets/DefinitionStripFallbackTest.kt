package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.fonts.PortableFallbackFonts
import java.awt.ComponentOrientation
import java.awt.Font
import java.awt.Rectangle
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dictionary definition is a translation too, and it used to be the one text surface with no
 * fallback at all: a plain area that could only ever be drawn in a single font.
 */
class DefinitionStripFallbackTest {

    private val samples = mapOf(
        "Bengali" to "বাংলা ভাষা",
        "Devanagari" to "हिन्दी भाषा",
        "Thai" to "ภาษาไทย",
        "Arabic" to "الترجمة",
    )

    private fun render(definition: String): DefinitionStrip {
        val strip = DefinitionStrip()
        SwingUtilities.invokeAndWait { strip.render(definition) }
        return strip
    }

    private fun textComponent(strip: DefinitionStrip): JTextPane {
        var found: JTextPane? = null
        SwingUtilities.invokeAndWait {
            fun walk(c: java.awt.Container) {
                c.components.forEach { if (it is JTextPane) found = it; if (it is java.awt.Container) walk(it) }
            }
            walk(strip)
        }
        return found!!
    }

    @Test
    fun `a definition in a reported script is drawn by a face that can draw it`() {
        for ((script, sample) in samples) {
            val strip = render(sample)
            val text = textComponent(strip)
            val doc = text.styledDocument

            val unsupported = (0 until sample.length).filter { offset ->
                val attributes = doc.getCharacterElement(offset).attributes
                Font(
                    StyleConstants.getFontFamily(attributes),
                    Font.PLAIN,
                    StyleConstants.getFontSize(attributes),
                ).canDisplayUpTo(sample.substring(offset, offset + 1)) != -1
            }
            assertTrue(
                unsupported.isEmpty(),
                "$script: ${unsupported.map { sample[it] }} are drawn by no font, which is the boxes this strip had"
            )
        }
    }

    @Test
    fun `a reported script moves to its bundled face and keeps Latin in the interface font`() {
        val strip = render("Hello ภาษาไทย world")
        val doc = textComponent(strip).styledDocument
        val text = "Hello ภาษาไทย world"

        val families = (0 until text.length)
            .map { StyleConstants.getFontFamily(doc.getCharacterElement(it).attributes) }
            .toSet()
        assertTrue(PortableFallbackFonts.THAI in families, "the script is drawn by the bundled Thai face")
        assertEquals(2, families.size, "and the Latin keeps the interface face: $families")
    }

    @Test
    fun `a blank definition hides the strip and a nonblank one shows it`() {
        val strip = DefinitionStrip()
        val hidden = arrayOfNulls<Boolean>(1)
        SwingUtilities.invokeAndWait {
            strip.render("   ")
            hidden[0] = strip.isVisible
        }
        assertFalse(hidden[0]!!, "a definition of nothing says nothing")

        val shown = arrayOfNulls<Boolean>(1)
        SwingUtilities.invokeAndWait {
            strip.render(samples.getValue("Thai"))
            shown[0] = strip.isVisible
        }
        assertTrue(shown[0]!!, "a real definition shows the strip")
    }

    @Test
    fun `the strip stays an aside - not editable, and out of the tab order`() {
        val strip = render(samples.getValue("Thai"))
        val text = textComponent(strip)

        assertFalse(text.isEditable, "a definition is not written into")
        assertTrue(text.isFocusable, "a click leaves the caret here so the keyboard can copy")
        assertTrue(
            text.skipsFocusTraversal(),
            "while Tab walks past it: a reader does not tab to a definition"
        )
    }

    @Test
    fun `the definition keeps following its own script for orientation`() {
        val strip = render("الترجمة")
        assertEquals(
            ComponentOrientation.RIGHT_TO_LEFT,
            strip.componentOrientation,
            "an Arabic definition stays right to left"
        )

        val flipped = DefinitionStrip()
        val orientation = arrayOfNulls<ComponentOrientation>(1)
        SwingUtilities.invokeAndWait {
            flipped.render("الترجمة")
            flipped.render(samples.getValue("Thai"))
            orientation[0] = flipped.componentOrientation
        }
        assertEquals(
            ComponentOrientation.LEFT_TO_RIGHT,
            orientation[0],
            "and a Bengali or Thai one puts it back to left to right"
        )
    }

    @Test
    fun `a long definition still wraps, and reports the height it needs`() {
        val strip = DefinitionStrip()
        val sentence = List(12) { samples.getValue("Thai") }.joinToString(" ")
        val height = IntArray(1)
        SwingUtilities.invokeAndWait {
            strip.render(sentence)
            strip.setSize(240, 1000)
            height[0] = strip.preferredSize.height
        }

        assertTrue(height[0] > 40, "a ${sentence.length}-character definition wraps rather than clipping: ${height[0]}px")
        assertEquals(0, strip.preferredSize.width, "the strip asks for the width it is given, not the text's own")

        val oneLine = IntArray(1)
        SwingUtilities.invokeAndWait {
            strip.render(samples.getValue("Thai"))
            strip.setSize(240, 1000)
            oneLine[0] = strip.preferredSize.height
        }
        assertTrue(oneLine[0] < height[0], "a short definition asks for less height than a long one")
    }

    @Test
    fun `the definition text is left exactly as it was given`() {
        val sample = samples.getValue("Devanagari")
        val strip = render(sample)

        assertEquals(sample, textComponent(strip).text, "attributes change how it is drawn, not what it says")
    }
}
