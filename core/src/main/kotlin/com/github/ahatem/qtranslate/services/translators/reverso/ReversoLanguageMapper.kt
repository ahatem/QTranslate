package com.github.ahatem.qtranslate.services.translators.reverso

import com.github.ahatem.qtranslate.common.UserAgent
import com.github.ahatem.qtranslate.models.Language
import com.github.ahatem.qtranslate.services.translators.abstraction.LanguageMapper
import com.google.gson.Gson
import kong.unirest.core.Unirest
import kotlinx.coroutines.future.await

private data class ReversoLanguageDetectionRequest(
    val input: String,
    val from: String = "eng",
    val to: String = "fra",
    val format: String = "text",
    val options: TranslationRequestOptions = TranslationRequestOptions()
)

private data class TranslationRequestOptions(
    val origin: String = "translation.web",
    val sentenceSplitter: Boolean = false,
    val contextResults: Boolean = false,
    val languageDetection: Boolean = true
)

private data class ReversoLanguageDetectionResponse(val languageDetection: LanguageDetection)
private data class LanguageDetection(
    val detectedLanguage: String,
    val isDirectionChanged: Boolean,
    val originalDirection: String,
    val originalDirectionContextMatches: Long,
    val changedDirectionContextMatches: Long,
    val timeTaken: Long,
)

class ReversoLanguageMapper(override val serviceName: String, private val gson: Gson) : LanguageMapper() {
    override val supportedLanguages: Array<String>
        get() = arrayOf(
            "auto",
            "ara",
            "chi",
            "dut",
            "eng",
            "fra",
            "ger",
            "heb",
            "ita",
            "jpn",
            "pol",
            "por",
            "rum",
            "rus",
            "spa",
            "tur"
        )

    override suspend fun detectLanguage(text: String): String {
        val url = "https://api.reverso.net/translate/v1/translation"

        val headers = mapOf(
            "user-agent" to UserAgent.random(),
            "content-type" to "application/json"
        )

        runCatching {
            return Unirest.post(url)
                .headers(headers)
                .body(gson.toJson(ReversoLanguageDetectionRequest(input = text)))
                .asStringAsync().await().body.let {
                    val response = gson.fromJson(it, ReversoLanguageDetectionResponse::class.java)
                    response.languageDetection.detectedLanguage
                }
        }

        return "eng"
    }

    override fun doNormalize(language: Language): String {
        if (language.id == "zho") return "chi"
        return language.alpha3
    }

    override fun doDenormalize(languageCode: String): Language {
        if (languageCode in arrayOf("chi", "zh-cn")) return Language("zho")
        return Language(languageCode)
    }

}