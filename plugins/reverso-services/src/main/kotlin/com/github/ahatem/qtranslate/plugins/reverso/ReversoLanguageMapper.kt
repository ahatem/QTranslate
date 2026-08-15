package com.github.ahatem.qtranslate.plugins.reverso

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages

internal object ReversoLanguageMapper {
    val languages = setOf(
        LanguageCode.ENGLISH,
        LanguageCode.FRENCH,
        LanguageCode.GERMAN,
        LanguageCode.SPANISH,
        LanguageCode.ITALIAN,
        LanguageCode.RUSSIAN
    )
    val supportedLanguages = SupportedLanguages.Specific(languages)

    fun code(language: LanguageCode): String = language.tag.substringBefore('-').lowercase()
}
