package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.getOrElse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.Boolean
import kotlin.Long
import kotlin.String
import kotlin.fold
import kotlin.let
import kotlin.require
import kotlin.runCatching
import kotlin.time.Duration.Companion.hours

/**
 * Thread-safe manager for Bing authentication tokens. Tokens are cached for their nominal
 * one hour lifetime, refreshed by at most one caller at a time, and can be dropped the moment
 * the endpoint rejects them, so a token the server no longer accepts is not served again.
 */
class BingAuthManager(
    private val pluginContext: PluginContext,
    private val httpClient: HttpClient,
    /** Injectable clock so expiry is testable without waiting an hour. */
    private val now: () -> Long = System::currentTimeMillis
) {
    private val authRef = AtomicReference<AuthState?>(null)

    /**
     * Serializes refreshes. Without it, a burst of translations arriving while the cache is empty
     * or expired sends every caller to scrape the page, and the last writer would overwrite the
     * token an earlier fetch had already stored.
     */
    private val refreshMutex = Mutex()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getAuth(): Result<BingAuth, ServiceError> {
        authRef.get()?.takeIf { !it.isExpired() }?.let { return Ok(it.auth) }
        return refreshAuth()
    }

    /**
     * Drops the cached token, but only if it is still the one the caller used. A token that was
     * rejected by the endpoint must not keep being handed out; a newer token a concurrent refresh
     * already stored has never failed anything and stays.
     */
    fun invalidateIfCurrent(auth: BingAuth) {
        authRef.update { current -> if (current?.auth == auth) null else current }
    }

    private suspend fun refreshAuth(): Result<BingAuth, ServiceError> {
        refreshMutex.withLock {
            // Re-checked under the lock: a refresh that completed while this caller waited has
            // already stored a usable token, so the page is scraped once for the burst, not once
            // per caller.
            authRef.get()?.takeIf { !it.isExpired() }?.let { return Ok(it.auth) }

            return coroutineBinding {
                pluginContext.logger.info("Fetching new Bing authentication token")

                val html = httpClient.get(
                    url = "https://www.bing.com/translator",
                    headers = ApiConfig().createHeaders()
                ).bind()

                val auth = parseAuthFromHtml(html).bind()
                authRef.set(AuthState(auth, now()))

                pluginContext.logger.info("Successfully obtained Bing authentication token")
                auth
            }
        }
    }

    private fun parseAuthFromHtml(html: String): Result<BingAuth, ServiceError> {
        val ig = extractPattern(html, """IG:"(.*?)"""", "IG").getOrElse { return Err(it) }
        val iid = extractPattern(html, """data-iid="(.*?)"""", "IID").getOrElse { return Err(it) }
        val helperInfo = extractPattern(html, """params_AbusePreventionHelper = (.*?);""", "helper info")
            .getOrElse { return Err(it) }

        // Extract additional authentication data
        val muid = extractPattern(html, """muid":\s*"(.*?)"""", "MUID").getOr("")
        val sid = extractPattern(html, """sid":\s*"(.*?)"""", "SID").getOr("")
        val tid = extractPattern(html, """tid":\s*"(.*?)"""", "TID").getOr("")

        return runCatching {
            val jsonElement = json.parseToJsonElement(helperInfo)
            val helperArray = jsonElement.jsonArray
            require(helperArray.size >= 2) { "Invalid helper info format" }

            BingAuth(
                ig = ig,
                iid = iid,
                key = helperArray[0].toString(),
                token = helperArray[1].toString().removeSurrounding("\""),
                muid = muid,
                sid = sid,
                tid = tid
            )
        }.fold(
            onSuccess = { Ok(it) },
            onFailure = { Err(ServiceError.InvalidResponseError("Failed to parse auth data: ${it.message}", it)) }
        )
    }

    private fun extractPattern(html: String, pattern: String, fieldName: String): Result<String, ServiceError> =
        Regex(pattern).find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { Ok(it) }
            ?: Err(ServiceError.InvalidResponseError("Failed to extract $fieldName from Bing page", null))

    private inner class AuthState(val auth: BingAuth, val timestamp: Long) {
        fun isExpired(): Boolean = (now() - timestamp) >= 1.hours.inWholeMilliseconds
    }
}

/** Compare-and-swap for [AtomicReference], so invalidation cannot clobber a concurrent refresh. */
private inline fun <T> AtomicReference<T>.update(transform: (T) -> T) {
    while (true) {
        val current = get()
        val next = transform(current)
        if (compareAndSet(current, next)) return
    }
}
