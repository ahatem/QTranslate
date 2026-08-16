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
 * @property authors Everyone who has worked on this translation, oldest first. See below.
 * @property isRtl Whether this language uses right-to-left text direction (true for Arabic, Hebrew, etc.)
 */
data class LocalizedLanguageMeta(
    val name: String,
    val nativeName: String,
    val locale: String,
    /**
     * GitHub handles, in the order they were added.
     *
     * A list because a translation outlives its first author. The single `author` field it
     * replaced forced everyone after the first to either erase the previous name or leave
     * themselves out, and the contribution history shows both happening: two pull requests
     * replaced the existing name, another updated a translation and never touched the field, so
     * the work was credited to someone else.
     *
     * Handles rather than names and addresses. A handle is unique, links to a profile, and keeps
     * personal email out of a public repository where it is only a spam harvest and is already
     * recorded in the Git history anyway.
     *
     * The older `author` field is still read when this is absent, so a translation file written
     * against the previous format keeps working and keeps crediting whoever it named.
     */
    val authors: List<String>,
    val isRtl: Boolean,
) {
    /** Empty when the file names nobody, which the UI shows as no credit rather than as "Unknown". */
    val hasAuthors: Boolean get() = authors.isNotEmpty()
}