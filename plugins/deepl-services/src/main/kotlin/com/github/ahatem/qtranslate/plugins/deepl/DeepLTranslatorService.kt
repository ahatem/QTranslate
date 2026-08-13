package com.github.ahatem.qtranslate.plugins.deepl

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.HttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.toResultOr
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.random.Random

internal class DeepLTranslatorService(
    private val context: PluginContext,
    private val httpClient: HttpClient,
    private val settings: () -> DeepLSettings,
    private val onModeChanged: (DeepLMode) -> Unit = {},
    private val minimumWebRequestIntervalMillis: Long = 750,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nextRequestId: () -> Long = { Random.nextLong(100_000, 1_000_000) * 1_000 }
) : Translator {
    override val id = "deepl-services-translator"
    override val name = "DeepL"
    override val version = "1.1.0"
    override val supportedLanguages = SupportedLanguages.Specific(SUPPORTED_LANGUAGES)

    private val officialParser = createJsonParser<DeepLTranslateResponse>(context)
    private val webParser = createJsonParser<DeepLWebResponse>(context)
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
                    title = "DeepL API key rejected",
                    body = "Using the free web endpoint. Update the API key in plugin settings to restore official access.",
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
        val translation = officialParser.parse(responseText).bind().translations.firstOrNull()
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
                val id = nextRequestId()
                val body = json.encodeToString(DeepLWebRequest(
                    id = id,
                    params = DeepLWebParams(
                        lang = DeepLWebLanguage(
                            sourceLanguage = if (request.sourceLanguage == LanguageCode.AUTO) "auto" else toWebCode(request.sourceLanguage),
                            targetLanguage = toWebCode(request.targetLanguage)
                        ),
                        texts = listOf(DeepLWebText(request.text)),
                        timestamp = webTimestamp(request.text)
                    )
                )).withMethodSpacing(id)

                val responseBody = httpClient.post(
                    url = WEB_ENDPOINT,
                    headers = ApiConfig().createJsonHeaders(mapOf(
                        "Origin" to "https://www.deepl.com",
                        "Referer" to "https://www.deepl.com/"
                    )),
                    body = body,
                    queryParams = mapOf("method" to "LMT_handle_texts")
                ).mapError(::mapWebHttpError).bind()
                lastWebRequestAtNanos = System.nanoTime()

                val response = webParser.parse(responseBody).bind()
                response.error?.let { error ->
                    if (error.code in WEB_RATE_LIMIT_CODES || error.message.contains("too many requests", true)) {
                        Err(ServiceError.RateLimitError(
                            FREE_RATE_LIMIT_MESSAGE
                        )).bind<String>()
                    }
                    Err(ServiceError.ServiceUnavailableError(
                        "DeepL's free endpoint returned error ${error.code}: ${error.message}. " +
                            "The unofficial endpoint may have changed."
                    )).bind<String>()
                }

                val result = response.result
                    .toResultOr { ServiceError.InvalidResponseError(
                        "DeepL's free endpoint returned an invalid response. It may have changed."
                    ) }.bind()
                val translation = result.texts.firstOrNull { it.text.isNotBlank() }
                    .toResultOr { ServiceError.InvalidResponseError(
                        "DeepL's free endpoint returned no translated text. It may have changed."
                    ) }.bind()

                TranslationResponse(
                    translatedText = translation.text,
                    detectedLanguage = result.lang?.let(::fromDeepLCode),
                    alternatives = translation.alternatives.mapNotNull { it.text.takeIf(String::isNotBlank) }
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

    private fun webTimestamp(text: String): Long {
        val iCount = text.count { it == 'i' } + 1
        val now = nowMillis()
        return if (iCount == 1) now else now - (now % iCount) + iCount
    }

    private fun String.withMethodSpacing(id: Long): String = when {
        (id + 5) % 29L == 0L || (id + 3) % 13L == 0L -> replaceFirst("\"method\":", "\"method\" : ")
        else -> replaceFirst("\"method\":", "\"method\": ")
    }

    private fun toDeepLCode(language: LanguageCode): String = when (language) {
        LanguageCode.CHINESE_SIMPLIFIED -> "ZH-HANS"
        LanguageCode.CHINESE_TRADITIONAL -> "ZH-HANT"
        else -> language.tag.uppercase()
    }

    private fun toWebCode(language: LanguageCode): String = when (language) {
        LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.CHINESE_TRADITIONAL -> "ZH"
        else -> language.tag.substringBefore('-').uppercase()
    }

    private fun fromDeepLCode(code: String): LanguageCode = when (code.uppercase()) {
        "NB" -> LanguageCode.NORWEGIAN
        "ZH", "ZH-HANS" -> LanguageCode.CHINESE_SIMPLIFIED
        "ZH-HANT" -> LanguageCode.CHINESE_TRADITIONAL
        else -> LanguageCode(code.lowercase())
    }

    companion object {
        private const val WEB_ENDPOINT = "https://www2.deepl.com/jsonrpc"
        private const val FREE_RATE_LIMIT_MESSAGE =
            "DeepL free endpoint is rate-limited. Add an API key for official access or try again later."
        private const val MAX_WEB_CHARACTERS = 5_000
        private val WEB_RATE_LIMIT_CODES = setOf(1_042_911, 1_042_912)
        private val json = Json { ignoreUnknownKeys = true }

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
