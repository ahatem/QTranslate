package com.github.ahatem.qtranslate.plugins.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// OpenAI / Gemini / Mistral — chat completions format
// POST /v1/chat/completions
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ChatCompletionRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<ChatMessage>,
    @SerialName("temperature") val temperature: Double = 0.3,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

@Serializable
data class ChatMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String
)

@Serializable
data class ResponseFormat(
    @SerialName("type") val type: String
)

@Serializable
data class ChatCompletionResponse(
    @SerialName("choices") val choices: List<ChatChoice> = emptyList(),
    @SerialName("error") val error: ChatError? = null
)

@Serializable
data class ChatChoice(
    @SerialName("message") val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChatError(
    @SerialName("message") val message: String,
    @SerialName("type") val type: String? = null,
    @SerialName("code") val code: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// Anthropic — native Messages API format
// POST /v1/messages
// Docs: platform.claude.com/docs/en/api/messages
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Anthropic Messages API request.
 * Note: [system] is a top-level string, NOT a message in the array.
 * [maxTokens] is required by the Anthropic API (we default to 4096).
 */
@Serializable
data class AnthropicRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<AnthropicMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    @SerialName("temperature") val temperature: Double = 0.3,
    @SerialName("system") val system: String? = null
)

@Serializable
data class AnthropicMessage(
    @SerialName("role") val role: String,    // "user" or "assistant"
    @SerialName("content") val content: String
)

@Serializable
data class AnthropicResponse(
    @SerialName("content") val content: List<AnthropicContent> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("error") val error: AnthropicError? = null
)

@Serializable
data class AnthropicContent(
    @SerialName("type") val type: String,    // "text"
    @SerialName("text") val text: String = ""
)

@Serializable
data class AnthropicError(
    @SerialName("type") val type: String,
    @SerialName("message") val message: String
)