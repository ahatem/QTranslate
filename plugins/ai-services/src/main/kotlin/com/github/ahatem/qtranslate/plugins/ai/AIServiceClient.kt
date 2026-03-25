package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.toResultOr

/**
 * Shared HTTP wrapper that routes to the correct provider endpoint and handles
 * the schema difference between the OpenAI chat completions format (Gemini,
 * OpenAI, Mistral) and Anthropic's native Messages API.
 *
 * ### Provider routing
 *
 * | Provider  | Endpoint                                            | Format          |
 * |-----------|-----------------------------------------------------|-----------------|
 * | Gemini    | .../v1beta/openai/chat/completions + ?key=<key>     | OpenAI-compat   |
 * | OpenAI    | api.openai.com/v1/chat/completions                  | OpenAI-native   |
 * | Mistral   | api.mistral.ai/v1/chat/completions                  | OpenAI-compat   |
 * | Anthropic | api.anthropic.com/v1/messages                       | Anthropic-native|
 *
 * ### Usage
 * Services call [complete] with a [system] prompt string and a [userContent] string.
 * The client assembles the correct request body for the active provider internally.
 *
 * @param settings Lambda returning live [AISettings] — picks up changes without rebuild.
 */
class AIServiceClient(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val settings: () -> AISettings
) {
    private val openAiParser = createJsonParser<ChatCompletionResponse>(pluginContext)
    private val anthropicParser = createJsonParser<AnthropicResponse>(pluginContext)

    /**
     * Sends a completion request and returns the model's text response.
     *
     * @param system      The system-level instruction. For Anthropic this becomes the
     *                    top-level `system` field; for others it's a "system" message.
     * @param userContent The user turn content.
     * @param jsonMode    Request JSON output. Only set when the [system] prompt explicitly
     *                    asks for JSON — some providers reject json_object mode otherwise.
     *                    Ignored for Anthropic (use prompt engineering there instead).
     */
    suspend fun complete(
        system: String,
        userContent: String,
        jsonMode: Boolean = false
    ): Result<String, ServiceError> {
        val current = settings()

        if (current.apiKey.isBlank()) {
            return Err(
                ServiceError.AuthenticationError(
                    "AI Plugin: API key is not configured. Please add your key in Settings."
                )
            )
        }

        val provider = AIProvider.fromSettingValue(current.provider)

        pluginContext.logger.debug(
            "AIServiceClient → ${provider.name} [model=${current.model}]"
        )

        return if (provider.usesNativeAnthropicApi) {
            completeWithAnthropic(current, system, userContent)
        } else {
            completeWithOpenAICompat(current, provider, system, userContent, jsonMode)
        }
    }

    // OpenAI-compatible path  (Gemini · OpenAI · Mistral)
    private suspend fun completeWithOpenAICompat(
        current: AISettings,
        provider: AIProvider,
        system: String,
        userContent: String,
        jsonMode: Boolean
    ): Result<String, ServiceError> {
        val endpoint = "${provider.baseUrl}/chat/completions"

        val messages = listOf(
            ChatMessage(role = "system", content = system),
            ChatMessage(role = "user", content = userContent)
        )

        val requestBody = ChatCompletionRequest(
            model = current.model,
            messages = messages,
            temperature = current.temperature,
            responseFormat = if (jsonMode) ResponseFormat(type = "json_object") else null
        )

        val headers = buildMap {
            put("Authorization", "Bearer ${current.apiKey}")
            put("Content-Type", "application/json")
        }

        // Gemini requires the key as a query param in addition to Bearer header
        val queryParams = if (provider.requiresKeyQueryParam) {
            mapOf("key" to current.apiKey)
        } else {
            emptyMap()
        }

        return httpClient.sendJson(
            url = endpoint,
            headers = headers,
            body = requestBody,
            queryParams = queryParams
        ).andThen { responseString ->
            openAiParser.parse(responseString)
        }.andThen { response ->
            response.error?.let { return@andThen Err(mapOpenAiError(it)) }
            response.choices.firstOrNull()?.message?.content
                .toResultOr {
                    ServiceError.InvalidResponseError(
                        "Provider returned an empty response (no choices).", null
                    )
                }
        }
    }

    // Anthropic native path  — /v1/messages
    // Docs: platform.claude.com/docs/en/api/messages
    private suspend fun completeWithAnthropic(
        current: AISettings,
        system: String,
        userContent: String
    ): Result<String, ServiceError> {
        val endpoint = "${AIProvider.ANTHROPIC.baseUrl}/messages"

        val requestBody = AnthropicRequest(
            model = current.model,
            system = system,
            messages = listOf(AnthropicMessage(role = "user", content = userContent)),
            maxTokens = 4096,
            temperature = current.temperature
        )

        val headers = mapOf(
            "x-api-key" to current.apiKey,
            "anthropic-version" to "2023-06-01",
            "Content-Type" to "application/json"
        )

        return httpClient.sendJson(
            url = endpoint,
            headers = headers,
            body = requestBody,
            queryParams = emptyMap()
        ).andThen { responseString ->
            anthropicParser.parse(responseString)
        }.andThen { response ->
            response.error?.let { return@andThen Err(mapAnthropicError(it)) }
            response.content.firstOrNull { it.type == "text" }?.text
                .toResultOr {
                    ServiceError.InvalidResponseError(
                        "Anthropic returned an empty response (no text content).", null
                    )
                }
        }
    }

    private fun mapOpenAiError(error: ChatError): ServiceError {
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

    private fun mapAnthropicError(error: AnthropicError): ServiceError {
        val msg = error.message
        return when (error.type) {
            "authentication_error" -> ServiceError.AuthenticationError(msg)
            "permission_error" -> ServiceError.AuthenticationError(msg)
            "rate_limit_error" -> ServiceError.RateLimitError(msg)
            "overloaded_error" -> ServiceError.ServiceUnavailableError(msg)
            "api_error" -> ServiceError.ServiceUnavailableError(msg)
            "invalid_request_error" -> ServiceError.InvalidInputError(msg)
            "not_found_error" -> ServiceError.InvalidInputError(msg)
            else -> ServiceError.UnknownError(msg)
        }
    }

    private fun String.containsAny(vararg terms: String): Boolean =
        terms.any { this.contains(it, ignoreCase = true) }

    private companion object {
        val AUTH_CODES = setOf("invalid_api_key", "invalid_request_error", "401", "403")
        val RATE_LIMIT_CODES = setOf("rate_limit_exceeded", "429")
        val UNAVAILABLE_CODES = setOf("service_unavailable", "server_error", "503", "529")
    }
}