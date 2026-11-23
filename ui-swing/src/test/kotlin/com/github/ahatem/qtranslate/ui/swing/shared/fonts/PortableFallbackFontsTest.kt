package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import java.awt.Font
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Coverage is asserted against the loaded bundled face, not whatever the host has installed. */
class PortableFallbackFontsTest {

    @Test
    fun `each rescue family registers under the name the fallback chain asks for`() {
        assertTrue(PortableFallbackFonts.installAll(), "every bundled rescue face should load")

        val families = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        for (family in listOf(
            PortableFallbackFonts.BENGALI,
            PortableFallbackFonts.DEVANAGARI,
            PortableFallbackFonts.THAI,
        )) {
            assertTrue(family in families, "'$family' should be registered, but was not among the available families")
            assertEquals(family, Font(family, Font.PLAIN, 15).family)
        }
    }

    @Test
    fun `each bundled face covers its script, clusters included`() {
        val cases = listOf(
            Triple("Bengali", PortableFallbackFonts.BENGALI, listOf("বাংলা ভাষা")),
            Triple("Devanagari", PortableFallbackFonts.DEVANAGARI, listOf("हिन्दी भाषा", "नेपाली भाषा", "हि", "न्दी", "ने", "पा", "ली", "भा", "षा")),
            Triple("Thai", PortableFallbackFonts.THAI, listOf("ภาษาไทย")),
        )
        for ((script, family, samples) in cases) {
            val font = rescue(family)
            for (sample in samples) {
                assertEquals(-1, font.canDisplayUpTo(sample), "$script face draws '$sample'")
            }
        }
    }

    @Test
    fun `a lazily registered rescue family resolves on first use without an eager install`() {
        PortableFallbackFonts.installLazy()

        val font = Font(PortableFallbackFonts.THAI, Font.PLAIN, 15)

        assertEquals(PortableFallbackFonts.THAI, font.family)
        assertEquals(-1, font.canDisplayUpTo("ภาษาไทย"))
    }

    private fun rescue(family: String): Font {
        assertTrue(PortableFallbackFonts.install(family), "'$family' should load")
        return Font(family, Font.PLAIN, 15)
    }
}
