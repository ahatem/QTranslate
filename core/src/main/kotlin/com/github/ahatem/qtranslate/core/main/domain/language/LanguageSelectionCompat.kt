package com.github.ahatem.qtranslate.core.main.domain.language

import com.github.ahatem.qtranslate.api.language.LanguageCode

/**
 * Compatibility rules between the user's selected language and the active translator's
 * advertised list. User-facing selectors only offer the explicit regional variants, but
 * a language tag stored by older versions remains valid: providers that advertise
 * `pt-BR`/`pt-PT` still accept generic `pt` at their own boundary.
 */
object LanguageSelectionCompat {

    /**
     * A selected generic `pt` stays satisfied when the provider advertises any regional
     * Portuguese variant; [LanguageCode.PORTUGUESE_BRAZIL]/[LanguageCode.PORTUGUESE_PORTUGAL]
     * selections require their own exact tags.
     */
    fun isSupported(selected: LanguageCode, supported: Collection<LanguageCode>): Boolean {
        if (selected in supported) return true
        return selected.tag.equals("pt", ignoreCase = true) &&
            supported.any { it.tag.startsWith("pt-", ignoreCase = true) }
    }

    /**
     * Appends currently selected languages back into the advertised list when they are
     * only satisfiable through the legacy-alias rule, so selectors can render the active
     * row. Generic `pt` is never added on its own for new choices.
     */
    fun visibleLanguages(
        supported: List<LanguageCode>,
        currentSelections: List<LanguageCode?>
    ): List<LanguageCode> {
        val extras = currentSelections
            .filterNotNull()
            .filter { it !in supported && isSupported(it, supported) }
            .distinct()
        return if (extras.isEmpty()) supported else supported + extras
    }

    /**
     * Runtime policy for the selected target language. An existing selection that the
     * provider still satisfies through the legacy-alias rule is kept verbatim; stored
     * values are never silently rewritten, and fallback to an unrelated language only
     * happens when nothing at all can honor the current choice.
     */
    fun resolveTargetLanguage(
        current: LanguageCode,
        supported: List<LanguageCode>,
        preferredTag: String
    ): LanguageCode = when {
        isSupported(current, supported) -> current
        else -> {
            val preferred = LanguageCode(preferredTag)
            supported.firstOrNull { it == preferred }
                ?: supported.firstOrNull { it.tag != "auto" }
                ?: current
        }
    }
}
