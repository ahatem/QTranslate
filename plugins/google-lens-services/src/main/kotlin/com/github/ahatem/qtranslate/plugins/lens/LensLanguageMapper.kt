package com.github.ahatem.qtranslate.plugins.lens

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.LanguageMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

object LensLanguageMapper : LanguageMapper {

    private val tags = listOf(
        "auto", "en", "es", "fr", "de", "it", "pt", "ru",
        "zh-Hans", "zh-Hant", "ja", "ko", "ar", "hi", "bn",
        "id", "tr", "pl", "uk", "nl", "el", "he", "sv", "da",
        "fi", "no", "cs", "ro", "hu", "th", "vi", "ms", "af",
        "sq", "am", "hy", "az", "eu", "be", "bs", "bg", "my",
        "ca", "hr", "et", "fa", "ka", "is", "ga", "km", "lo",
        "lv", "lt", "mk", "mt", "mn", "ne", "sk", "sl", "so",
        "sw", "ta", "te", "ur", "cy", "zu"
    )

    private val supportedLanguagesSet: Set<LanguageCode> = tags.map { LanguageCode(it) }.toSet()

    private val standardToProviderMap = mapOf(
        "zh-Hans" to "zh-CN",
        "zh-Hant" to "zh-TW",
        "he" to "iw"
    )

    private val providerToStandardMap = standardToProviderMap.entries.associate { (k, v) -> v to k }

    override fun toProviderCode(code: LanguageCode): String {
        return standardToProviderMap[code.tag] ?: code.tag
    }

    override fun fromProviderCode(providerCode: String): LanguageCode {
        val standardTag = providerToStandardMap[providerCode] ?: providerCode
        return try {
            val candidate = LanguageCode(standardTag)
            if (candidate in supportedLanguagesSet) {
                candidate
            } else {
                val baseCode = standardTag.split('-', '_').first()
                val baseCandidate = LanguageCode(baseCode)
                if (baseCandidate in supportedLanguagesSet) baseCandidate else candidate
            }
        } catch (_: Exception) {
            val baseCode = standardTag.split('-', '_').first()
            try {
                LanguageCode(baseCode)
            } catch (_: Exception) {
                LanguageCode.ENGLISH
            }
        }
    }

    override suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
        return Ok(supportedLanguagesSet)
    }
}
