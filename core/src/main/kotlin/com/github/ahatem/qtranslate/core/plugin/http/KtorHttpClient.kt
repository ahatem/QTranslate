package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.ktor.client.*
// Both this file's supertype and Ktor's own client are called HttpClient. Ours is now imported by
// name, which beats the star import below, so Ktor's needs an alias to stay reachable.
import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.call.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.reflect.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.Base64

/**
 * The methods RFC 9110 defines as idempotent: sending one twice has the same effect as once, so a
 * response that may or may not have been acted on can safely be retried.
 */
private val IDEMPOTENT_METHODS = setOf(
    HttpMethod.Get, HttpMethod.Head, HttpMethod.Options, HttpMethod.Put, HttpMethod.Delete
)

private const val TOO_MANY_REQUESTS = 429

internal class KtorHttpClient(
    private val logger: Logger,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    },
    private val config: HttpClientConfig = HttpClientConfig(),
    /**
     * The engine to send on.
     *
     * Injectable so the retry, proxy and timeout rules can be tested against a scripted engine
     * rather than a live server. They are the kind of behaviour that is invisible until it is
     * wrong in production: nothing about a POST being replayed after a 500 shows up locally, and
     * the bill for it arrives later. Production still gets CIO, built here by default.
     */
    private val engine: HttpClientEngine = CIO.create {
        config.proxy?.let { proxy = ProxyBuilder.http(it.url) }
        maxConnectionsCount = config.maxConnectionsTotal
        endpoint.maxConnectionsPerRoute = config.maxConnectionsPerHost
    },
    /**
     * Whether closing this also closes [engine]. False when the caller supplied one, since a test
     * that shares an engine across clients should decide for itself when it dies.
     */
    private val ownsEngine: Boolean = true
) : HttpClient, Closeable {

    private val client = KtorClient(engine) {
        install(ContentNegotiation) {
            json(json)
        }
        // Attached to every request, which is the only place it works. CIO's startTunnel copies
        // Proxy-Authorization onto the CONNECT it sends for an HTTPS request, but only when the
        // header is already on the request; it never derives one from the proxy address. A plain
        // HTTP request goes to the proxy directly and carries the header in its ordinary place.
        // One installation covers both.
        config.proxy?.authorizationHeader()?.let { credentials ->
            install(DefaultRequest) {
                header(HttpHeaders.ProxyAuthorization, credentials)
            }
        }
        install(ContentEncoding) {
            gzip()
            deflate()
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            connectTimeoutMillis = config.connectTimeoutMillis
            socketTimeoutMillis = config.socketTimeoutMillis
        }
        if (config.enableRetry) {
            install(HttpRequestRetry) {
                // Not retryOnServerErrors, which retries every 5xx whatever the method. A 5xx may
                // mean the server did the work and failed to say so, and replaying a POST then
                // does it twice: a second translation billed, a second quota unit spent, and for
                // anything with a real side effect, worse. Only the methods RFC 9110 defines as
                // idempotent are safe to send again on a 5xx.
                //
                // 429 is different and is retried for any method: it says the server refused the
                // request without acting on it, so there is nothing to duplicate. It is also the
                // status a translation API actually returns under load, and the old rule excluded
                // it entirely — the one case worth retrying was the one case that never was.
                retryIf(maxRetries = config.maxRetries) { request, response ->
                    val status = response.status.value
                    status == TOO_MANY_REQUESTS || (status in 500..599 && request.method in IDEMPOTENT_METHODS)
                }
                // Honours Retry-After when the server sends it, falling back to backoff when it
                // does not. Retrying sooner than a rate limiter asked for is how a rate limit
                // becomes a longer one.
                exponentialDelay(baseDelayMs = config.retryInitialDelayMillis.coerceAtLeast(1))
            }
        }
    }

    /**
     * Applies whatever [HttpClientConfig.hostTimeouts] says about [url]'s host, if anything.
     *
     * One client serves every host. `HttpTimeout` merges a per-request value over the client-wide
     * one with `?:`, so a host that names only a socket timeout keeps the shared request and
     * connect values rather than silently resetting them to the plugin's defaults. That merge is
     * also why per-host timeouts need no second client and no second connection pool: the pooling
     * question is a separate one, and this does not decide it.
     */
    private fun HttpRequestBuilder.applyHostTimeout(url: String) {
        if (config.hostTimeouts.isEmpty()) return
        val host = runCatching { Url(url).host }.getOrNull() ?: return
        val override = config.hostTimeouts.entries
            .firstOrNull { it.key.equals(host, ignoreCase = true) }
            ?.value
            ?: return

        timeout {
            override.requestTimeoutMillis?.let { requestTimeoutMillis = it }
            override.connectTimeoutMillis?.let { connectTimeoutMillis = it }
            override.socketTimeoutMillis?.let { socketTimeoutMillis = it }
        }
    }

    // Main POST method with flexible content type support
    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.post(url) {
                applyHostTimeout(url)
                headers.forEach { (key, value) -> header(key, value) }
                queryParams.forEach { (key, value) ->
                    value?.let { parameter(key, it.toString()) }
                }
                if (body != null) {
                    // Default to JSON if no content-type specified
                    val contentType = headers["Content-Type"] ?: "application/json"
                    contentType(ContentType.parse(contentType))
                    setBody(body)
                }
            }
            handleResponse(response, url)
        } catch (e: HttpRequestTimeoutException) {
            logger.error("POST request timeout for $url", e)
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            logger.error("POST request failed for $url", e)
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.get(url) {
                applyHostTimeout(url)
                headers.forEach { (key, value) -> header(key, value) }
                queryParams.forEach { (key, value) ->
                    parametersOf()
                    when (value) {
                        is List<*> -> value.forEach { item -> item?.let { parameter(key, it.toString()) } }
                        else -> value?.let { parameter(key, it.toString()) }
                    }
                }
            }
            handleResponse(response, url)
        } catch (e: HttpRequestTimeoutException) {
            logger.error("GET request timeout for $url", e)
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            logger.error("GET request failed for $url", e)
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }

    // ========== FORM DATA UTILITY METHODS ==========

    /**
     * Performs a POST request with form-urlencoded data.
     */
    override suspend fun postForm(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>,
        cookies: Map<String, String>
    ): Result<String, ServiceError> = withContext(Dispatchers.IO) {
        try {

            val response: HttpResponse = client.post(url) {
                applyHostTimeout(url)
                cookies.forEach { (key, value) -> cookie(key, value) }

                headers.forEach { (key, value) -> header(key, value) }
                queryParams.forEach { (key, value) ->
                    value?.let { parameter(key, it.toString()) }
                }

                setBody(FormDataContent(Parameters.build {
                    formData.forEach { (key, value) ->
                        append(key, value)
                    }
                }))
            }

            handleResponse(response, url)
        } catch (e: HttpRequestTimeoutException) {
            logger.error("POST form request timeout for $url", e)
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            logger.error("POST form request failed for $url", e)
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }


    // ========== SPECIALIZED POST METHODS ==========

    // ========== OTHER UTILITY METHODS ==========

    /**
     * Performs a POST request with form-urlencoded data, returning raw bytes (useful for audio, etc.).
     */
    override suspend fun postFormBytes(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>,
        cookies: Map<String, String>
    ): Result<ByteArray, ServiceError> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.post(url) {
                applyHostTimeout(url)
                cookies.forEach { (key, value) -> cookie(key, value) }

                headers.forEach { (key, value) -> header(key, value) }
                queryParams.forEach { (key, value) ->
                    value?.let { parameter(key, it.toString()) }
                }

                setBody(FormDataContent(Parameters.build {
                    formData.forEach { (key, value) ->
                        append(key, value)
                    }
                }))
            }

            handleResponseBytes(response, url)
        } catch (e: HttpRequestTimeoutException) {
            logger.error("POST form bytes request timeout for $url", e)
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            logger.error("POST form bytes request failed for $url", e)
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }

    /**
     * GET request that returns raw bytes (useful for audio, images, etc.)
     */
    override suspend fun getBytes(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<ByteArray, ServiceError> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.get(url) {
                applyHostTimeout(url)
                headers.forEach { (key, value) -> header(key, value) }
                queryParams.forEach { (key, value) ->
                    value?.let { parameter(key, it.toString()) }
                }
            }
            handleResponseBytes(response, url)
        } catch (e: HttpRequestTimeoutException) {
            logger.error("GET bytes request timeout for $url", e)
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            logger.error("GET bytes request failed for $url", e)
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }

    // ========== RESPONSE HANDLING ==========

    private suspend fun handleResponse(
        response: HttpResponse,
        url: String
    ): Result<String, ServiceError> {
        return when (response.status) {
            HttpStatusCode.OK -> Ok(response.bodyAsText())
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> Err(
                ServiceError.AuthenticationError("Authentication failed for $url")
            )

            HttpStatusCode.TooManyRequests -> Err(
                ServiceError.RateLimitError("Rate limit exceeded for $url")
            )

            HttpStatusCode.PaymentRequired -> {
                val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
                logger.error("HTTP 402 (Payment Required) for $url — $errorBody")
                Err(
                    ServiceError.AuthenticationError(
                        "Insufficient credits. " +
                        "If you are using OpenRouter, visit openrouter.ai/settings/credits to top up, " +
                        "or switch the model to openrouter/free."
                    )
                )
            }

            else -> {
                val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
                logger.error("HTTP ${response.status.value} for $url — $errorBody")
                Err(ServiceError.ServiceUnavailableError("HTTP ${response.status.value} for $url\n$errorBody"))
            }
        }
    }

    private suspend fun handleResponseBytes(
        response: HttpResponse,
        url: String
    ): Result<ByteArray, ServiceError> {
        return when (response.status) {
            HttpStatusCode.OK -> Ok(response.body<ByteArray>())
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> Err(
                ServiceError.AuthenticationError("Authentication failed for $url")
            )

            HttpStatusCode.TooManyRequests -> Err(
                ServiceError.RateLimitError("Rate limit exceeded for $url")
            )

            else -> {
                val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
                logger.error("HTTP ${response.status.value} for $url — $errorBody")
                Err(ServiceError.ServiceUnavailableError("HTTP ${response.status.value} for $url\n$errorBody"))
            }
        }
    }

    override fun close() {
        client.close()
        // The client does not close an engine it was handed, so an owned one is closed here.
        if (ownsEngine) engine.close()
    }
}

/**
 * Configuration for HTTP client behavior
 */
data class HttpClientConfig(
    val requestTimeoutMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 15_000,
    val socketTimeoutMillis: Long = 15_000,
    val enableRetry: Boolean = true,
    val maxRetries: Int = 2,
    /**
     * How long to wait before the first retry. Each attempt after that waits twice the last,
     * capped, with jitter added.
     *
     * Doubling rather than a fixed interval, because the thing most worth retrying is a rate
     * limit, and retrying a rate limit at a fixed interval is knocking at the same door at the
     * same speed. The jitter is not configurable and matters more than it looks: without it,
     * every plugin that failed on one network blip retries in the same instant, together,
     * forever. A server's Retry-After overrides all of this whenever it sends one.
     */
    val retryInitialDelayMillis: Long = 1_000,
    val proxy: ProxyConfiguration? = null,
    /**
     * Timeouts for particular hosts, overriding the values above.
     *
     * Keyed by hostname, matched without regard to case and without the scheme or port: one entry
     * covers a host however it is reached. The point is that these services are not alike. A local
     * model on `127.0.0.1` may think for a minute before its first token and should not be cut off
     * at thirty seconds, while a cloud endpoint that has not answered in ten is not going to.
     */
    val hostTimeouts: Map<String, HostTimeout> = emptyMap(),
    /**
     * The most connections to open to any single host at once.
     *
     * CIO's own default is 100 per route, which is a server's number. A desktop application that
     * opens a hundred sockets to one translation API is not going faster, it is announcing itself
     * to a rate limiter, and on a metered or mobile connection it is doing so expensively. Eight
     * is more than the handful of overlapping requests this application ever really has in flight.
     *
     * The limit is approximate, not a hard ceiling. `Endpoint.makePipelineRequest` reads the
     * connection count, compares it to this, and only increments later inside `connect`, with a
     * suspend boundary in between, so a burst can slip several past the same stale check. It
     * holds concurrency near the number rather than strictly under it, which is all that is
     * wanted here: the point is not to flood one endpoint, not to guarantee an exact count.
     *
     * The cap is uniform: CIO enforces it per route with a counter on each endpoint, and there is
     * no way to say "two for this host, eight for that one" on a single engine. Differing per-host
     * caps would need an engine each, which means a connection pool each, and that trade is not
     * worth making for a limit nothing here comes close to.
     */
    val maxConnectionsPerHost: Int = 8,
    /**
     * The most connections to hold open across every host at once. CIO defaults this to 1000.
     */
    val maxConnectionsTotal: Int = 64
)

/**
 * Timeouts for one host. Every field is optional, and an unset one keeps the client-wide value.
 *
 * Nullable rather than defaulted, because "not specified" and "specified as the default" are
 * different statements: a host that only needs a longer socket timeout should not have its request
 * and connect timeouts reset by mentioning it.
 *
 * The fallback itself is Ktor's, not ours. `HttpTimeout` merges in its Send phase with
 * `this.x = this.x ?: clientWideX`, so a null per-request field takes the client-wide value
 * regardless of how it came to be null. Writing the nulls through explicitly would behave
 * identically; the guards below are there to say so at the call site rather than to cause it.
 */
data class HostTimeout(
    val requestTimeoutMillis: Long? = null,
    val connectTimeoutMillis: Long? = null,
    val socketTimeoutMillis: Long? = null
)

/**
 * An HTTP proxy to send through, and the credentials for it.
 *
 * ### Why the credentials are ours to send
 * Ktor's [io.ktor.client.engine.ProxyConfig] carries an address and nothing else, and CIO never
 * derives credentials from the proxy URL's userinfo. Its `startTunnel` forwards
 * `Proxy-Authorization` onto the CONNECT request only when that header is already on the request
 * being sent, so putting `user:pass@` in the proxy address authenticates nothing. The header has to
 * be attached to every request, which is what [KtorHttpClient] does with this.
 *
 * That covers both shapes at once: an HTTPS request tunnels through CONNECT and the header is
 * copied onto it, and a plain HTTP request goes to the proxy directly with the header already in
 * its normal place.
 */
data class ProxyConfiguration(
    /** The proxy's own address, for example `http://proxy.example:3128`. Credentials go below. */
    val url: String,
    val username: String? = null,
    val password: String? = null
) {
    /** The `Proxy-Authorization` value, or null when the proxy takes no credentials. */
    fun authorizationHeader(): String? {
        if (username.isNullOrEmpty()) return null
        val raw = "$username:${password.orEmpty()}"
        return "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }
}
