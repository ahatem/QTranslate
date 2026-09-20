package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result

internal class GoogleTestHttpClient(
    private val primaryHandler: suspend (Int) -> Result<String, ServiceError>,
    private val fallbackHandler: suspend (Int) -> Result<String, ServiceError> =
        { Err(ServiceError.NetworkError("no fallback arranged")) }
) : TextHttpClient() {

    private val lock = Any()
    private var primaryCallCount = 0
    private var fallbackCallCount = 0

    val primaryCalls: Int get() = synchronized(lock) { primaryCallCount }
    val fallbackCalls: Int get() = synchronized(lock) { fallbackCallCount }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        val isFallback = url.contains("clients5.google.com")
        val index: Int
        synchronized(lock) {
            index = if (isFallback) fallbackCallCount++ else primaryCallCount++
        }
        return if (isFallback) fallbackHandler(index) else primaryHandler(index)
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = Err(ServiceError.InvalidInputError("unexpected POST to $url"))
}
