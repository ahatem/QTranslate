package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SingleAttemptHttpClient
import com.github.michaelbull.result.Result

/**
 * Performs a GET without the transport's retry policy, where the transport can opt out of it.
 *
 * A transport that retries on its own, and waits before each retry, puts that delay between the
 * caller and a failure the caller already knows how to act on. A caller that owns a policy over
 * those same failures wants the first answer instead, so this sends one attempt when the transport
 * offers that. A transport that cannot opt out, because it does not retry or does not say so, still
 * answers the call: it degrades to an ordinary [HttpClient.get] and the caller sees whatever that
 * transport would have returned.
 *
 * The cast is what keeps this from calling itself. It narrows the receiver to the interface that
 * declares `getOnce` as a member, and a member always wins over an extension of the same name, so
 * the member is chosen over this function.
 */
suspend fun HttpClient.getOnce(
    url: String,
    headers: Map<String, String> = emptyMap(),
    queryParams: Map<String, Any?> = emptyMap()
): Result<String, ServiceError> =
    (this as? SingleAttemptHttpClient)?.getOnce(url, headers, queryParams)
        ?: get(url, headers, queryParams)
