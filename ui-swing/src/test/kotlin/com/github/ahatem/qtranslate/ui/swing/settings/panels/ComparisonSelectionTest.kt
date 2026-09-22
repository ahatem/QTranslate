package com.github.ahatem.qtranslate.ui.swing.settings.panels

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `toggling appends selections and removes without reordering survivors`() {
        val selected = listOf("deepl", "bing")
        val withOpenAi = toggleComparisonId(selected, "openai", selected = true)
        assertEquals(listOf("deepl", "bing", "openai"), withOpenAi)
        assertEquals(listOf("deepl", "openai"), toggleComparisonId(withOpenAi, "bing", selected = false))
    }
}
