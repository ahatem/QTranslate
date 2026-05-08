package com.github.ahatem.qtranslate.plugins.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ─────────────────────────────────────────────────────────────────────────────
// OpenAI-compatible chat completions format
// POST {baseUrl}/chat/completions
// Compatible with: OpenRouter · OpenAI · Mistral · Gemini OpenAI-compat ·
//                  Ollama · LM Studio · Azure OpenAI · any OpenAI-compat server
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ChatCompletionRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<ChatMessage>,
    @SerialName("temperature") val temperature: Double,
    // Use only max_completion_tokens (the current standard field):
    //  - Supported by OpenAI, OpenRouter, and Gemini's OpenAI-compat endpoint.
    //  - Sending both max_tokens AND max_completion_tokens simultaneously causes HTTP 400 on
    //    Gemini's endpoint ("cannot both be set"). max_tokens is deprecated on OpenRouter.
    //  - Providers that don't yet recognise max_completion_tokens (older Ollama / Mistral)
    //    silently ignore unknown fields rather than erroring, so single-field is safest.
    // No Kotlin default here — the caller always passes an explicit value so encodeDefaults
    // skipping doesn't accidentally drop this field from the serialized JSON.
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int
)

@Serializable
data class ChatMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String
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
// Vision / multimodal format
// Used when the user message carries image content alongside text.
// The `content` field is a JsonElement so it can be either a plain string
// (text-only messages) or a JSON array of typed content parts (vision messages).
// Compatible with: OpenAI GPT-4V · Gemini Vision · Claude Vision via OpenRouter
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class VisionChatCompletionRequest(
    @SerialName("model")                  val model: String,
    @SerialName("messages")               val messages: List<VisionMessage>,
    @SerialName("temperature")            val temperature: Double,
    @SerialName("max_completion_tokens")  val maxCompletionTokens: Int
)

/**
 * A chat message whose content may be either a plain string (for system/assistant
 * turns) or a JSON array of typed parts (for user turns that include images).
 *
 * Example text-only:    `content = JsonPrimitive("Translate this.")`
 * Example with image:   `content = buildJsonArray { addJsonObject { … } }`
 */
@Serializable
data class VisionMessage(
    @SerialName("role")    val role: String,
    @SerialName("content") val content: JsonElement
)
