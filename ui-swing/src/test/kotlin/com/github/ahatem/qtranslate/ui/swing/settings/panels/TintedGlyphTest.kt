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

/**
 * The row-action squares are painted for every visible row on every repaint — which includes every
 * step of a scroll — and between those rows only the colour differs. Rendering the glyph and
 * recolouring it per square allocated a fresh image each time, so the work is done once per colour
 * that is actually used and then reused.
 *
 * These tests pin that behaviour without timing anything: the source icon counts its own paints,
 * so a cache hit is visible as a paint that never happened.
 */
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
    fun `a colour is rendered once and then reused`() {
        val icon = CountingIcon()
        val glyph = TintedGlyph(icon)

        val first = glyph.forColor(Color(200, 30, 30, 175))
        val second = glyph.forColor(Color(200, 30, 30, 175))

        assertSame(first, second, "the same state gets the same icon back")
        assertEquals(1, icon.paints, "the glyph is rendered once per colour, not once per paint")
    }

    @Test
    fun `each state is its own copy`() {
        val icon = CountingIcon()
        val glyph = TintedGlyph(icon)

        val idle = glyph.forColor(Color(0, 0, 0, 175))
        val hovered = glyph.forColor(Color(0, 0, 0, 255))
        val disabled = glyph.forColor(Color(0, 0, 0, 55))

        assertNotSame(idle, hovered, "hover is brighter, not the idle copy")
        assertNotSame(idle, disabled, "a disabled square is fainter")
        assertEquals(3, icon.paints, "one render per distinct state")
    }

    @Test
    fun `the same colour at a different alpha is a different state`() {
        val icon = CountingIcon()
        val glyph = TintedGlyph(icon)

        val a = glyph.forColor(Color(10, 20, 30, 100))
        val b = glyph.forColor(Color(10, 20, 30, 101))

        assertNotSame(a, b, "alpha is part of the state, so it is part of the key")
        assertEquals(2, icon.paints)
    }

    @Test
    fun `painting a cached glyph does not re-render the source`() {
        val icon = CountingIcon()
        val glyph = TintedGlyph(icon).forColor(Color(30, 90, 200, 200))

        val image = image()
        val g = image.createGraphics()
        try {
            repeat(20) { glyph.paintIcon(null, g, 0, 0) }
        } finally {
            g.dispose()
        }

        assertEquals(1, icon.paints, "twenty paints of the same square cost one render")
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
