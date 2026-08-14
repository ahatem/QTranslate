package com.github.ahatem.qtranslate.plugins.deepl

import com.github.ahatem.qtranslate.api.plugin.ServiceCapability

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.HttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.toResultOr
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DeepLTranslatorService(
    private val context: PluginContext,
    private val httpClient: HttpClient,
    private val settings: () -> DeepLSettings,
    private val onModeChanged: (DeepLMode) -> Unit = {},
    private val minimumWebRequestIntervalMillis: Long = 2_000,
    private val rateLimitBackoffMillis: Long = 10_000,
    private val maxWebRetries: Int = 1
) : Translator {
    override val capabilities = setOf(ServiceCapability.TRANSLATOR)
    override val key = "deepl-services-translator"
    override val name = "DeepL"
    override val version = "1.1.0"
    override val iconPath = "assets/deepl.svg"
    override val supportedLanguages = SupportedLanguages.Specific(SUPPORTED_LANGUAGES)

    private val responseParser =
        com.github.ahatem.qtranslate.plugins.common.createJsonParser<DeepLTranslateResponse>(context)
    private val webRequestMutex = Mutex()
    private var lastWebRequestAtNanos = 0L
    private var rejectedApiKey: String? = null

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        val current = settings()
        if (current.apiKey.isNotBlank() && current.apiKey != rejectedApiKey) {
            onModeChanged(DeepLMode.OFFICIAL)
            val officialResult = translateOfficial(request, current)
            if (officialResult.isOk) return officialResult

            val authenticationFailed = officialResult.fold(
                success = { false },
                failure = { it is ServiceError.AuthenticationError }
            )
            if (authenticationFailed) {
                rejectedApiKey = current.apiKey
                onModeChanged(DeepLMode.FREE_WEB_AFTER_REJECTION)
                context.logger.warn("DeepL rejected the configured API key; using the free web endpoint")
                context.notify(
                    // Key and fallback: the host translates it if it knows the key, and shows
                    // the English text if it does not. This plugin ships no bundle of its own.
                    title = DisplayText(
                        "deepl.api_key_rejected_title",
                        "DeepL API key rejected"
                    ),
                    body = DisplayText(
                        "deepl.api_key_rejected_body",
                        "Using the free web endpoint. Update the API key in plugin settings to restore official access."
                    ),
                    type = NotificationType.WARNING
                )
                return translateWeb(request)
            }
            return officialResult
        }

        val rejected = current.apiKey.isNotBlank() && current.apiKey == rejectedApiKey
        onModeChanged(if (rejected) DeepLMode.FREE_WEB_AFTER_REJECTION else DeepLMode.FREE_WEB)
        return translateWeb(request)
    }

    private suspend fun translateOfficial(
        request: TranslationRequest,
        settings: DeepLSettings
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val requestBody = DeepLTranslateRequest(
            text = listOf(request.text),
            targetLanguage = toDeepLCode(request.targetLanguage),
            sourceLanguage = request.sourceLanguage.takeUnless { it == LanguageCode.AUTO }?.let(::toDeepLCode)
        )
        val responseText = httpClient.post(
            url = "${settings.baseUrl()}/v2/translate",
            headers = ApiConfig().createJsonHeaders(settings.authHeaders()),
            body = json.encodeToString(requestBody)
        ).bind()
        val translation = responseParser.parse(responseText).bind().translations.firstOrNull()
            .toResultOr { ServiceError.InvalidResponseError("DeepL returned no translation.") }
            .bind()

        TranslationResponse(
            translatedText = translation.text,
            detectedLanguage = translation.detectedSourceLanguage?.let(::fromDeepLCode)
        )
    }

    private suspend fun translateWeb(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
        coroutineBinding {
            val responses = splitForWeb(request.text).map { text ->
                translateWebSegment(request.copy(text = text)).bind()
            }
            TranslationResponse(
                translatedText = responses.joinToString("") { it.translatedText },
                detectedLanguage = responses.firstNotNullOfOrNull { it.detectedLanguage },
                alternatives = responses.singleOrNull()?.alternatives.orEmpty()
            )
        }

    private suspend fun translateWebSegment(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> = webRequestMutex.withLock {
            coroutineBinding {
                paceWebRequest()
                val body = json.encodeToString(DeepLTranslateRequest(
                    text = listOf(request.text),
                    targetLanguage = toWebCode(request.targetLanguage),
                    sourceLanguage = request.sourceLanguage
                        .takeUnless { it == LanguageCode.AUTO }
                        ?.let(::toWebCode)
                ))

                val responseBody = postWeb(body).bind()
                lastWebRequestAtNanos = System.nanoTime()

                if (responseBody.contains("Too many requests", ignoreCase = true) ||
                    responseBody.contains("\"code\":1042911")) {
                    Err(ServiceError.RateLimitError(FREE_RATE_LIMIT_MESSAGE)).bind<String>()
                }

                val translation = responseParser.parse(responseBody).bind().translations
                    .firstOrNull { it.text.isNotBlank() }
                    .toResultOr { ServiceError.InvalidResponseError(
                        "DeepL's free endpoint returned no translated text. It may have changed."
                    ) }.bind()

                TranslationResponse(
                    translatedText = translation.text,
                    detectedLanguage = translation.detectedSourceLanguage?.let(::fromDeepLCode)
                )
            }
        }

    private fun mapWebHttpError(error: ServiceError): ServiceError = when (error) {
        is ServiceError.RateLimitError -> ServiceError.RateLimitError(
            message = FREE_RATE_LIMIT_MESSAGE,
            retryAfterSeconds = error.retryAfterSeconds,
            cause = error.cause
        )
        else -> error
    }

    private suspend fun postWeb(body: String): Result<String, ServiceError> {
        val headers = ApiConfig().createJsonHeaders(mapOf(
            "Authorization" to "None",
            "Accept" to "application/json"
        ))
        var attempt = 0
        while (true) {
            val result = httpClient.post(WEB_ENDPOINT, headers, body)
            val rateLimit = result.fold(
                success = { null },
                failure = { it as? ServiceError.RateLimitError }
            )
            if (rateLimit == null || attempt >= maxWebRetries) return result.mapError(::mapWebHttpError)

            val retryAfterMillis = rateLimit.retryAfterSeconds?.times(1_000L) ?: 0L
            delay(maxOf(rateLimitBackoffMillis, retryAfterMillis))
            attempt++
        }
    }

    private fun splitForWeb(text: String): List<String> {
        if (text.length <= MAX_WEB_CHARACTERS) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + MAX_WEB_CHARACTERS, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--

            if (end < text.length) {
                val preferredBoundary = (end - 1 downTo start + MAX_WEB_CHARACTERS / 2)
                    .firstOrNull { text[it].isWhitespace() }
                if (preferredBoundary != null) end = preferredBoundary + 1
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }

    private suspend fun paceWebRequest() {
        if (lastWebRequestAtNanos == 0L || minimumWebRequestIntervalMillis <= 0) return
        val elapsedMillis = (System.nanoTime() - lastWebRequestAtNanos) / 1_000_000
        val remainingMillis = minimumWebRequestIntervalMillis - elapsedMillis
        if (remainingMillis > 0) delay(remainingMillis)
    }

    private fun toDeepLCode(language: LanguageCode): String = when (language) {
        LanguageCode.CHINESE_SIMPLIFIED -> "ZH-HANS"
        LanguageCode.CHINESE_TRADITIONAL -> "ZH-HANT"
        else -> language.tag.uppercase()
    }

    private fun toWebCode(language: LanguageCode): String = when (language) {
        LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.CHINESE_TRADITIONAL -> "zh"
        else -> language.tag.substringBefore('-').lowercase()
    }

    private fun fromDeepLCode(code: String): LanguageCode = when (code.uppercase()) {
        "NB" -> LanguageCode.NORWEGIAN
        "ZH", "ZH-HANS" -> LanguageCode.CHINESE_SIMPLIFIED
        "ZH-HANT" -> LanguageCode.CHINESE_TRADITIONAL
        else -> LanguageCode(code.lowercase())
    }

    companion object {
        private const val WEB_ENDPOINT = "https://oneshot-free.www.deepl.com/v1/translate"
        private const val FREE_RATE_LIMIT_MESSAGE =
            "DeepL free endpoint is rate-limited. Add an API key for official access or try again later."
        private const val MAX_WEB_CHARACTERS = 5_000
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        private val SUPPORTED_LANGUAGES = setOf(
            LanguageCode.AUTO, LanguageCode.ARABIC, LanguageCode.BULGARIAN,
            LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.CHINESE_TRADITIONAL,
            LanguageCode.CZECH, LanguageCode.DANISH, LanguageCode.DUTCH,
            LanguageCode.ENGLISH, LanguageCode.ESTONIAN, LanguageCode.FINNISH,
            LanguageCode.FRENCH, LanguageCode.GERMAN, LanguageCode.GREEK,
            LanguageCode.HUNGARIAN, LanguageCode.INDONESIAN, LanguageCode.ITALIAN,
            LanguageCode.JAPANESE, LanguageCode.KOREAN, LanguageCode.LATVIAN,
            LanguageCode.LITHUANIAN, LanguageCode.NORWEGIAN, LanguageCode.POLISH,
            LanguageCode.PORTUGUESE, LanguageCode.ROMANIAN, LanguageCode.RUSSIAN,
            LanguageCode.SLOVAK, LanguageCode.SLOVENIAN, LanguageCode.SPANISH,
            LanguageCode.SWEDISH, LanguageCode.TURKISH, LanguageCode.UKRAINIAN
        )
    }
}
