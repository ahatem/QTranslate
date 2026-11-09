package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.common.sendJson
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
 * This client implements exactly one provider shape:
 * `POST {baseUrl}/chat/completions` (chat/completions style). It does NOT
 * implement the Responses API (`/v1/responses`), model listing (`/models`),
 * audio endpoints, embeddings, Assistants, or Azure deployment routes
 * — those need their own clients if ever supported.
 *
 * A single code path handles every supported endpoint that speaks that
 * shape: OpenRouter · OpenAI · Mistral · Gemini OpenAI-compat · Ollama ·
 * Azure OpenAI · etc. The resolved [AiProfile] is passed per call, so
 * endpoint, key, model, temperature and max tokens are all per-profile
 * and changes in Settings → Plugins take effect without restarting the app.
 *
 * ### Custom headers
 * [AiProfile.customHeaders] is a JSON object string merged into every
 * request. It may never override `Authorization` or `Content-Type` —
 * those entries are skipped case-insensitively so a malformed or hostile
 * custom-header blob cannot silently break auth.
 *
 * ### Privacy
 * Debug logs carry metadata only (model name). Keys, `Authorization`
 * values, header values, request bodies, response bodies, and user text
 * are never logged, and provider error surfaces never echo raw bodies.
 *
 * @param pluginContext Provides logging and the JSON response parser.
 * @param httpClient HTTP transport; its errors pass through untouched.
 */
class AIServiceClient(
    private val pluginContext: PluginContext,
    private val httpClient: HttpClient
) {
    private val responseParser = createJsonParser<ChatCompletionResponse>(pluginContext)
    private val headersJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Checks that the configured endpoint, key and model actually work together.
     *
     * Sends the smallest real request rather than only inspecting the settings: an endpoint that
     * rejects the key, or a model name the provider does not have, both look perfectly valid on
     * paper and only fail when the user tries to translate something.
     */
    suspend fun validate(profile: AiProfile): Result<Unit, ServiceError> {
        profile.missingKeyError()?.let { return Err(it) }
        return complete(system = "Reply with the single character: 1", userContent = "1", profile = profile)
            .map { }
    }

    /**
     * Sends a chat-completion request and returns the model's text response.
     *
     * HTTP transport errors from [httpClient] pass through untouched — they
     * are never re-mapped or wrapped here.
     *
     * @param system      The system-level instruction (becomes the first "system" message).
     * @param userContent The user turn content.
     * @param profile     The resolved profile supplying endpoint, key, model and limits.
     */
    suspend fun complete(
        system: String,
        userContent: String,
        profile: AiProfile
    ): Result<String, ServiceError> {
        profile.missingKeyError()?.let { return Err(it) }

        val endpoint = profile.chatCompletionsUrl()

        pluginContext.logger.debug("AIServiceClient chat completion: model=${profile.model}")

        val requestBody = ChatCompletionRequest(
            model               = profile.model,
            messages            = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user",   content = userContent)
            ),
            temperature         = profile.temperature,
            maxCompletionTokens = profile.maxTokens
        )

        return httpClient.sendJson(
            url         = endpoint,
            headers     = buildHeaders(profile),
            body        = requestBody,
            queryParams = emptyMap()
        ).andThen { responseString ->
            responseParser.parse(responseString)
        }.andThen { response ->
            extractContent(response, "AI service returned an empty response (no choices).")
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
     * @param profile       The resolved profile supplying endpoint, key, model and limits.
     */
    suspend fun completeWithImage(
        system: String,
        image: ImageData,
        userText: String,
        profile: AiProfile
    ): Result<String, ServiceError> {
        profile.missingKeyError()?.let { return Err(it) }

        val endpoint = profile.chatCompletionsUrl()

        pluginContext.logger.debug(
            "AIServiceClient (vision): model=${profile.model}, " +
                    "image=${image.width}×${image.height} ${image.format}"
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
            model               = profile.model,
            messages            = listOf(
                VisionMessage(role = "system", content = JsonPrimitive(system)),
                VisionMessage(role = "user",   content = userContent)
            ),
            temperature         = profile.temperature,
            maxCompletionTokens = profile.maxTokens
        )

        return httpClient.sendJson(
            url         = endpoint,
            headers     = buildHeaders(profile),
            body        = requestBody,
            queryParams = emptyMap()
        ).andThen { responseString ->
            responseParser.parse(responseString)
        }.andThen { response ->
            extractContent(response, "AI vision service returned an empty response (no choices).")
        }
    }

    /**
     * Builds request headers from [profile].
     *
     * - A blank key (local, no-key endpoint) omits `Authorization` entirely —
     *   sending `Bearer ` with an empty token would trip strict servers.
     * - `Content-Type: application/json` is always present.
     * - [AiProfile.customHeaders] (a JSON object string) is merged, except
     *   `Authorization`/`Content-Type` entries which are skipped
     *   case-insensitively so custom headers cannot silently break auth.
     * - On parse failure, warn with the exception class name only — header
     *   values are never logged.
     */
    private fun buildHeaders(profile: AiProfile): Map<String, String> =
        buildMap {
            if (profile.apiKey.isNotBlank()) {
                put("Authorization", "Bearer ${profile.apiKey}")
            }
            put("Content-Type", "application/json")
            if (profile.customHeaders.isNotBlank()) {
                runCatching {
                    headersJson.decodeFromString<Map<String, String>>(profile.customHeaders)
                }.onSuccess { extra ->
                    extra.forEach { (key, value) ->
                        if (!key.equals("Authorization", ignoreCase = true) &&
                            !key.equals("Content-Type", ignoreCase = true)
                        ) {
                            put(key, value)
                        }
                    }
                }.onFailure { e ->
                    pluginContext.logger.warn(
                        "AI Plugin: Could not parse Custom Headers JSON — skipping: ${e::class.simpleName}"
                    )
                }
            }
        }

    /**
     * Extracts assistant text from a parsed completion, applying the
     * `finish_reason` contract:
     *
     * - `"length"` means the model hit the token cap and the text is
     *   truncated — surfaced as a distinct [ServiceError.InvalidResponseError]
     *   telling the user to raise Max Tokens, never leaking the body.
     * - `null`/any other value passes through as before.
     * - Empty choices/message is an [ServiceError.InvalidResponseError]
     *   with no response body attached.
     * - In-band provider errors map via [mapError] as before.
     */
    private fun extractContent(
        response: ChatCompletionResponse,
        emptyMessage: String
    ): Result<String, ServiceError> {
        response.error?.let { return Err(mapError(it)) }
        val choice = response.choices.firstOrNull()
            ?: return Err(ServiceError.InvalidResponseError(emptyMessage, null))
        if (choice.finishReason == "length") {
            return Err(
                ServiceError.InvalidResponseError(
                    "AI output was truncated because it reached the max token limit. " +
                        "Increase Max Tokens for this profile and retry.", null
                )
            )
        }
        return choice.message.content
            .toResultOr {
                ServiceError.InvalidResponseError(emptyMessage, null)
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
