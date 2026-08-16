package com.github.ahatem.qtranslate.api.plugin

import com.github.michaelbull.result.Result

/**
 * Makes HTTP requests on a plugin's behalf.
 *
 * ### Why this is part of the API
 * Reaching the network is the single most common thing a plugin does, and until this moved here it
 * was the one thing the API declined to describe: the interface lived in a bundled helper module,
 * so a third-party plugin either depended on something that was not the published API or shipped
 * its own HTTP stack inside its JAR. Neither is a contract.
 *
 * Stating it here is also what lets the application own the transport. Settings that have to apply
 * to everything, a proxy above all, are only reliable if plugins receive a client rather than
 * building their own, because a plugin cannot then opt out of them by accident.
 *
 * ### Errors, not exceptions
 * Both methods return a [Result] and never throw for an HTTP or network failure. Implementations
 * map transport problems onto [ServiceError] so a plugin can pass the error straight back and have
 * the application word it for the user.
 */
interface HttpClient {

    /**
     * Performs a GET request and returns the response body as text.
     *
     * A list value in [queryParams] is expanded into one repeated parameter per element. A null
     * value is omitted rather than sent empty.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError>

    /**
     * Performs a POST request and returns the response body as text.
     *
     * [body] is sent as `application/json` unless [headers] carries a different `Content-Type`.
     */
    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError>
}
