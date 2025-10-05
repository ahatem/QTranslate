package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.PluginContext
import com.github.ahatem.qtranslate.api.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.reflect.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class KtorHttpClient(
    private val core: PluginContext,
    @PublishedApi internal val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    },
    private val config: HttpClientConfig = HttpClientConfig()
) : HttpClient {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
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
                retryOnServerErrors(maxRetries = config.maxRetries)
                exponentialDelay()
            }
        }
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.get(url) {
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
            core.logError(e, "GET request timeout for $url")
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            core.logError(e, "GET request failed for $url")
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.post(url) {
                headers.forEach { (key, value) -> header(key, value) }
                queryParams.forEach { (key, value) ->
                    value?.let { parameter(key, it.toString()) }
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            handleResponse(response, url)
        } catch (e: HttpRequestTimeoutException) {
            core.logError(e, "POST request timeout for $url")
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            core.logError(e, "POST request failed for $url")
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }

    /**
     * Performs a GET request using [get].
     * Parses the JSON response into type [T].
     *
     * @param url target endpoint
     * @param headers optional request headers
     * @param queryParams optional query parameters
     * @return [Result] containing parsed object of type [T] or [ServiceError] on failure
     *
     * Example:
     * val result = fetchJson<User>("https://api.example.com/user")
     */
    suspend inline fun <reified T> fetchJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any> = emptyMap()
    ): Result<T, ServiceError> {
        val responseString = get(url, headers, queryParams).getOrElse { return Err(it) }

        return runCatching {
            Ok(json.decodeFromString<T>(responseString))
        }.getOrElse { error ->
            Err(ServiceError.InvalidResponseError("Failed to parse JSON response", error))
        }
    }


    /**
     * Performs a POST request using [post].
     * Encodes [body] as JSON before sending.
     *
     * @param url target endpoint
     * @param headers optional request headers
     * @param body optional request body to encode as JSON
     * @param queryParams optional query parameters
     * @return [Result] containing response body as [String] or [ServiceError] on failure
     *
     * Example:
     * val result = sendJson("https://api.example.com/user", body = UserRequest("John"))
     */
    suspend inline fun <reified T> sendJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: T? = null,
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError> {
        val encodedBody = body?.let { json.encodeToString(it) }
        return post(url, headers, encodedBody, queryParams)
    }


    /**
     * GET request that returns raw bytes (useful for audio, images, etc.)
     */
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<ByteArray, ServiceError> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.get(url) {
                headers.forEach { (key, value) -> header(key, value) }
                queryParams.forEach { (key, value) ->
                    value?.let { parameter(key, it.toString()) }
                }
            }
            when (response.status) {
                HttpStatusCode.OK -> Ok(response.body<ByteArray>())
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> Err(
                    ServiceError.AuthenticationError("Authentication failed for $url")
                )

                HttpStatusCode.TooManyRequests -> Err(
                    ServiceError.RateLimitError("Rate limit exceeded for $url")
                )

                else -> Err(
                    ServiceError.ServiceUnavailableError("HTTP ${response.status.value} for $url")
                )
            }
        } catch (e: HttpRequestTimeoutException) {
            core.logError(e, "GET bytes request timeout for $url")
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            core.logError(e, "GET bytes request failed for $url")
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }

    /**
     * POST request with typed body (automatically serialized)
     */
    suspend inline fun <reified T> postTyped(
        url: String,
        body: T,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError> {
        return postTypedImpl(url, body, typeInfo<T>(), headers, queryParams)
    }

    @PublishedApi
    internal suspend fun <T> postTypedImpl(
        url: String,
        body: T,
        typeInfo: TypeInfo,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.post(url) {
                headers.forEach { (key, value) -> header(key, value) }
                queryParams.forEach { (key, value) ->
                    value?.let { parameter(key, it.toString()) }
                }
                contentType(ContentType.Application.Json)
                setBody(body, typeInfo)
            }
            handleResponse(response, url)
        } catch (e: HttpRequestTimeoutException) {
            core.logError(e, "POST typed request timeout for $url")
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            core.logError(e, "POST typed request failed for $url")
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }

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

            else -> Err(
                ServiceError.ServiceUnavailableError("HTTP ${response.status.value} for $url")
            )
        }
    }

    fun close() {
        client.close()
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
    val maxRetries: Int = 2
)