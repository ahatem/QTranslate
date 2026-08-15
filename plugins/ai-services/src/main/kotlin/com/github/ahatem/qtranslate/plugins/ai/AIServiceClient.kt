package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.map
import com.github.michaelbull.result.toResultOr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Shared HTTP wrapper for the OpenAI-compatible chat completions API.
 *
 * A single code path handles every supported endpoint:
 * OpenRouter · OpenAI · Mistral · Gemini OpenAI-compat · Ollama · Azure OpenAI · etc.
 *
 * The [baseUrl] is read from [AISettings] on every call so endpoint changes in
 * Settings → Plugins take effect without restarting the app.
 *
 * ### Custom headers
 * [AISettings.customHeaders] is a JSON object string merged into every request.
 * The defaults include OpenRouter's optional site-attribution headers
 * (`HTTP-Referer`, `X-Title`), which are harmless with other providers.
 *
 * @param settings Lambda returning live [AISettings] — picks up changes without rebuild.
 */
class AIServiceClient(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val settings: () -> AISettings
) {
    private val responseParser = createJsonParser<ChatCompletionResponse>(pluginContext)
    private val headersJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Sends a chat-completion request and returns the model's text response.
     *
     * @param system      The system-level instruction (becomes the first "system" message).
     * @param userContent The user turn content.
     */
    /**
     * Checks that the configured endpoint, key and model actually work together.
     *
     * Sends the smallest real request rather than only inspecting the settings: an endpoint that
     * rejects the key, or a model name the provider does not have, both look perfectly valid on
     * paper and only fail when the user tries to translate something.
     */
    suspend fun validate(): Result<Unit, ServiceError> {
        val current = settings()
        if (current.apiKey.isBlank()) {
            return Err(
                ServiceError.ConfigurationError(
                    "No API key set. Add one in Settings → Plugins → AI Plugin."
                )
            )
        }
        return complete(system = "Reply with the single character: 1", userContent = "1")
            .map { }
    }

    suspend fun complete(
        system: String,
        userContent: String
    ): Result<String, ServiceError> {
        val current = settings()

        if (current.apiKey.isBlank()) {
            // Not set up yet, which is a different problem from a key the provider rejected —
            // one is "finish configuring", the other is "the key is wrong".
            return Err(
                ServiceError.ConfigurationError(
                    "AI Plugin: API key is not configured. Add your key in Settings → Plugins → AI Plugin."
                )
            )
        }

        val baseUrl = current.baseUrl.trimEnd('/')
        val endpoint = "$baseUrl/chat/completions"

        pluginContext.logger.debug("AIServiceClient → $endpoint [model=${current.model}]")

        val requestBody = ChatCompletionRequest(
            model               = current.model,
            messages            = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user",   content = userContent)
            ),
            temperature         = current.temperature,
            maxCompletionTokens = current.maxTokens
        )

        val headers = buildMap<String, String> {
            put("Authorization", "Bearer ${current.apiKey}")
            put("Content-Type",  "application/json")
            // Merge user-supplied custom headers (e.g. OpenRouter attribution headers)
            if (current.customHeaders.isNotBlank()) {
                runCatching {
                    headersJson.decodeFromString<Map<String, String>>(current.customHeaders)
                }.onSuccess { extra ->
                    putAll(extra)
                }.onFailure { e ->
                    pluginContext.logger.warn(
                        "AI Plugin: Could not parse Custom Headers JSON — skipping. Error: ${e.message}"
                    )
                }
            }
        }

        return httpClient.sendJson(
            url         = endpoint,
            headers     = headers,
            body        = requestBody,
            queryParams = emptyMap()
        ).andThen { responseString ->
            responseParser.parse(responseString)
        }.andThen { response ->
            response.error?.let { return@andThen Err(mapError(it)) }
            response.choices.firstOrNull()?.message?.content
                .toResultOr {
                    ServiceError.InvalidResponseError(
                        "AI service returned an empty response (no choices).", null
                    )
                }
        }
    }

    /**
     * Sends a vision (multimodal) request: a system prompt + an image + optional text.
     *
     * The image is base64-encoded and embedded as a data URI in the `image_url` content
     * part, which is the format expected by OpenAI-compatible vision endpoints on
     * OpenRouter (GPT-4V, Gemini Vision, Claude Vision, etc.).
     *
     * **Model requirement:** the active model must support vision. Models without vision
     * capability will return an error from the provider — switch to a multimodal model
     * (e.g. `openai/gpt-4o`, `google/gemini-flash-1.5`, `anthropic/claude-3-5-sonnet`).
     *
     * @param system        System-level instruction for the model.
     * @param image         Raw image data to send. Bytes are base64-encoded internally.
     * @param userText      Optional text prompt shown alongside the image in the user turn.
     */
    suspend fun completeWithImage(
        system: String,
        image: ImageData,
        userText: String = ""
    ): Result<String, ServiceError> {
        val current = settings()

        if (current.apiKey.isBlank()) {
            return Err(
                ServiceError.AuthenticationError(
                    "AI Plugin: API key is not configured. Add your key in Settings → Plugins → AI Plugin."
                )
            )
        }

        val baseUrl  = current.baseUrl.trimEnd('/')
        val endpoint = "$baseUrl/chat/completions"

        pluginContext.logger.debug(
            "AIServiceClient (vision) → $endpoint [model=${current.model}, image=${image.width}×${image.height} ${image.format}]"
        )

        // Build the user content array: image part + optional text part
        val mimeType    = "image/${image.format.lowercase().trimStart('.')}"
        val base64Image = Base64.getEncoder().encodeToString(image.bytes)
        val dataUri     = "data:$mimeType;base64,$base64Image"

        val userContent = buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", dataUri) }
            })
            if (userText.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", userText)
                })
            }
        }

        val requestBody = VisionChatCompletionRequest(
            model               = current.model,
            messages            = listOf(
                VisionMessage(role = "system", content = JsonPrimitive(system)),
                VisionMessage(role = "user",   content = userContent)
            ),
            temperature         = current.temperature,
            maxCompletionTokens = current.maxTokens
        )

        val headers = buildMap<String, String> {
            put("Authorization", "Bearer ${current.apiKey}")
            put("Content-Type",  "application/json")
            if (current.customHeaders.isNotBlank()) {
                runCatching {
                    headersJson.decodeFromString<Map<String, String>>(current.customHeaders)
                }.onSuccess { putAll(it) }
                 .onFailure { e ->
                    pluginContext.logger.warn(
                        "AI Plugin: Could not parse Custom Headers JSON — skipping. Error: ${e.message}"
                    )
                }
            }
        }

        return httpClient.sendJson(
            url         = endpoint,
            headers     = headers,
            body        = requestBody,
            queryParams = emptyMap()
        ).andThen { responseString ->
            responseParser.parse(responseString)
        }.andThen { response ->
            response.error?.let { return@andThen Err(mapError(it)) }
            response.choices.firstOrNull()?.message?.content
                .toResultOr {
                    ServiceError.InvalidResponseError(
                        "AI vision service returned an empty response (no choices).", null
                    )
                }
        }
    }

    private fun mapError(error: ChatError): ServiceError {
        val msg = error.message
        return when {
            error.code in AUTH_CODES || msg.containsAny("api key", "invalid key", "unauthorized", "authentication")
                -> ServiceError.AuthenticationError(msg)

            error.code in RATE_LIMIT_CODES || msg.containsAny("rate limit", "quota", "too many requests")
                -> ServiceError.RateLimitError(msg)

            error.code in UNAVAILABLE_CODES || msg.containsAny("unavailable", "overloaded", "capacity")
                -> ServiceError.ServiceUnavailableError(msg)

            msg.containsAny("invalid request", "bad request", "does not support")
                -> ServiceError.InvalidInputError(msg)

            else -> ServiceError.UnknownError(msg)
        }
    }

    private fun String.containsAny(vararg terms: String): Boolean =
        terms.any { this.contains(it, ignoreCase = true) }

    private companion object {
        val AUTH_CODES       = setOf("invalid_api_key", "invalid_request_error", "401", "403")
        val RATE_LIMIT_CODES = setOf("rate_limit_exceeded", "429")
        val UNAVAILABLE_CODES = setOf("service_unavailable", "server_error", "503", "529")
    }
}
