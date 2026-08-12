package com.github.ahatem.qtranslate.plugins.deepl

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.toResultOr

internal class DeepLTranslatorService(
    private val context: PluginContext,
    private val httpClient: KtorHttpClient,
    private val settings: () -> DeepLSettings
) : Translator {

    override val id = "deepl-services-translator"
    override val name = "DeepL"
    override val version = "1.0.0"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    private val translationParser = createJsonParser<DeepLTranslateResponse>(context)

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
        val current = settings()
        if (current.apiKey.isBlank()) {
            return Err(ServiceError.AuthenticationError("DeepL API key is not configured."))
        }

        return coroutineBinding {
            val targetLanguages = httpClient.fetchJson<List<DeepLLanguage>>(
                url = "${current.baseUrl()}/v2/languages",
                headers = current.authHeaders(),
                queryParams = mapOf("type" to "target")
            ).bind()

            buildSet {
                add(LanguageCode.AUTO)
                targetLanguages.forEach { add(fromDeepLCode(it.language)) }
            }
        }
    }

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        val current = settings()
        if (current.apiKey.isBlank()) {
            return Err(ServiceError.AuthenticationError("DeepL API key is not configured."))
        }

        return coroutineBinding {
            val body = DeepLTranslateRequest(
                text = listOf(request.text),
                targetLanguage = toDeepLCode(request.targetLanguage),
                sourceLanguage = request.sourceLanguage
                    .takeUnless { it == LanguageCode.AUTO }
                    ?.let(::toDeepLCode)
            )

            val responseText = httpClient.sendJson(
                url = "${current.baseUrl()}/v2/translate",
                headers = current.authHeaders(),
                body = body
            ).bind()

            val translation = translationParser.parse(responseText).bind()
                .translations.firstOrNull()
                .toResultOr { ServiceError.InvalidResponseError("DeepL returned no translation.", null) }
                .bind()

            TranslationResponse(
                translatedText = translation.text,
                detectedLanguage = translation.detectedSourceLanguage?.let(::fromDeepLCode)
            )
        }
    }

    private fun toDeepLCode(language: LanguageCode): String = when (language) {
        LanguageCode.CHINESE_SIMPLIFIED -> "ZH-HANS"
        LanguageCode.CHINESE_TRADITIONAL -> "ZH-HANT"
        else -> language.tag.uppercase()
    }

    private fun fromDeepLCode(code: String): LanguageCode = when (code.uppercase()) {
        "ZH", "ZH-HANS" -> LanguageCode.CHINESE_SIMPLIFIED
        "ZH-HANT" -> LanguageCode.CHINESE_TRADITIONAL
        else -> LanguageCode(code.lowercase())
    }
}
