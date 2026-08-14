package com.github.ahatem.qtranslate.plugins.mozhi

import com.github.ahatem.qtranslate.api.plugin.ServiceCapability

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.toResultOr

internal class MozhiTranslatorService(
    private val context: PluginContext,
    private val httpClient: KtorHttpClient,
    private val settings: () -> MozhiSettings
) : Translator {

    override val capabilities = setOf(ServiceCapability.TRANSLATOR)

    override val key = "mozhi-services-translator"
    override val name = "Mozhi"
    override val version = "1.0.0"
    override val iconPath = "assets/mozhi.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
        coroutineBinding {
            val current = settings()
            val response = httpClient.fetchJson<MozhiTranslationResponse>(
                url = "${current.resolvedInstanceUrl()}/api/translate",
                queryParams = mapOf(
                    "engine" to current.engine.lowercase(),
                    "from" to toMozhiCode(request.sourceLanguage),
                    "to" to toMozhiCode(request.targetLanguage),
                    "text" to request.text
                )
            ).bind()
            val translatedText = response.translatedText.takeIf(String::isNotBlank)
                .toResultOr {
                    ServiceError.InvalidResponseError(
                        "Mozhi's ${current.engine} engine returned no translation. Try another engine or instance."
                    )
                }.bind()

            TranslationResponse(
                translatedText = translatedText,
                detectedLanguage = detectedLanguage(response, request),
                transliteration = response.targetTransliteration?.takeIf(String::isNotBlank)
            )
        }

    private fun detectedLanguage(
        response: MozhiTranslationResponse,
        request: TranslationRequest
    ): LanguageCode? {
        if (request.sourceLanguage != LanguageCode.AUTO) return null
        val code = response.detected ?: response.sourceLanguage ?: return null
        return runCatching { fromMozhiCode(code) }
            .onFailure { context.logger.warn("Mozhi returned an invalid detected language '$code'.") }
            .getOrNull()
    }

    private fun toMozhiCode(language: LanguageCode): String = when (language) {
        LanguageCode.CHINESE_SIMPLIFIED -> "zh-CN"
        LanguageCode.CHINESE_TRADITIONAL -> "zh-TW"
        else -> language.tag
    }

    private fun fromMozhiCode(code: String): LanguageCode = when (code.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> LanguageCode.CHINESE_SIMPLIFIED
        "zh-tw", "zh-hant" -> LanguageCode.CHINESE_TRADITIONAL
        else -> LanguageCode(code.lowercase())
    }
}
