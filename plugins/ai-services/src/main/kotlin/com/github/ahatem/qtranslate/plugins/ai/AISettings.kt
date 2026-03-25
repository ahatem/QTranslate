package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType

data class AISettings(

    @field:Setting(
        label = "AI Provider",
        description = "The AI provider to use. Each provider requires its own API key.",
        type = SettingType.DROPDOWN,
        options = "Gemini,OpenAI,Mistral,Anthropic",
        defaultValue = "Gemini",
        order = 10
    )
    var provider: String = "Gemini",

    @field:Setting(
        label = "Model",
        description = "Gemini: gemini-3-flash-preview · gemini-2.5-flash | OpenAI: gpt-4.1 · gpt-4.1-mini · gpt-4o | Mistral: mistral-small-latest · mistral-large-latest | Anthropic: claude-sonnet-4-6 · claude-opus-4-6 · claude-haiku-4-5",
        type = SettingType.TEXT,
        defaultValue = "gemini-flash-lite-latest",
        isRequired = true,
        order = 20
    )
    var model: String = "gemini-flash-lite-latest",

    @field:Setting(
        label = "API Key",
        description = "Your API key for the selected provider.",
        type = SettingType.PASSWORD,
        isRequired = true,
        order = 30
    )
    var apiKey: String = "",

    @field:Setting(
        label = "Temperature",
        description = "Controls randomness (0.0 = deterministic, 1.0 = creative). Recommended: 0.2 for translation/spell-check, 0.7 for summarization/rewriting.",
        type = SettingType.NUMBER,
        defaultValue = "0.3",
        order = 40
    )
    var temperature: Double = 0.3

) : PluginSettings.Configurable()