package com.github.ahatem.qtranslate.plugins.libretranslate


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.BatchTranslationRequest
import com.github.ahatem.qtranslate.api.translator.BatchTranslationResponse
import com.github.ahatem.qtranslate.api.translator.BatchTranslator
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import kotlinx.serialization.json.Json

internal class LibreTranslateService(
    private val context: PluginContext,
    private val httpClient: KtorHttpClient,
    private val settings: () -> LibreTranslateSettings
) : BatchTranslator {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    override val key = "libretranslate-local-translator"
    override val name = "LibreTranslate Local"
    override val iconPath = "assets/libretranslate.svg"
    override val version = "1.0.0"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic
    override val maxBatchSize = 50
    override val maxBatchCharacters = 50_000

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        coroutineBinding {
            val current = settings()
            val languages = httpClient.fetchJson<List<LibreTranslateLanguage>>(
                "${current.normalizedInstanceUrl()}/languages"
            ).bind()
            buildSet {
                add(LanguageCode.AUTO)
                languages.forEach { language ->
                    toLanguageCode(language.code)?.let(::add)
                    language.targets.forEach { target -> toLanguageCode(target)?.let(::add) }
                }
            }
        }

    override suspend fun translate(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val current = settings()
        val body = SingleTranslateBody(
            q = request.text,
            source = request.sourceLanguage.tag,
            target = request.targetLanguage.tag,
            apiKey = current.apiKey.takeIf(String::isNotBlank)
        )
        val raw = httpClient.sendJson(
            url = "${current.normalizedInstanceUrl()}/translate",
            body = body
        ).bind()
        val response = decode<SingleTranslateResponse>(raw).bind()
        TranslationResponse(
            translatedText = response.translatedText,
            detectedLanguage = response.detectedLanguage?.language?.let(::toLanguageCode)
        )
    }

    override suspend fun translateBatch(
        request: BatchTranslationRequest
    ): Result<BatchTranslationResponse, ServiceError> = coroutineBinding {
        val current = settings()
        val body = BatchTranslateBody(
            q = request.texts,
            source = request.sourceLanguage.tag,
            target = request.targetLanguage.tag,
            apiKey = current.apiKey.takeIf(String::isNotBlank)
        )
        val raw = httpClient.sendJson(
            url = "${current.normalizedInstanceUrl()}/translate",
            body = body
        ).bind()
        val response = decode<BatchTranslateResponse>(raw).bind()
        if (response.translatedText.size != request.texts.size) {
            Err(
                ServiceError.InvalidResponseError(
                    "LibreTranslate returned ${response.translatedText.size} translations for ${request.texts.size} texts.",
                    null
                )
            ).bind()
        }
        BatchTranslationResponse(
            response.translatedText.mapIndexed { index, translatedText ->
                TranslationResponse(
                    translatedText = translatedText,
                    detectedLanguage = response.detectedLanguage?.getOrNull(index)?.language?.let(::toLanguageCode)
                )
            }
        )
    }

    private inline fun <reified T> decode(raw: String): Result<T, ServiceError> =
        runCatching { json.decodeFromString<T>(raw) }
            .fold(
                onSuccess = { com.github.michaelbull.result.Ok(it) },
                onFailure = {
                    context.logger.warn("LibreTranslate returned an invalid response: ${it.message}")
                    Err(ServiceError.InvalidResponseError("LibreTranslate returned an invalid JSON response.", it))
                }
            )

    private fun toLanguageCode(code: String): LanguageCode? = runCatching {
        when (code.lowercase()) {
            "zh", "zh-hans", "zh-cn" -> LanguageCode.CHINESE_SIMPLIFIED
            "zt", "zh-hant", "zh-tw" -> LanguageCode.CHINESE_TRADITIONAL
            else -> LanguageCode(code.lowercase())
        }
    }.onFailure {
        context.logger.warn("LibreTranslate returned an invalid language code '$code'.")
    }.getOrNull()
}
