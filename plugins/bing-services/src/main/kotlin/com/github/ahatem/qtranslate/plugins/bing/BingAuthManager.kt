package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.PluginContext
import com.github.ahatem.qtranslate.api.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.getOrElse
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
 * Thread-safe manager for Bing authentication tokens.
 * Tokens are automatically refreshed when expired (1 hour lifetime).
 */
class BingAuthManager(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient
) {
    private val authRef = AtomicReference<AuthState?>(null)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getAuth(): Result<BingAuth, ServiceError> {
        val current = authRef.get()

        return when {
            current != null && !current.isExpired() -> Ok(current.auth)
            else -> refreshAuth()
        }
    }

    private suspend fun refreshAuth(): Result<BingAuth, ServiceError> = coroutineBinding {
        pluginContext.logInfo("Fetching new Bing authentication token")

        val html = httpClient.get(
            url = "https://www.bing.com/translator",
            headers = ApiConfig().createHeaders()
        ).bind()

        val auth = parseAuthFromHtml(html).bind()
        authRef.set(AuthState(auth, System.currentTimeMillis()))

        pluginContext.logInfo("Successfully obtained Bing authentication token")
        auth
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

    private data class AuthState(val auth: BingAuth, val timestamp: Long) {
        fun isExpired(): Boolean = (System.currentTimeMillis() - timestamp) >= 1.hours.inWholeMilliseconds
    }
}
