package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType

/**
 * User-configurable settings for the AI Plugin.
 *
 * The plugin targets any **OpenAI-compatible** chat completions endpoint, which covers:
 * - **OpenRouter** (default) — unified gateway to 300+ models via a single API key
 *   (`https://openrouter.ai/api/v1`). Recommended for most users.
 * - **OpenAI** — `https://api.openai.com/v1`
 * - **Mistral** — `https://api.mistral.ai/v1`
 * - **Gemini** — `https://generativelanguage.googleapis.com/v1beta/openai`
 * - **Local Ollama** — `http://localhost:11434/v1`
 * - **Azure OpenAI**, LM Studio, and any other OpenAI-compatible server.
 *
 * Anthropic's native `/v1/messages` API is no longer supported directly; use
 * OpenRouter (`anthropic/claude-3-5-sonnet`) to access Anthropic models.
 */
data class AISettings(

    @field:Setting(
        label = "Base URL",
        description = "OpenAI-compatible endpoint. Default: OpenRouter — a single key gives access to 300+ models. " +
                "You can also point this at OpenAI (https://api.openai.com/v1), " +
                "Mistral (https://api.mistral.ai/v1), " +
                "Gemini (https://generativelanguage.googleapis.com/v1beta/openai), " +
                "local Ollama (http://localhost:11434/v1), or any compatible server.",
        type = SettingType.TEXT,
        defaultValue = "https://openrouter.ai/api/v1",
        order = 10
    )
    var baseUrl: String = "https://openrouter.ai/api/v1",

    @field:Setting(
        label = "API Key",
        description = "Your API key for the selected endpoint. " +
                "Get an OpenRouter key at openrouter.ai/keys. " +
                "For local Ollama leave this blank.",
        type = SettingType.PASSWORD,
        isRequired = true,
        order = 20
    )
    var apiKey: String = "",

    @field:Setting(
        label = "Model",
        description = "Model identifier. OpenRouter examples: google/gemini-flash-1.5-8b · openai/gpt-4o · anthropic/claude-3-5-sonnet · mistralai/mistral-small. " +
                "Direct provider examples: gpt-4o · gemini-2.5-flash · mistral-small-latest.",
        type = SettingType.TEXT,
        defaultValue = "google/gemini-flash-1.5-8b",
        isRequired = true,
        order = 30
    )
    var model: String = "google/gemini-flash-1.5-8b",

    @field:Setting(
        label = "Temperature",
        description = "Controls output randomness (0.0 = deterministic, 1.0 = creative). " +
                "Recommended: 0.2 for translation and spell-check, 0.7 for summarization and rewriting.",
        type = SettingType.NUMBER,
        defaultValue = "0.3",
        order = 40
    )
    var temperature: Double = 0.3,

    @field:Setting(
        label = "Max Tokens",
        description = "Maximum number of tokens the model may generate. " +
                "Increase for very long texts; decrease to reduce cost.",
        type = SettingType.NUMBER,
        defaultValue = "4096",
        order = 50
    )
    var maxTokens: Int = 4096,

    @field:Setting(
        label = "Custom Headers (JSON)",
        description = "Optional extra HTTP headers sent with every request, as a JSON object. " +
                "The defaults below add OpenRouter site-attribution headers (harmless with other providers). " +
                "Leave blank to send no extra headers.",
        type = SettingType.TEXTAREA,
        defaultValue = """{"HTTP-Referer": "https://github.com/ahatem/QTranslate", "X-Title": "QTranslate", "X-OpenRouter-Title": "QTranslate"}""",
        order = 60
    )
    var customHeaders: String = """{"HTTP-Referer": "https://github.com/ahatem/QTranslate", "X-Title": "QTranslate", "X-OpenRouter-Title": "QTranslate"}"""

) : PluginSettings.Configurable()
