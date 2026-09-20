package com.github.ahatem.qtranslate.plugins.systemocr.language

import com.github.ahatem.qtranslate.api.language.LanguageCode

/**
 * Maps between the platform OCR engines' language identifiers and QTranslate's [LanguageCode].
 *
 * Windows and Vision already use BCP-47, so their tags pass through. Tesseract names its traineddata
 * by ISO 639-2/T code, so a small table maps those. Identifiers that do not name a language, or that
 * [LanguageCode] cannot represent, are dropped.
 */
internal object SystemOcrLanguages {

    /** A BCP-47 tag, when QTranslate can represent it. */
    fun fromBcp47(tag: String): LanguageCode? =
        runCatching { LanguageCode(tag.trim()) }.getOrNull()

    /**
     * A Tesseract traineddata code (`eng`, `deu`, `chi_sim`, ...), or null when it names something
     * other than a language (`osd`, `equ`, the `script/` models) or falls outside [LanguageCode].
     */
    fun fromTesseract(code: String): LanguageCode? {
        val normalised = code.trim().lowercase()
        if (normalised in NON_LANGUAGE_TESSDATA || normalised.startsWith("script/")) return null
        val bcp47 = TESSERACT_TO_BCP47[normalised] ?: normalised
        return fromBcp47(bcp47)
    }

    /** The Tesseract traineddata code to request for [language], or `null` when unmapped. */
    fun toTesseract(language: LanguageCode): String? =
        BCP47_TO_TESSERACT[language.tag]
            ?: BCP47_TO_TESSERACT[language.tag.substringBefore('-')]

    private val NON_LANGUAGE_TESSDATA = setOf("osd", "equ")

    /**
     * Traineddata to BCP-47, ordered so the canonical code for each language comes first. The
     * reverse map is derived from it, so the two cannot disagree.
     */
    private val TESSERACT_TO_BCP47: Map<String, String> = linkedMapOf(
        "eng" to "en", "deu" to "de", "fra" to "fr", "spa" to "es", "ita" to "it",
        "por" to "pt", "rus" to "ru", "jpn" to "ja", "kor" to "ko",
        "chi_sim" to "zh-Hans", "chi_tra" to "zh-Hant",
        "ara" to "ar", "hin" to "hi", "nld" to "nl", "pol" to "pl", "tur" to "tr",
        "ces" to "cs", "swe" to "sv", "dan" to "da", "fin" to "fi", "nor" to "no",
        "ell" to "el", "heb" to "he", "ukr" to "uk", "vie" to "vi", "tha" to "th",
        "ind" to "id", "msa" to "ms", "ron" to "ro", "hun" to "hu", "bul" to "bg",
        "ben" to "bn", "tam" to "ta", "tel" to "te", "urd" to "ur", "fas" to "fa",
        "guj" to "gu", "mar" to "mr", "mal" to "ml", "kan" to "kn", "sin" to "si",
        "nep" to "ne", "khm" to "km", "lao" to "lo", "mya" to "my", "amh" to "am",
        "kat" to "ka", "hye" to "hy", "aze" to "az", "bel" to "be", "bos" to "bs",
        "cat" to "ca", "hrv" to "hr", "est" to "et", "eus" to "eu", "gle" to "ga",
        "isl" to "is", "lav" to "lv", "lit" to "lt", "mkd" to "mk", "mlt" to "mt",
        "mon" to "mn", "slk" to "sk", "slv" to "sl", "sqi" to "sq", "srp" to "sr",
        "swa" to "sw", "cym" to "cy", "afr" to "af", "jav" to "jv", "yor" to "yo",
        "hau" to "ha", "som" to "so", "zul" to "zu",
    )

    private val BCP47_TO_TESSERACT: Map<String, String> = buildMap {
        for ((code, bcp47) in TESSERACT_TO_BCP47) {
            if (bcp47 !in this) put(bcp47, code)
        }
    }
}
