package com.github.ahatem.qtranslate.ui.swing.settings.panels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Locales without their own text for the command list's accessibility strings show the embedded
 * English text: never the raw key, an empty label or an unsubstituted `%s`. `ar-SA` ships its own
 * (see [KeyboardPanelArabicCoverageTest]).
 */
class KeyboardPanelLocalizationFallbackTest {

    /** Locales that deliberately carry their own text for these keys. */
    private val translated = setOf("ar-SA")

    private val keys = listOf(
        "settings_hotkeys.column_actions",
        "settings_hotkeys.command_list_accessible_name",
        "settings_hotkeys.row_actions_accessible_name",
        "settings_hotkeys.row_actions_accessible_description",
    )

    @Test
    fun `untranslated locales fall back to the English text and translated ones differ`() {
        val tags = shippedLanguageTags()
        assertTrue(tags.size >= 15, "expected the shipped locales, found $tags")
        val english = shippedLocalizerFor("en")
        for (tag in tags) {
            val localizer = shippedLocalizerFor(tag)
            for (key in keys) {
                val text = localizer.getString(key)
                assertNotEquals(key, text, "$tag leaks the key for $key")
                assertTrue(text.isNotBlank(), "$tag renders an empty label for $key")
                if (tag in translated) {
                    assertNotEquals(
                        english.getString(key),
                        text,
                        "$tag ships its own text for $key and should not fall back to English",
                    )
                } else {
                    assertEquals(
                        english.getString(key),
                        text,
                        "$tag should fall back to the English text for $key",
                    )
                }
            }
        }
    }

    @Test
    fun `the row actions description substitutes the command even when untranslated`() {
        // The template only exists in English, so the argument must still be formatted into it.
        for (tag in shippedLanguageTags()) {
            val text = shippedLocalizerFor(tag)
                .getString("settings_hotkeys.row_actions_accessible_description", "Translate")
            assertTrue(
                text.contains("Translate") && !text.contains("%s"),
                "$tag left the placeholder unformatted: $text",
            )
        }
    }
}
