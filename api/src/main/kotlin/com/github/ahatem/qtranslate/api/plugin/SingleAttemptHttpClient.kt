package com.github.ahatem.qtranslate.api.plugin

import com.github.michaelbull.result.Result

/**
 * An [HttpClient] that can also send a GET without the transport's own retry policy.
 *
 * ### Why this is a separate interface
 * The transport retries some failures before a caller ever sees them, and that is the right
 * default: a transient error often clears on its own, and leaving every caller to notice would
 * mean every caller reimplementing the same loop. A caller that owns a policy of its own over
 * those same failures needs the opposite, because retries happening underneath it, and the delay
 * before them, make the timing of the request something the caller cannot account for. Adding a
 * method to [HttpClient] for that would oblige every existing and third-party implementation to
 * grow one, including implementations whose transport does not retry at all and could only ignore
 * it. A capability only some transports provide belongs on an interface they can opt into instead.
 *
 * ### Using it
 * [getOnce] is a statement about what the transport is asked to do, not about what the wire sees:
 * a client that never retries has nothing to opt out of. A caller should therefore test for this
 * interface and fall back to [HttpClient.get] when the transport does not implement it:
 *
 * ```kotlin
 * val response = (client as? SingleAttemptHttpClient)?.getOnce(url) ?: client.get(url)
 * ```
 */
public interface SingleAttemptHttpClient : HttpClient {

    /**
     * Performs a single GET attempt, leaving the transport's retry policy out of it.
     *
     * The first response, or the first failure, is returned as it arrives and is not automatically
     * sent again. Everything else matches [HttpClient.get], so a rate limit still arrives as
     * [ServiceError.RateLimitError] carrying whatever `Retry-After` hint the server sent, and it is
     * then the caller's to act on.
     *
     * The parameters behave as they do on [HttpClient.get].
     */
    public suspend fun getOnce(
        url: String,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError>
}
