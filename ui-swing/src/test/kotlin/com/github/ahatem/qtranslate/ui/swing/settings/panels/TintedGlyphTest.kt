package com.github.ahatem.qtranslate.ui.swing.settings.panels

import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** The row-action squares are repainted on every scroll step, so each colour's tinted copy is rendered once. */
class TintedGlyphTest {

    /** An icon that counts how often it is asked to draw itself. */
    private class CountingIcon(private val size: Int = 14) : Icon {
        var paints = 0

        override fun getIconWidth() = size
        override fun getIconHeight() = size

        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
            paints++
            g?.color = Color.BLACK
            g?.fillRect(x, y, size, size)
        }
    }

    private fun image() = BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `a glyph is rendered once per distinct colour and alpha, however often it is painted`() {
        val icon = CountingIcon()
        val glyph = TintedGlyph(icon)

        val idle = glyph.forColor(Color(0, 0, 0, 175))
        assertSame(idle, glyph.forColor(Color(0, 0, 0, 175)), "the same state gets the same icon back")
        assertEquals(1, icon.paints)

        val hovered = glyph.forColor(Color(0, 0, 0, 255))
        val disabled = glyph.forColor(Color(0, 0, 0, 55))
        val oneAlphaStepAway = glyph.forColor(Color(0, 0, 0, 176))
        assertNotSame(idle, hovered)
        assertNotSame(idle, disabled)
        assertNotSame(idle, oneAlphaStepAway, "alpha is part of the key")
        assertEquals(4, icon.paints, "one render per distinct state")

        val image = image()
        val g = image.createGraphics()
        try {
            repeat(20) { idle.paintIcon(null, g, 0, 0) }
        } finally {
            g.dispose()
        }
        assertEquals(4, icon.paints, "painting a cached glyph does not re-render the source")
    }

    @Test
    fun `a cached glyph carries the colour it was asked for`() {
        val icon = CountingIcon()
        val glyph = TintedGlyph(icon).forColor(Color(10, 120, 200, 255))

        val image = image()
        val g = image.createGraphics()
        try {
            glyph.paintIcon(null, g, 0, 0)
        } finally {
            g.dispose()
        }

        // The source is solid black; the copy is the theme colour, keeping its own alpha.
        val centre = image.getRGB(7, 7)
        assertEquals(10, (centre shr 16) and 0xFF, "red channel")
        assertEquals(120, (centre shr 8) and 0xFF, "green channel")
        assertEquals(200, centre and 0xFF, "blue channel")
        assertEquals(255, (centre ushr 24) and 0xFF, "alpha")
    }

    @Test
    fun `a theme change drops the cached colours`() {
        val icon = CountingIcon()
        val glyph = TintedGlyph(icon)

        val before = glyph.forColor(Color(0, 0, 0, 175))
        glyph.invalidate()
        val after = glyph.forColor(Color(0, 0, 0, 175))

        assertNotSame(before, after, "the colour may now be a different theme's, so it is redrawn")
        assertEquals(2, icon.paints)
    }
}
