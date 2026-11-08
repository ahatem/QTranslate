package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SingleAttemptHttpClient
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError

/**
 * Routes requests by client id so the translator's two unofficial paths and the official endpoint
 * can be scripted independently, and records call counts plus the peak number of in-flight requests
 * for concurrency assertions.
 *
 * Both unofficial paths share one host, so the host no longer identifies them: the flat client is
 * the translator's first path, the sentence client is its second, and the spell checker's `dt=qc`
 * request also uses the sentence path but is counted separately so a translator assertion cannot be
 * satisfied by a spell check.
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
        { Err(ServiceError.NetworkError("no second path arranged")) },
    private val officialHandler: suspend (Int) -> Result<String, ServiceError> =
        { Err(ServiceError.NetworkError("no official arranged")) },
    /** Answers the spell checker; null serves it from [primaryHandler], as it always did. */
    private val spellHandler: (suspend (Int) -> Result<String, ServiceError>)? = null
) : TextHttpClient(), SingleAttemptHttpClient {

    private val lock = Any()
    private val requestLog = mutableListOf<String>()
    private val queryLog = mutableListOf<Map<String, Any?>>()
    private val officialBodyLog = mutableListOf<String>()
    private var primaryCallCount = 0
    private var fallbackCallCount = 0
    private var spellCallCount = 0
    private var officialCallCount = 0
    private var inFlightNow = 0
    private var maxInFlightNow = 0

    val urls: List<String> get() = synchronized(lock) { requestLog.toList() }

    /** What each request asked for, oldest first, so a test can assert the endpoint's parameters. */
    val queryParams: List<Map<String, Any?>> get() = synchronized(lock) { queryLog.toList() }
    val primaryCalls: Int get() = synchronized(lock) { primaryCallCount }

    /** Wire attempts against the translator's second path (the sentence client). */
    val fallbackCalls: Int get() = synchronized(lock) { fallbackCallCount }

    /** Wire attempts from the spell checker, which shares the sentence path but is not the translator. */
    val spellCalls: Int get() = synchronized(lock) { spellCallCount }

    val officialCalls: Int get() = synchronized(lock) { officialCallCount }

    /** The JSON bodies sent to the official endpoint, oldest first. */
    val officialBodies: List<String> get() = synchronized(lock) { officialBodyLog.toList() }

    val maxInFlight: Int get() = synchronized(lock) { maxInFlightNow }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        var result = attempt(url, queryParams)
        var retries = 0
        while (retries < TRANSPORT_RETRIES && result.getError() is ServiceError.RateLimitError) {
            result = attempt(url, queryParams)
            retries++
        }
        return result
    }

    override suspend fun getOnce(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = attempt(url, queryParams)

    /** Which path a request belongs to, decided by the client id rather than by the host. */
    private enum class Path { PRIMARY, SECONDARY, SPELL }

    private fun pathOf(queryParams: Map<String, Any?>): Path = when {
        queryParams["client"] == FLAT_CLIENT -> Path.PRIMARY
        queryParams["dt"] == SPELL_FEATURES -> Path.SPELL
        else -> Path.SECONDARY
    }

    private suspend fun attempt(
        url: String,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        val path = pathOf(queryParams)
        val index: Int
        synchronized(lock) {
            requestLog += url
            queryLog += queryParams
            inFlightNow++
            if (inFlightNow > maxInFlightNow) maxInFlightNow = inFlightNow
            index = when (path) {
                Path.PRIMARY -> primaryCallCount++
                Path.SECONDARY -> fallbackCallCount++
                Path.SPELL -> spellCallCount++
            }
        }
        return try {
            when (path) {
                Path.PRIMARY -> primaryHandler(index)
                Path.SECONDARY -> fallbackHandler(index)
                Path.SPELL -> (spellHandler ?: primaryHandler)(index)
            }
        } finally {
            synchronized(lock) { inFlightNow-- }
        }
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        val index: Int
        synchronized(lock) {
            requestLog += url
            officialBodyLog += body.orEmpty()
            inFlightNow++
            if (inFlightNow > maxInFlightNow) maxInFlightNow = inFlightNow
            index = officialCallCount++
        }
        return try {
            officialHandler(index)
        } finally {
            synchronized(lock) { inFlightNow-- }
        }
    }

    private companion object {
        // A rate limit gets one attempt plus two retries on the transport.
        const val TRANSPORT_RETRIES = 2

        /** The translator's first path. */
        const val FLAT_CLIENT = "dict-chrome-ex"

        /** What only the spell checker asks for, which marks its request on the shared path. */
        const val SPELL_FEATURES = "qc"
    }
}
