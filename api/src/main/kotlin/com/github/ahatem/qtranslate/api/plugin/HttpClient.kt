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
 * Every method returns a [Result] and never throws for an HTTP or network failure. Implementations
 * map transport problems onto [ServiceError] so a plugin can pass the error straight back and have
 * the application word it for the user.
 *
 * ### What is here and what is not
 * These are the calls that need the transport itself: text and binary responses, and the one body
 * encoding that cannot be expressed as a plain string. Everything else a plugin reaches for, JSON
 * in particular, is built on top of these as extension functions rather than widening the
 * contract, so adding a convenience never obliges an implementation to grow a method.
 */
public interface HttpClient {

    /**
     * Performs a GET request and returns the response body as text.
     *
     * A list value in [queryParams] is expanded into one repeated parameter per element. A null
     * value is omitted rather than sent empty.
     */
    public suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError>

    /**
     * Performs a POST request and returns the response body as text.
     *
     * [body] is sent as `application/json` unless [headers] carries a different `Content-Type`.
     */
    public suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError>

    /**
     * Performs a GET request and returns the response body as raw bytes.
     *
     * For audio, images and anything else that is not text. Decoding a binary body through
     * [get] would corrupt it, which is why this is a method rather than a convenience.
     */
    public suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<ByteArray, ServiceError>

    /**
     * Performs a POST request with an `application/x-www-form-urlencoded` body.
     *
     * Form encoding is here rather than left to callers because getting it subtly wrong, in the
     * escaping of spaces or reserved characters, produces a request that a server accepts and
     * misreads. [cookies] is supported because several providers require a session cookie
     * alongside the form.
     */
    public suspend fun postForm(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap(),
        cookies: Map<String, String> = emptyMap()
    ): Result<String, ServiceError>

    /** As [postForm], for a response that is binary rather than text. */
    public suspend fun postFormBytes(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap(),
        cookies: Map<String, String> = emptyMap()
    ): Result<ByteArray, ServiceError>
}
