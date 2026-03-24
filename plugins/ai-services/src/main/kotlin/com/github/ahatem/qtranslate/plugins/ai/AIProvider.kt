package com.github.ahatem.qtranslate.plugins.ai

/**
 * Supported AI providers with their verified endpoints and auth requirements.
 * ─────────────────────────────────────────────────────────────────────────────
 * GEMINI  (ai.google.dev/gemini-api/docs/models)
 *   Endpoint : https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
 *   Auth     : Authorization: Bearer <key>  +  ?key=<key>  (both required)
 *   Models   : gemini-3-flash-preview · gemini-3.1-pro-preview · gemini-3.1-flash-lite-preview
 *              gemini-2.5-flash (stable) · gemini-2.5-flash-lite · gemini-2.5-pro (stable)
 *
 * OPENAI  (platform.openai.com/docs)
 *   Endpoint : https://api.openai.com/v1/chat/completions
 *   Auth     : Authorization: Bearer <key>
 *   Models   : gpt-4.1 · gpt-4.1-mini · gpt-4.1-nano · gpt-4o  (all active in API)
 *
 * MISTRAL  (docs.mistral.ai/getting-started/models)
 *   Endpoint : https://api.mistral.ai/v1/chat/completions
 *   Auth     : Authorization: Bearer <key>
 *   Models   : mistral-small-latest · mistral-large-latest · mistral-medium-3-1
 *              (latest alias resolves to: mistral-small-4-0-26-03 · mistral-large-3-25-12)
 *
 * ANTHROPIC  (platform.claude.com/docs)
 *   Endpoint : https://api.anthropic.com/v1/messages  ← NATIVE endpoint, not OpenAI-compat
 *   Auth     : x-api-key: <key>  +  anthropic-version: 2023-06-01
 *   Models   : claude-opus-4-6 · claude-sonnet-4-6 · claude-haiku-4-5
 */
enum class AIProvider(
    val baseUrl: String,
    /**
     * For Gemini only: append `?key=<apiKey>` as a query param in addition
     * to the Bearer header. Both are required to avoid intermittent 400s.
     */
    val requiresKeyQueryParam: Boolean = false,
    /**
     * Anthropic's native /v1/messages API uses a different request/response
     * schema from the OpenAI chat completions format. When true, [AIServiceClient]
     * switches to the Anthropic-native path instead of the OpenAI-compat path.
     */
    val usesNativeAnthropicApi: Boolean = false
) {
    GEMINI(
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        requiresKeyQueryParam = true
    ),
    OPENAI(
        baseUrl = "https://api.openai.com/v1"
    ),
    MISTRAL(
        baseUrl = "https://api.mistral.ai/v1"
    ),
    ANTHROPIC(
        baseUrl = "https://api.anthropic.com/v1",
        usesNativeAnthropicApi = true
    );

    companion object {
        fun fromSettingValue(value: String): AIProvider =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: GEMINI
    }
}