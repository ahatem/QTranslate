package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingGroup
import com.github.ahatem.qtranslate.api.settings.SettingGroups
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
 *
 * Settings are organized into three groups visible in the plugin settings dialog:
 * - **Endpoint** — base URL and API key
 * - **Model** — model identifier
 * - **Advanced** — temperature, max tokens, custom headers (collapsed by default)
 */
@SettingGroups(
    SettingGroup(key = "endpoint", title = "Endpoint",         order = 10),
    SettingGroup(key = "model",    title = "Model",            order = 20),
    SettingGroup(key = "advanced", title = "Advanced",         order = 30,
                 collapsible = true, defaultCollapsed = true)
)
data class AISettings(

    @field:Setting(
        label       = "Base URL",
        description = "OpenAI-compatible endpoint. Default: OpenRouter — a single key gives access to 300+ models. " +
                "You can also point this at OpenAI (https://api.openai.com/v1), " +
                "Mistral (https://api.mistral.ai/v1), " +
                "Gemini (https://generativelanguage.googleapis.com/v1beta/openai), " +
                "local Ollama (http://localhost:11434/v1), or any compatible server.",
        type         = SettingType.TEXT,
        defaultValue = "https://openrouter.ai/api/v1",
        group        = "endpoint",
        order        = 10
    )
    var baseUrl: String = "https://openrouter.ai/api/v1",

    @field:Setting(
        label       = "API Key",
        description = "Your API key for the selected endpoint. " +
                "Get an OpenRouter key at openrouter.ai/keys. " +
                "Leave blank for local Ollama.",
        type        = SettingType.PASSWORD,
        isRequired  = true,
        group       = "endpoint",
        order       = 20
    )
    var apiKey: String = "",

    @field:Setting(
        label       = "Model",
        description = "Model identifier. OpenRouter examples: google/gemini-flash-1.5-8b · openai/gpt-4o · " +
                "anthropic/claude-3-5-sonnet · mistralai/mistral-small. " +
                "Direct provider examples: gpt-4o · gemini-2.5-flash · mistral-small-latest. " +
                "Append :free to use a free-tier model on OpenRouter, e.g. google/gemini-flash-1.5-8b:free.",
        type         = SettingType.TEXT,
        defaultValue = "google/gemini-flash-1.5-8b",
        isRequired   = true,
        group        = "model",
        order        = 10
    )
    var model: String = "google/gemini-flash-1.5-8b",

    @field:Setting(
        label       = "Temperature",
        description = "Controls output randomness. 0.0 = deterministic, 2.0 = very creative. " +
                "Recommended: 0.2 for translation/spell-check, 0.7 for summarization/rewriting.",
        type         = SettingType.SLIDER,
        defaultValue = "0.3",
        minValue     = 0.0,
        maxValue     = 2.0,
        step         = 0.05,
        group        = "advanced",
        order        = 10
    )
    var temperature: Double = 0.3,

    @field:Setting(
        label       = "Max Tokens",
        description = "Maximum number of tokens the model may generate. " +
                "Increase for very long texts; decrease to reduce cost.",
        type         = SettingType.NUMBER,
        defaultValue = "4096",
        minValue     = 1.0,
        maxValue     = 131072.0,
        step         = 256.0,
        group        = "advanced",
        order        = 20
    )
    var maxTokens: Int = 4096,

    @field:Setting(
        label       = "Custom Headers (JSON)",
        description = "Optional extra HTTP headers sent with every request, as a JSON object. " +
                "The defaults below add OpenRouter site-attribution headers (harmless with other providers). " +
                "Leave blank to send no extra headers.",
        type         = SettingType.TEXTAREA,
        defaultValue = """{"HTTP-Referer": "https://github.com/ahatem/QTranslate", "X-Title": "QTranslate", "X-OpenRouter-Title": "QTranslate"}""",
        rows         = 4,
        group        = "advanced",
        order        = 30
    )
    var customHeaders: String = """{"HTTP-Referer": "https://github.com/ahatem/QTranslate", "X-Title": "QTranslate", "X-OpenRouter-Title": "QTranslate"}"""

) : PluginSettings.Configurable()
