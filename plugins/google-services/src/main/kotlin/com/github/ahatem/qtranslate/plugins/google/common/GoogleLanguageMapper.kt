package com.github.ahatem.qtranslate.plugins.google.common


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.LanguageMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

object GoogleLanguageMapper : LanguageMapper {

    private val supportedLanguagesSet: Set<LanguageCode> = setOf(
        LanguageCode.AUTO,
        LanguageCode.ENGLISH, LanguageCode.SPANISH, LanguageCode.FRENCH,
        LanguageCode.GERMAN, LanguageCode.ARABIC, LanguageCode.HINDI,
        LanguageCode.ITALIAN, LanguageCode.JAPANESE, LanguageCode.KOREAN,
        LanguageCode.PORTUGUESE, LanguageCode.RUSSIAN,
        LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.CHINESE_TRADITIONAL,
        LanguageCode.AFRIKAANS, LanguageCode.ALBANIAN, LanguageCode.AMHARIC,
        LanguageCode.ARMENIAN, LanguageCode.AZERBAIJANI, LanguageCode.BASQUE,
        LanguageCode.BELARUSIAN, LanguageCode.BENGALI, LanguageCode.BOSNIAN,
        LanguageCode.BULGARIAN, LanguageCode.BURMESE, LanguageCode.CATALAN,
        LanguageCode.CZECH, LanguageCode.DANISH, LanguageCode.DUTCH,
        LanguageCode.ESTONIAN, LanguageCode.FINNISH, LanguageCode.GREEK,
        LanguageCode.HEBREW, LanguageCode.HUNGARIAN, LanguageCode.ICELANDIC,
        LanguageCode.INDONESIAN, LanguageCode.IRISH, LanguageCode.KHMER,
        LanguageCode.LAO, LanguageCode.LATVIAN, LanguageCode.LITHUANIAN,
        LanguageCode.MACEDONIAN, LanguageCode.MALAY, LanguageCode.MALTESE,
        LanguageCode.MONGOLIAN, LanguageCode.NEPALI, LanguageCode.NORWEGIAN,
        LanguageCode.POLISH, LanguageCode.ROMANIAN, LanguageCode.SERBIAN,
        LanguageCode.SINHALA, LanguageCode.SLOVAK, LanguageCode.SLOVENIAN,
        LanguageCode.SOMALI, LanguageCode.SWAHILI, LanguageCode.SWEDISH,
        LanguageCode.TAMIL, LanguageCode.TELUGU, LanguageCode.THAI,
        LanguageCode.TURKISH, LanguageCode.UKRAINIAN, LanguageCode.URDU,
        LanguageCode.VIETNAMESE, LanguageCode.WELSH, LanguageCode.YORUBA,
        LanguageCode.ZULU,
        // Google-specific
        LanguageCode("ceb"), LanguageCode("co"), LanguageCode("eo"),
        LanguageCode("fy"), LanguageCode("gd"), LanguageCode("gl"),
        LanguageCode("gu"), LanguageCode("ht"), LanguageCode("ha"),
        LanguageCode("haw"), LanguageCode("hmn"), LanguageCode("ig"),
        LanguageCode("jw"), LanguageCode("kn"), LanguageCode("kk"),
        LanguageCode("ku"), LanguageCode("ky"), LanguageCode("lb"),
        LanguageCode("mg"), LanguageCode("ml"), LanguageCode("mi"),
        LanguageCode("mr"), LanguageCode("ny"), LanguageCode("or"),
        LanguageCode("ps"), LanguageCode("pa"), LanguageCode("sm"),
        LanguageCode("st"), LanguageCode("sn"), LanguageCode("sd"),
        LanguageCode("su"), LanguageCode("tg"), LanguageCode("tt"),
        LanguageCode("tk"), LanguageCode("ug"), LanguageCode("uz"),
        LanguageCode("xh"), LanguageCode("yi")
    )

    private val standardToGoogleMap = mapOf(
        LanguageCode.CHINESE_SIMPLIFIED.tag to "zh-CN",
        LanguageCode.CHINESE_TRADITIONAL.tag to "zh-TW",
        LanguageCode.HEBREW.tag to "iw"
    )

    private val googleToStandardMap = standardToGoogleMap.entries.associate { (k, v) -> v to k }

    override fun toProviderCode(code: LanguageCode): String {
        if (code == LanguageCode.AUTO) return "auto"
        return standardToGoogleMap[code.tag] ?: code.tag.split('-').first()
    }

    override fun fromProviderCode(providerCode: String): LanguageCode {
        if (providerCode == "auto") return LanguageCode.AUTO
        if (providerCode == "iw") return LanguageCode.HEBREW
        val standardTag = googleToStandardMap[providerCode] ?: providerCode
        return LanguageCode(standardTag)
    }

    override suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
        return Ok(supportedLanguagesSet)
    }
}