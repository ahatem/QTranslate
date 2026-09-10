package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SingleAttemptHttpClient
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError

/**
 * Routes requests by host so the primary and fallback endpoints can be scripted independently, and
 * records call counts plus the peak number of in-flight requests for concurrency assertions.
 *
 * It also mirrors the transport's retry policy. The transport retries a 429 up to twice, so a plain
 * [get] that meets a rate limit calls the handler three times in all before returning the last
 * result, while [getOnce] sends a single attempt and hands back the first answer. Every attempt the
 * handler serves is counted, so [primaryCalls] and [fallbackCalls] are wire attempts rather than
 * call sites: the same rate limited request counts three through [get] and one through [getOnce].
 */
internal class GoogleTestHttpClient(
    private val primaryHandler: suspend (Int) -> Result<String, ServiceError>,
    private val fallbackHandler: suspend (Int) -> Result<String, ServiceError> =
        { Err(ServiceError.NetworkError("no fallback arranged")) }
) : TextHttpClient(), SingleAttemptHttpClient {

    private val lock = Any()
    private val requestLog = mutableListOf<String>()
    private var primaryCallCount = 0
    private var fallbackCallCount = 0
    private var inFlightNow = 0
    private var maxInFlightNow = 0

    val urls: List<String> get() = synchronized(lock) { requestLog.toList() }
    val primaryCalls: Int get() = synchronized(lock) { primaryCallCount }
    val fallbackCalls: Int get() = synchronized(lock) { fallbackCallCount }
    val maxInFlight: Int get() = synchronized(lock) { maxInFlightNow }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        var result = attempt(url)
        var retries = 0
        while (retries < TRANSPORT_RETRIES && result.getError() is ServiceError.RateLimitError) {
            result = attempt(url)
            retries++
        }
        return result
    }

    override suspend fun getOnce(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = attempt(url)

    private suspend fun attempt(url: String): Result<String, ServiceError> {
        val isFallback = url.contains("clients5.google.com")
        val index: Int
        synchronized(lock) {
            requestLog += url
            inFlightNow++
            if (inFlightNow > maxInFlightNow) maxInFlightNow = inFlightNow
            index = if (isFallback) fallbackCallCount++ else primaryCallCount++
        }
        return try {
            if (isFallback) fallbackHandler(index) else primaryHandler(index)
        } finally {
            synchronized(lock) { inFlightNow-- }
        }
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = Err(ServiceError.InvalidInputError("unexpected POST to $url"))

    private companion object {
        // A rate limit gets one attempt plus two retries on the transport.
        const val TRANSPORT_RETRIES = 2
    }
}
