package com.github.ahatem.qtranslate.plugins.systemocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.util.Locale

/**
 * Picks a concrete language for a backend that cannot choose one itself.
 *
 * This is fallback selection, not detection: nothing here inspects the image. The platform language
 * is tried first because it is the best available hint at what the image probably contains, and the
 * result is always a language the backend reports as installed.
 */
internal object AutoLanguageResolver {

    /**
     * A language from [supported], preferring one that matches [preferredTags].
     *
     * An exact tag match wins over a match on the primary subtag (`en-US` is used for a preferred
     * `en-GB`). Failing that, [supported] is taken ordered by tag, so the result never depends on
     * set iteration order. Returns null when nothing is installed.
     */
    fun resolve(supported: Set<LanguageCode>, preferredTags: List<String>): LanguageCode? {
        if (supported.isEmpty()) return null

        val ordered = supported.sortedBy { it.tag }
        for (tag in preferredTags) {
            val preferred = runCatching { LanguageCode(tag.trim()) }.getOrNull() ?: continue
            val primary = preferred.tag.substringBefore('-')

            ordered.firstOrNull { it == preferred }?.let { return it }
            ordered.firstOrNull { it.tag.substringBefore('-') == primary }?.let { return it }
        }
        return ordered.first()
    }

    /**
     * The user's platform language, as the JVM reports it. Used only to pick between installed
     * languages.
     */
    fun platformPreferredTags(): List<String> = listOf(Locale.getDefault().toLanguageTag())
}
