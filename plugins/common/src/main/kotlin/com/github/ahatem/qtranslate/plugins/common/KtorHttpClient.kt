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
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.reflect.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class KtorHttpClient(
    private val pluginContext: PluginContext,
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

    // Main POST method with flexible content type support
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
                    // Default to JSON if no content-type specified
                    val contentType = headers["Content-Type"] ?: "application/json"
                    contentType(ContentType.parse(contentType))
                    setBody(body)
                }
            }
            handleResponse(response, url)
        } catch (e: HttpRequestTimeoutException) {
            pluginContext.logError(e, "POST request timeout for $url")
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            pluginContext.logError(e, "POST request failed for $url")
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
            pluginContext.logError(e, "GET request timeout for $url")
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            pluginContext.logError(e, "GET request failed for $url")
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }

    // ========== JSON UTILITY METHODS ==========

    /**
     * Performs a GET request and parses the JSON response into type [T].
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
     * Performs a POST request with JSON body.
     */
    suspend inline fun <reified T> sendJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: T? = null,
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError> {
        val encodedBody = body?.let { json.encodeToString(it) }
        val jsonHeaders = headers + ("Content-Type" to "application/json")
        return post(url, jsonHeaders, encodedBody, queryParams)
    }

    /**
     * POST request with typed body (automatically serialized to JSON).
     */
    suspend inline fun <reified T> postTyped(
        url: String,
        body: T,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError> {
        return postTypedImpl(url, body, typeInfo<T>(), headers, queryParams)
    }

    // ========== FORM DATA UTILITY METHODS ==========

    /**
     * Performs a POST request with form-urlencoded data.
     */
    suspend fun postForm(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap(),
        cookies: Map<String, String> = emptyMap()
    ): Result<String, ServiceError> = withContext(Dispatchers.IO) {
        try {

            val response: HttpResponse = client.post(url) {
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
            pluginContext.logError(e, "POST form request timeout for $url")
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            pluginContext.logError(e, "POST form request failed for $url")
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }


    // ========== SPECIALIZED POST METHODS ==========

    /**
     * POST request with raw text content.
     */
    suspend fun postText(
        url: String,
        text: String,
        contentType: String = "text/plain",
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError> {
        val textHeaders = headers + ("Content-Type" to contentType)
        return post(url, textHeaders, text, queryParams)
    }

    /**
     * POST request with XML content.
     */
    suspend fun postXml(
        url: String,
        xml: String,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError> {
        val xmlHeaders = headers + ("Content-Type" to "application/xml")
        return post(url, xmlHeaders, xml, queryParams)
    }


    // ========== OTHER UTILITY METHODS ==========

    /**
     * Performs a POST request with form-urlencoded data, returning raw bytes (useful for audio, etc.).
     */
    suspend fun postFormBytes(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap(),
        cookies: Map<String, String> = emptyMap()
    ): Result<ByteArray, ServiceError> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.post(url) {
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
            pluginContext.logError(e, "POST form bytes request timeout for $url")
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            pluginContext.logError(e, "POST form bytes request failed for $url")
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
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
            handleResponseBytes(response, url)
        } catch (e: HttpRequestTimeoutException) {
            pluginContext.logError(e, "GET bytes request timeout for $url")
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            pluginContext.logError(e, "GET bytes request failed for $url")
            Err(ServiceError.NetworkError("Network error: ${e.message}", e))
        }
    }

    // ========== INTERNAL IMPLEMENTATIONS ==========

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
            pluginContext.logInfo("POST typed request succeeded for ${response.request.url}")
            pluginContext.logInfo("POST typed request succeeded for ${response.request.content}")
            handleResponse(response, url)
        } catch (e: HttpRequestTimeoutException) {
            pluginContext.logError(e, "POST typed request timeout for $url")
            Err(ServiceError.TimeoutError("Request timed out: $url", e))
        } catch (e: Exception) {
            pluginContext.logError(e, "POST typed request failed for $url")
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

            else -> Err(
                ServiceError.ServiceUnavailableError("HTTP ${response.status.value} for $url")
            )
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

// Extension function to create form data from pairs
fun formDataOf(vararg pairs: Pair<String, String>): Map<String, String> = mapOf(*pairs)