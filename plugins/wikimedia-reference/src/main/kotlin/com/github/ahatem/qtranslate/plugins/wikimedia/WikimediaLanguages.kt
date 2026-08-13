package com.github.ahatem.qtranslate.plugins.wikimedia

import com.github.ahatem.qtranslate.api.language.LanguageCode

internal object WikimediaLanguages {
    private val editionCodes = setOf(
        "af", "ar", "az", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el",
        "en", "eo", "es", "et", "eu", "fa", "fi", "fr", "ga", "gl", "he", "hi",
        "hr", "hu", "hy", "id", "is", "it", "ja", "ka", "kk", "km", "ko", "la",
        "lo", "lt", "lv", "mk", "ml", "mn", "mr", "ms", "mt", "my", "ne", "nl",
        "no", "pl", "pt", "ro", "ru", "si", "sk", "sl", "sq", "sr", "sv", "sw",
        "ta", "te", "th", "tr", "uk", "ur", "uz", "vi", "yi", "zh"
    )

    val supported: Set<LanguageCode> = buildSet {
        editionCodes.mapTo(this, ::LanguageCode)
        add(LanguageCode.CHINESE_SIMPLIFIED)
        add(LanguageCode.CHINESE_TRADITIONAL)
    }

    fun editionCode(language: LanguageCode): String = when (language) {
        LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.CHINESE_TRADITIONAL -> "zh"
        else -> language.tag.lowercase().substringBefore('-')
    }
}
