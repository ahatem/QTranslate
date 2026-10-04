package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.PluginJson
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Reads the translate endpoint, which does not always answer with a translation.
 *
 * Bing can return an error object with HTTP 200, and non-JSON responses may be temporary
 * interstitial pages. The shape of the body is the only thing that separates a translation from
 * a failure, so the root is inspected before decoding: decoding an error object as a translation
 * array fails on the first character and blames the payload for it.
 */
internal class BingResponseParser(private val logger: Logger) {

    fun parseTranslations(body: String): Result<List<BingTranslateResponse>, ServiceError> {
        val root = runCatching { PluginJson.parseToJsonElement(body) }.getOrElse { failure ->
            return Err(unreadable(body, failure))
        }

        return when (root) {
            is JsonArray -> decodeTranslations(root)
            is JsonObject ->
                if (isProviderError(root)) Err(rejection(root)) else Err(unexpected())
            else -> Err(unexpected())
        }
    }

    private fun decodeTranslations(
        body: JsonArray
    ): Result<List<BingTranslateResponse>, ServiceError> =
        runCatching { PluginJson.decodeFromJsonElement<List<BingTranslateResponse>>(body) }
            .fold(
                onSuccess = { Ok(it) },
                onFailure = { failure ->
                    logger.error("Bing translation array could not be decoded", failure)
                    Err(ServiceError.InvalidResponseError(UNREADABLE, failure))
                }
            )

    /**
     * A body that opens with JSON but does not parse is a payload Bing could not deliver. One that
     * never looked like JSON is an interstitial page, which clears on its own.
     */
    private fun unreadable(body: String, failure: Throwable): ServiceError {
        val opening = body.trimStart().take(1)
        return if (opening == "{" || opening == "[") {
            logger.error("Bing returned a malformed JSON body", failure)
            ServiceError.InvalidResponseError(UNREADABLE, failure)
        } else {
            // Response bodies are intentionally not logged.
            logger.warn("Bing returned a body that is not JSON")
            ServiceError.ServiceUnavailableError(UNEXPECTED, failure)
        }
    }

    /**
     * Only Bing's own error fields make an object a rejection. An unknown object is an unexpected
     * shape, and reporting it as a rejection would assert something the provider never said.
     */
    private fun isProviderError(body: JsonObject): Boolean =
        STATUS_CODE in body || ERROR_MESSAGE in body

    /** The status is the dependable part; errorMessage is often empty. */
    private fun rejection(body: JsonObject): ServiceError {
        val status = body.stringField(STATUS_CODE)
        val detail = body.stringField(ERROR_MESSAGE)?.let(::summarise)

        logger.warn("Bing rejected the request with status ${status ?: UNKNOWN_STATUS}")

        val message = when {
            status != null && detail != null -> "$REJECTED (status $status): $detail"
            status != null -> "$REJECTED (status $status)."
            detail != null -> "$REJECTED: $detail"
            else -> "$REJECTED."
        }
        return ServiceError.InvalidResponseError(message)
    }

    private fun unexpected(): ServiceError {
        logger.warn("Bing returned an unexpected JSON response")
        return ServiceError.InvalidResponseError(UNEXPECTED)
    }

    /** Reads a field as text whether the provider quoted it or not, so a numeric status still reads. */
    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun summarise(message: String): String? =
        message.replace(WHITESPACE, " ").trim().takeIf { it.isNotEmpty() }?.take(MAX_DETAIL)

    private companion object {
        const val REJECTED = "Bing Translate rejected the request"
        const val UNEXPECTED = "Bing Translate returned an unexpected response. Please try again."
        const val UNREADABLE = "Bing Translate returned an unreadable response."
        const val UNKNOWN_STATUS = "unknown"
        const val STATUS_CODE = "statusCode"
        const val ERROR_MESSAGE = "errorMessage"
        const val MAX_DETAIL = 200
        val WHITESPACE = Regex("\\s+")
    }
}
