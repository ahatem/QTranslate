package com.github.ahatem.qtranslate.ui.swing.settings.panels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ComparisonSelectionTest {
    @Test
    fun `primary is excluded while unavailable ids remain removable`() {
        val options = comparisonChooserOptions(
            available = listOf(
                ComparisonServiceOption("google", "Google", true),
                ComparisonServiceOption("deepl", "DeepL", true),
                ComparisonServiceOption("bing", "Bing", true)
            ),
            selectedIds = listOf("missing", "google", "bing"),
            primaryId = "google",
            unavailableName = "Unavailable service"
        )

        assertEquals(listOf("missing", "deepl", "bing"), options.map { it.id })
        assertEquals("Unavailable service", options.first().name)
        assertEquals(false, options.first().available)
    }

    @Test
    fun `rtl popup aligns its right edge with the chooser`() {
        assertEquals(0, comparisonPopupX(120, 120, isLeftToRight = false))
        assertEquals(-80, comparisonPopupX(120, 200, isLeftToRight = false))
        assertEquals(0, comparisonPopupX(120, 200, isLeftToRight = true))
    }

    @Test
    fun `toggling appends selections and removes without reordering survivors`() {
        val selected = listOf("deepl", "bing")
        val withOpenAi = toggleComparisonId(selected, "openai", selected = true)
        assertEquals(listOf("deepl", "bing", "openai"), withOpenAi)
        assertEquals(listOf("deepl", "openai"), toggleComparisonId(withOpenAi, "bing", selected = false))
    }
}
