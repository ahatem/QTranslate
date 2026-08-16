package com.github.ahatem.qtranslate.plugins.yandexweb

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class YandexWebClient(
    private val httpClient: HttpClient,
    private val minimumIntervalMillis: Long = 750,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    private val requestMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var lastRequestStartedAt = 0L

    suspend fun translate(text: String, targetLanguage: String): Result<YandexWebResponse, ServiceError> =
        requestMutex.withLock {
            val remainingDelay = minimumIntervalMillis - (clockMillis() - lastRequestStartedAt)
            if (lastRequestStartedAt != 0L && remainingDelay > 0) wait(remainingDelay)
            lastRequestStartedAt = clockMillis()

            val direct = httpClient.post(
                url = ENDPOINT,
                headers = HEADERS,
                body = formBody(text, targetLanguage)
            ).fold(
                success = ::parseResponse,
                failure = { Err(mapTransportError(it)) }
            )
            var directError: ServiceError? = null
            direct.fold(
                success = { return@withLock Ok(it) },
                failure = { directError = it }
            )
            val error = requireNotNull(directError)
            if (error !is ServiceError.InvalidResponseError && error !is ServiceError.ServiceUnavailableError) {
                return@withLock Err(error)
            }
            translateViaMozhi(text, targetLanguage).fold(
                success = { Ok(it) },
                failure = { Err(error) }
            )
        }

    private suspend fun translateViaMozhi(
        text: String,
        targetLanguage: String
    ): Result<YandexWebResponse, ServiceError> {
        for (endpoint in MOZHI_FALLBACKS) {
            val response = httpClient.get(
                url = "$endpoint/api/translate",
                queryParams = mapOf(
                    "engine" to "yandex",
                    "from" to "auto",
                    "to" to targetLanguage,
                    "text" to text
                )
            ).fold(
                success = { body ->
                    runCatching { json.decodeFromString<MozhiYandexResponse>(body) }.fold(
                        onSuccess = { parsed ->
                            if (parsed.translatedText.isNotBlank()) {
                                Ok(YandexWebResponse(parsed.translatedText, parsed.sourceLanguage, targetLanguage))
                            } else {
                                Err(endpointChangedError())
                            }
                        },
                        onFailure = { Err(endpointChangedError(it)) }
                    )
                },
                failure = { Err(it) }
            )
            var translated: YandexWebResponse? = null
            response.fold(success = { translated = it }, failure = {})
            translated?.let { return Ok(it) }
        }
        return Err(ServiceError.ServiceUnavailableError(
            "Yandex Web and its privacy-preserving fallback instances are unavailable. Try again later."
        ))
    }

    private fun parseResponse(body: String): Result<YandexWebResponse, ServiceError> =
        runCatching { json.decodeFromString<List<YandexWebResponse>>(body).firstOrNull() }
            .fold(
                onSuccess = { response ->
                    if (response != null && response.text.isNotBlank()) Ok(response)
                    else Err(endpointChangedError())
                },
                onFailure = { Err(endpointChangedError(it)) }
            )

    private fun mapTransportError(error: ServiceError): ServiceError = when (error) {
        is ServiceError.RateLimitError -> ServiceError.RateLimitError(
            "Yandex Web is rate-limited. This unofficial free endpoint may be temporarily unavailable; try again later.",
            error.retryAfterSeconds,
            error.cause
        )
        is ServiceError.AuthenticationError -> ServiceError.ServiceUnavailableError(
            "Yandex Web rejected the request. This unofficial free endpoint may have changed or may be temporarily blocking requests.",
            error.cause
        )
        is ServiceError.ServiceUnavailableError -> ServiceError.ServiceUnavailableError(
            "Yandex Web is unavailable. This unofficial free endpoint may have changed; try again later.",
            error.cause
        )
        else -> error
    }

    private fun endpointChangedError(cause: Throwable? = null) = ServiceError.InvalidResponseError(
        "Yandex Web returned an unexpected response. The unofficial free endpoint may have changed.",
        cause
    )

    private fun formBody(text: String, targetLanguage: String): String = mapOf(
        "text" to text,
        "brandID" to "int",
        "statLang" to targetLanguage,
        "targetLang" to "auto",
        "locale" to targetLanguage,
        "clid" to "2270494",
        "disable" to "serp",
        "use_llm_srv" to "0"
    ).entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val ENDPOINT = "https://api.browser.yandex.com/instaserp/translate"
        val HEADERS = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/x-www-form-urlencoded",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 " +
                "YaBrowser/26.3.0.0 Safari/537.36"
        )
        val MOZHI_FALLBACKS = listOf(
            "https://mozhi.adminforge.de",
            "https://mozhi.pussthecat.org"
        )
    }
}
