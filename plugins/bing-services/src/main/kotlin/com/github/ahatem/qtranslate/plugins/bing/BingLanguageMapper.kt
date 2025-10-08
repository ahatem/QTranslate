package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.LanguageMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

object BingLanguageMapper : LanguageMapper {

    // Translation languages from JSON endpoints.languages.translation
    val translationLanguageCodes = listOf(
        // Top languages (predefined)
        LanguageCode.ENGLISH,
        LanguageCode.CHINESE_SIMPLIFIED,
        LanguageCode.CHINESE_TRADITIONAL,
        LanguageCode.HINDI,
        LanguageCode.SPANISH,
        LanguageCode.FRENCH,
        LanguageCode.ARABIC,
        LanguageCode.BENGALI,
        LanguageCode.RUSSIAN,
        LanguageCode.PORTUGUESE,
        LanguageCode.INDONESIAN,
        LanguageCode.URDU,
        LanguageCode.GERMAN,
        LanguageCode.JAPANESE,
        LanguageCode.SWAHILI,
        LanguageCode.MARATHI,
        LanguageCode.TELUGU,
        LanguageCode.TURKISH,
        LanguageCode.TAMIL,
        LanguageCode.VIETNAMESE,
        LanguageCode.KOREAN,
        LanguageCode.ITALIAN,
        LanguageCode.THAI,
        LanguageCode.GUJARATI,
        LanguageCode.JAVANESE,
        LanguageCode.FARSI,
        LanguageCode.HAUSA,
        LanguageCode.BURMESE,
        LanguageCode.POLISH,
        LanguageCode.UKRAINIAN,
        LanguageCode.YORUBA,

        // Other major languages (predefined)
        LanguageCode.DUTCH,
        LanguageCode.GREEK,
        LanguageCode.HEBREW,
        LanguageCode.HUNGARIAN,
        LanguageCode.CZECH,
        LanguageCode.SWEDISH,
        LanguageCode.ROMANIAN,
        LanguageCode.DANISH,
        LanguageCode.FINNISH,
        LanguageCode.BULGARIAN,
        LanguageCode.NORWEGIAN,
        LanguageCode.SLOVAK,
        LanguageCode.SLOVENIAN,
        LanguageCode.CATALAN,
        LanguageCode.SERBIAN,
        LanguageCode.CROATIAN,
        LanguageCode.MALAY,
        LanguageCode.NEPALI,
        LanguageCode.SINHALA,
        LanguageCode.KHMER,
        LanguageCode.LAO,
        LanguageCode.AMHARIC,
        LanguageCode.SOMALI,
        LanguageCode.ZULU,
        LanguageCode.AFRIKAANS,
        LanguageCode.ALBANIAN,
        LanguageCode.ARMENIAN,
        LanguageCode.AZERBAIJANI,
        LanguageCode.BASQUE,
        LanguageCode.BELARUSIAN,
        LanguageCode.BOSNIAN,
        LanguageCode.ESTONIAN,
        LanguageCode.GEORGIAN,
        LanguageCode.ICELANDIC,
        LanguageCode.IRISH,
        LanguageCode.LATVIAN,
        LanguageCode.LITHUANIAN,
        LanguageCode.MACEDONIAN,
        LanguageCode.MALTESE,
        LanguageCode.MONGOLIAN,
        LanguageCode.WELSH,

        // Arabic dialects
        LanguageCode("arz"),   // Egyptian Arabic
        LanguageCode("ary"),   // Moroccan Arabic
        LanguageCode("apc"),   // Levantine Arabic
        LanguageCode("arq"),   // Algerian Arabic
        LanguageCode("ayl"),   // Libyan Arabic
        LanguageCode("arh"),   // Hassaniya Arabic
        LanguageCode("aym"),   // Yemeni Arabic

        // Portuguese variants
        LanguageCode("pt-BR"), // Brazilian Portuguese
        LanguageCode("pt-PT"), // European Portuguese

        // Chinese variants
        LanguageCode("zh-HK"), // Cantonese (Hong Kong)
        LanguageCode("zh-MO"), // Cantonese (Macau)
        LanguageCode("nan"),   // Min Nan (Hokkien/Taiwanese)

        // Other notable variants
        LanguageCode("haw"),   // Hawaiian
        LanguageCode("hmn"),   // Hmong
        LanguageCode("pdc"),   // Pennsylvania German
        LanguageCode("tlh"),   // Klingon
        LanguageCode("wls"),   // Wallisian
        LanguageCode("sm"),    // Samoan
        LanguageCode("to"),    // Tongan
        LanguageCode("ty"),    // Tahitian
        LanguageCode("yua"),   // Yucatec Maya
        LanguageCode("yue")    // Cantonese
    )

    // TTS languages from JSON endpoints.languages.tts
    val ttsLanguageCodes = listOf(
        LanguageCode.AFRIKAANS, LanguageCode.AMHARIC, LanguageCode.ARABIC,
        LanguageCode("ary"), // Moroccan Arabic
        LanguageCode("arz"), // Egyptian Arabic
        LanguageCode("ast"), // Asturian
        LanguageCode.BELARUSIAN, LanguageCode.BULGARIAN, LanguageCode.BENGALI,
        LanguageCode.CATALAN, LanguageCode.CZECH, LanguageCode.WELSH,
        LanguageCode.DANISH, LanguageCode.GERMAN, LanguageCode.GREEK,
        LanguageCode.ENGLISH, LanguageCode("en-GB"), // English (UK)
        LanguageCode.SPANISH, LanguageCode.ESTONIAN, LanguageCode.FARSI,
        LanguageCode.FINNISH, LanguageCode.FRENCH, LanguageCode("fr-CA"), // French (Canada)
        LanguageCode.IRISH, LanguageCode.GUJARATI, LanguageCode.HEBREW,
        LanguageCode.HINDI, LanguageCode.CROATIAN, LanguageCode.HUNGARIAN,
        LanguageCode.INDONESIAN, LanguageCode.ICELANDIC, LanguageCode.ITALIAN,
        LanguageCode("iu"), // Inuktitut
        LanguageCode("iu-Latn"), // Inuktitut (Latin)
        LanguageCode.JAPANESE, LanguageCode.JAVANESE, LanguageCode("kk"), // Kazakh
        LanguageCode.KHMER, LanguageCode("kn"), // Kannada
        LanguageCode.KOREAN, LanguageCode.LAO, LanguageCode.LITHUANIAN,
        LanguageCode.LATVIAN, LanguageCode.MACEDONIAN, LanguageCode("ml"), // Malayalam
        LanguageCode.MARATHI, LanguageCode.MALAY, LanguageCode.MALTESE,
        LanguageCode.BURMESE, LanguageCode.NORWEGIAN, LanguageCode.DUTCH,
        LanguageCode.POLISH, LanguageCode("ps"), // Pashto
        LanguageCode.PORTUGUESE, LanguageCode("pt-PT"), // Portuguese (Portugal)
        LanguageCode.ROMANIAN, LanguageCode.RUSSIAN, LanguageCode.SLOVAK,
        LanguageCode.SLOVENIAN, LanguageCode("sr-Cyrl"), // Serbian (Cyrillic)
        LanguageCode("su"), // Sundanese
        LanguageCode.SWEDISH, LanguageCode.TAMIL, LanguageCode.TELUGU,
        LanguageCode.THAI, LanguageCode.TURKISH, LanguageCode.UKRAINIAN,
        LanguageCode.URDU, LanguageCode("uz"), // Uzbek
        LanguageCode.VIETNAMESE, LanguageCode("yue"), // Cantonese
        LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.CHINESE_TRADITIONAL
    )

    // Spell check languages (simplified set)
    val spellCheckLanguageCodes = listOf(
        LanguageCode.DANISH, LanguageCode.ENGLISH, LanguageCode.DUTCH,
        LanguageCode.FINNISH, LanguageCode.FRENCH, LanguageCode("fr-CA"),
        LanguageCode.GERMAN, LanguageCode.ITALIAN, LanguageCode.JAPANESE,
        LanguageCode.KOREAN, LanguageCode.NORWEGIAN, LanguageCode.POLISH,
        LanguageCode.PORTUGUESE, LanguageCode("pt-PT"), LanguageCode.RUSSIAN,
        LanguageCode.SPANISH, LanguageCode.SWEDISH, LanguageCode.TURKISH,
        LanguageCode.CHINESE_TRADITIONAL, LanguageCode.CHINESE_SIMPLIFIED
    )

    private val supportedLanguagesSet: Set<LanguageCode> by lazy {
        buildSet {
            add(LanguageCode.AUTO)
            addAll(translationLanguageCodes)
        }
    }

    override fun toProviderCode(code: LanguageCode): String = when (code.tag) {
        "auto" -> "auto-detect"
        "no" -> "nb" // Norwegian → Norwegian Bokmål
        else -> code.tag
    }

    override fun fromProviderCode(providerCode: String): LanguageCode = when (providerCode) {
        "auto-detect" -> LanguageCode.AUTO
        "nb" -> LanguageCode.NORWEGIAN
        else -> LanguageCode(providerCode)
    }

    override suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        Ok(supportedLanguagesSet)
}

