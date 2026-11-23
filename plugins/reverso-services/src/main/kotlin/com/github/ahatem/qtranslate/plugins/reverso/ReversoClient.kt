package com.github.ahatem.qtranslate.plugins.reverso

import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.plugins.common.RequestPacer
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.mapError
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class ReversoClient(
    private val context: PluginContext,
    private val httpClient: HttpClient,
    private val minimumRequestIntervalMillis: Long = 600
) {
    private val textParser = createJsonParser<ReversoTextResponse>(context)
    private val wordParser = createJsonParser<ReversoWordResponse>(context)
    private val pacer = RequestPacer(minimumRequestIntervalMillis)

    suspend fun translate(request: ReversoTextRequest): Result<ReversoTextResponse, ServiceError> =
        send(TRANSLATE_TEXT, Json.encodeToString(request), textParser::parse)

    suspend fun lookup(request: ReversoWordRequest): Result<ReversoWordResponse, ServiceError> =
        send(TRANSLATE_WORD, Json.encodeToString(request), wordParser::parse)

    private suspend fun <T> send(
        endpoint: String,
        body: String,
        parse: suspend (String) -> Result<T, ServiceError>
    ): Result<T, ServiceError> = pacer.paced {
        coroutineBinding {
            val response = httpClient.post(
                url = "$BASE_URL$endpoint",
                headers = ApiConfig.createJsonHeaders(),
                body = body
            ).mapError(::mapHttpError).bind()
            pacer.markSent()
            parse(response).bind()
        }
    }

    fun providerError(message: String): ServiceError {
        val detail = message.ifBlank { "The service returned no result." }
        return if (detail.contains("too many", true) || detail.contains("rate", true)) {
            ServiceError.RateLimitError(
                "Reverso's free service is rate-limited. Try again later."
            )
        } else {
            ServiceError.ServiceUnavailableError(
                "Reverso's free service could not complete the request: $detail"
            )
        }
    }

    private fun mapHttpError(error: ServiceError): ServiceError = when (error) {
        is ServiceError.RateLimitError -> ServiceError.RateLimitError(
            message = "Reverso's free service is rate-limited. Try again later or choose another service.",
            retryAfterSeconds = error.retryAfterSeconds,
            cause = error.cause
        )
        is ServiceError.AuthenticationError,
        is ServiceError.ServiceUnavailableError -> ServiceError.ServiceUnavailableError(
            "Reverso's free service rejected the request. It may be temporarily unavailable or its endpoint may have changed.",
            error.cause
        )
        else -> error
    }

    private companion object {
        const val BASE_URL = "https://cps.reverso.net/api2/"
        const val TRANSLATE_TEXT = "TranslateText"
        const val TRANSLATE_WORD = "TranslateWord"
    }
}
