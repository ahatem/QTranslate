package com.github.ahatem.qtranslate.core.localization


data class ParsedLanguageFile(
    val entries: Map<String, String>,
    val meta: LocalizedLanguageMeta?
)

/**
 * Metadata about a localization file containing information about the language and translation.
 *
 * This data class holds essential information about a translation file that helps with
 * language selection, display, and management in the application.
 *
 * @property name The display name of the language in English (e.g., "Spanish", "Japanese")
 * @property nativeName The name of the language in its own script (e.g., "Español", "日本語")
 * @property locale The IETF BCP 47 language tag for this translation (e.g., "en-US", "es-ES", "ja-JP")
 * @property version The version of the translation file, useful for updates and compatibility
 * @property author The person or organization who created/maintains the translation
 * @property lastUpdate The date when this translation was last updated (ISO format recommended: "2024-01-15")
 * @property isRtl Whether this language uses right-to-left text direction (true for Arabic, Hebrew, etc.)
 */
data class LocalizedLanguageMeta(
    val name: String,
    val nativeName: String,
    val locale: String,
    val version: String,
    val author: String,
    val lastUpdate: String,
    val isRtl: Boolean,
)