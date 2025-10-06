package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.*
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.OfficialTranslateResponse
import com.github.ahatem.qtranslate.plugins.google.common.TranslateResponse
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.toResultOr

class GoogleTranslatorService(
    private val pluginContext: PluginContext,
    private val settings: GoogleSettings,
    private val httpClient: KtorHttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig
) : Translator {

    override val id: String = "google-translator"
    override val name: String = "Google Translate"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"

    private val officialParser = createJsonParser<OfficialTranslateResponse>(pluginContext)
    private val translateParser = createJsonParser<TranslateResponse>(pluginContext)

    companion object {
        private const val TRANSLATE_PRIMARY = "https://translate.googleapis.com/translate_a/single"
        private const val TRANSLATE_FALLBACK = "https://clients5.google.com/translate_a/t"
        private const val TRANSLATE_OFFICIAL = "https://translation.googleapis.com/language/translate/v2"
        private val TRANSLATE_FEATURES = listOf("t", "bd", "at", "ex", "ld", "md", "rw", "rm", "ss", "qc")
    }

    override suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
        return languageMapper.getSupportedLanguages()
    }

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        if (settings.translateApiKey.isNotBlank()) {
            pluginContext.logInfo("Using official Google Translate API")
            val officialResult = translateWithOfficialAPI(request)
            if (officialResult.isOk) return officialResult
            pluginContext.logInfo("Official API failed, falling back to unofficial endpoint")
        }
        return translateWithUnofficialAPI(request)
    }

    private suspend fun translateWithOfficialAPI(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val sourceTag = languageMapper.toProviderCode(request.sourceLanguage)
        val targetTag = languageMapper.toProviderCode(request.targetLanguage)

        val requestBody = mapOf(
            "q" to request.text,
            "source" to sourceTag,
            "target" to targetTag,
            "format" to "text"
        )

        val responseString = httpClient.sendJson(
            url = TRANSLATE_OFFICIAL,
            headers = apiConfig.createJsonHeaders(),
            body = requestBody,
            queryParams = mapOf("key" to settings.translateApiKey)
        ).bind()

        val parsed = officialParser.parse(responseString).bind()
        val firstTranslation = parsed.data.translations.firstOrNull()
            .toResultOr { ServiceError.InvalidResponseError("No translation in response", null) }
            .bind()

        TranslationResponse(
            translatedText = firstTranslation.translatedText,
            detectedLanguage = firstTranslation.detectedSourceLanguage?.let {
                languageMapper.fromProviderCode(it)
            }
        )
    }

    private suspend fun translateWithUnofficialAPI(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> {
        val sourceTag = languageMapper.toProviderCode(request.sourceLanguage)
        val targetTag = languageMapper.toProviderCode(request.targetLanguage)

        val primaryResult = tryPrimaryEndpoint(request.text, sourceTag, targetTag)
        if (primaryResult.isOk) return primaryResult

        pluginContext.logInfo("Primary endpoint failed, trying fallback")
        return tryFallbackEndpoint(request.text, sourceTag, targetTag)
    }

    private suspend fun tryPrimaryEndpoint(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val responseString = httpClient.get(
            url = TRANSLATE_PRIMARY,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to "gtx",
                "ie" to "UTF-8",
                "oe" to "UTF-8",
                "dj" to 1,
                "dt" to TRANSLATE_FEATURES,
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).bind()

        val parsed = translateParser.parse(responseString).bind()
        val translatedText = parsed.sentences.joinToString("") { it.text.orEmpty() }
        val detectedLang = languageMapper.fromProviderCode(parsed.sourceLanguage)
        val alternatives = parsed.dictionary?.firstOrNull()?.terms?.take(3) ?: emptyList()

        TranslationResponse(
            translatedText = translatedText.trim(),
            detectedLanguage = detectedLang,
            alternatives = alternatives
        )
    }

    private suspend fun tryFallbackEndpoint(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val parsed: List<List<String>> = httpClient.fetchJson<List<List<String>>>(
            url = TRANSLATE_FALLBACK,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to "dict-chrome-ex",
                "dj" to 1,
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).bind()

        val translatedText = parsed.getOrNull(0)?.getOrNull(0)
            .toResultOr { ServiceError.InvalidResponseError("No translation in fallback response", null) }
            .bind()

        val detectedLang = parsed.getOrNull(0)?.getOrNull(1)
            ?.let { languageMapper.fromProviderCode(it) }

        TranslationResponse(
            translatedText = translatedText.trim(),
            detectedLanguage = detectedLang
        )
    }

}