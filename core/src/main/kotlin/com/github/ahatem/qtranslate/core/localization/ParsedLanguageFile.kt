package com.github.ahatem.qtranslate.core.localization


data class ParsedLanguageFile(
    val entries: Map<String, String>,
    val meta: LocalizedLanguageMeta?
)

/**
 * How much of the interface a translation covers, against the English strings the application
 * actually asks for.
 *
 * A missing key falls back to English, so an unfinished translation still works. The cost is that
 * the gaps are invisible: nothing on screen distinguishes a language that is fully translated from
 * one that is half English, and the person best placed to finish it never finds out it needs
 * finishing.
 */
data class TranslationCoverage(
    val translated: Int,
    val total: Int
) {
    val missing: Int get() = (total - translated).coerceAtLeast(0)

    val isComplete: Boolean get() = missing == 0

    /**
     * Rounded down, so a translation one string short never reads as 100%. Claiming completeness
     * it has not reached is the one number that would make this worse than showing nothing.
     */
    val percent: Int get() = if (total == 0) 100 else translated * 100 / total
}

/**
 * Metadata about a localization file containing information about the language and translation.
 *
 * This data class holds essential information about a translation file that helps with
 * language selection, display, and management in the application.
 *
 * @property name The display name of the language in English (e.g., "Spanish", "Japanese")
 * @property nativeName The name of the language in its own script (e.g., "Español", "日本語")
 * @property locale The IETF BCP 47 language tag for this translation (e.g., "en-US", "es-ES", "ja-JP")
 * @property translators Everyone who has worked on this translation, oldest first. See below.
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
    val translators: List<String>,
    val isRtl: Boolean,
) {
    /** Empty when the file names nobody, which the UI shows as no credit rather than as "Unknown". */
    val hasTranslators: Boolean get() = translators.isNotEmpty()
}