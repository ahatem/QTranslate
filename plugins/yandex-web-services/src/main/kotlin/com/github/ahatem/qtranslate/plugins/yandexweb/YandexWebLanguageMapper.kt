package com.github.ahatem.qtranslate.plugins.yandexweb

import com.github.ahatem.qtranslate.api.language.LanguageCode

internal object YandexWebLanguageMapper {
    private val providerCodes = setOf(
        "af", "az", "sq", "ar", "hy", "eu", "be", "bg", "ca", "zh", "hr", "cs",
        "da", "nl", "en", "et", "fi", "fr", "gl", "de", "el", "ht", "he", "hi",
        "hu", "is", "id", "it", "ga", "ja", "ka", "ko", "lv", "lt", "mk", "ms",
        "mt", "no", "fa", "pl", "pt", "ro", "ru", "sr", "sk", "sl", "es", "sw",
        "sv", "th", "tr", "uk", "ur", "vi", "cy", "yi", "eo", "la", "lo", "kk",
        "uz", "si", "tg", "te", "km", "mn", "kn", "ta", "mr", "bn", "tt"
    )

    val supportedLanguages: Set<LanguageCode> = buildSet {
        add(LanguageCode.AUTO)
        providerCodes.mapTo(this, ::LanguageCode)
        add(LanguageCode.CHINESE_SIMPLIFIED)
        add(LanguageCode.CHINESE_TRADITIONAL)
    }

    fun toProviderCode(language: LanguageCode): String = when (language) {
        LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.CHINESE_TRADITIONAL -> "zh"
        else -> language.tag
    }

    fun fromProviderCode(code: String): LanguageCode? = when (code.lowercase()) {
        "zh" -> LanguageCode.CHINESE_SIMPLIFIED
        in providerCodes -> LanguageCode(code.lowercase())
        else -> null
    }
}
