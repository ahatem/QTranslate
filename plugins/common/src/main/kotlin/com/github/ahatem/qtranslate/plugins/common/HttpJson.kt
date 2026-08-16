package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kotlinx.serialization.json.Json

/**
 * JSON convenience over the [HttpClient] contract.
 *
 * These were methods on the Ktor implementation, which meant a plugin wanting to send JSON had to
 * depend on that concrete class rather than on the interface, and a second implementation would
 * have had to reimplement them. They are pure composition over `get` and `post`, so they belong
 * out here: the contract stays small, and adding another convenience never obliges an
 * implementation to grow a method.
 */

/**
 * The JSON configuration every plugin gets.
 *
 * Lenient on purpose. These are third-party APIs that add fields without warning, and a strict
 * parser turns a harmless addition into a broken service.
 */
val PluginJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Performs a GET request and parses the JSON response into [T]. */
suspend inline fun <reified T> HttpClient.fetchJson(
    url: String,
    headers: Map<String, String> = emptyMap(),
    queryParams: Map<String, Any> = emptyMap()
): Result<T, ServiceError> {
    val responseString = get(url, headers, queryParams).getOrElse { return Err(it) }

    return runCatching {
        Ok(PluginJson.decodeFromString<T>(responseString))
    }.getOrElse { error ->
        Err(ServiceError.InvalidResponseError("Failed to parse JSON response", error))
    }
}

/** Performs a POST request with [body] encoded as a JSON document. */
suspend inline fun <reified T> HttpClient.sendJson(
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: T? = null,
    queryParams: Map<String, Any?> = emptyMap()
): Result<String, ServiceError> {
    val encodedBody = body?.let { PluginJson.encodeToString(it) }
    val jsonHeaders = headers + ("Content-Type" to "application/json")
    return post(url, jsonHeaders, encodedBody, queryParams)
}
