package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import io.ktor.client.*
// Both this file's supertype and Ktor's own client are called HttpClient. Ours is now imported by
// name, which beats the star import below, so Ktor's needs an alias to stay reachable.
import io.ktor.client.HttpClient as KtorClient
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
import java.io.Closeable

internal class KtorHttpClient(
    private val logger: Logger,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    },
    private val config: HttpClientConfig = HttpClientConfig()
) : HttpClient, Closeable {

    private val client = KtorClient(CIO) {
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
