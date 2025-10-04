package com.github.ahatem.qtranslate.api

@JvmInline
value class LanguageCode(val code: String) {
    init {
        require(code == "auto" || code.length == 3) {
            "Language code must be ISO 639-3 (3 letters) or 'auto', got: $code"
        }
    }

    companion object {
        val AUTO = LanguageCode("auto")
        val ENGLISH = LanguageCode("eng")
        val ARABIC = LanguageCode("ara")
        val CHINESE = LanguageCode("zho")
        val SPANISH = LanguageCode("spa")
        val FRENCH = LanguageCode("fra")
        val GERMAN = LanguageCode("deu")
        val JAPANESE = LanguageCode("jpn")
        val KOREAN = LanguageCode("kor")
        val RUSSIAN = LanguageCode("rus")
        val PORTUGUESE = LanguageCode("por")
        val ITALIAN = LanguageCode("ita")
        val DUTCH = LanguageCode("nld")
        val POLISH = LanguageCode("pol")
        val TURKISH = LanguageCode("tur")
        val SWEDISH = LanguageCode("swe")
        val HINDI = LanguageCode("hin")
    }
}

/**
 * Helper for common ISO 639-2 (2-letter) to ISO 639-3 conversions.
 * Plugins can use this if their service uses 2-letter codes.
 */
object LanguageMapping {
    private val alpha2ToAlpha3 = mapOf(
        "auto" to "auto",
        "en" to "eng",
        "ar" to "ara",
        "zh" to "zho",
        "es" to "spa",
        "fr" to "fra",
        "de" to "deu",
        "ja" to "jpn",
        "ko" to "kor",
        "ru" to "rus",
        "pt" to "por",
        "it" to "ita",
        "nl" to "nld",
        "pl" to "pol",
        "tr" to "tur",
        "sv" to "swe",
        "hi" to "hin",
        "he" to "heb",
        "th" to "tha",
        "vi" to "vie",
        "id" to "ind",
        "ms" to "msa",
        "fa" to "fas",
        "uk" to "ukr",
        "ro" to "ron",
        "cs" to "ces",
        "el" to "ell",
        "hu" to "hun",
        "da" to "dan",
        "fi" to "fin",
        "no" to "nor",
        "bg" to "bul",
        "hr" to "hrv",
        "sk" to "slk"
    )

    private val alpha3ToAlpha2 = alpha2ToAlpha3.entries
        .associate { (k, v) -> v to k }

    /**
     * Convert 2-letter code to ISO 639-3.
     * Returns null if unknown.
     */
    fun toAlpha3(code: String): LanguageCode? {
        return alpha2ToAlpha3[code.lowercase()]?.let { LanguageCode(it) }
    }

    /**
     * Convert ISO 639-3 to 2-letter code.
     * Returns null if unknown.
     */
    fun toAlpha2(lang: LanguageCode): String? {
        return alpha3ToAlpha2[lang.code]
    }
}

interface LanguageSupport {
    val supportedLanguages: Set<LanguageCode>
    val supportsAutoDetect: Boolean
}