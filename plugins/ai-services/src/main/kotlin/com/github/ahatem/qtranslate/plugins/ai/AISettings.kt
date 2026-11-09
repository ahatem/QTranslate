package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
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
 * Settings are organized into seven groups visible in the plugin settings dialog:
 * - **Default Profile** — the profile every capability inherits from by default
 * - **Translation / Rewrite / Summarization / Spell Check / Dictionary / Vision** — one
 *   group per [AiCapability], each with its own overrides
 *
 * Each capability group starts with a "Use default profile" switch. While it is on (the
 * default), the capability resolves to exactly the Default profile and any stored
 * overrides in its group are ignored (the dialog hides them). Switching it off enables
 * field-wise overrides: every field left blank — or set to the `-1` sentinel for
 * temperature / max tokens — inherits the corresponding Default value.
 *
 * The Default fields keep their original names, defaults, and persistence keys
 * (`baseUrl`, `apiKey`, `model`, `temperature`, `maxTokens`, `customHeaders`), so
 * profiles configured before AI Plugin 2.0 load unchanged, with every capability
 * inheriting the Default by default.
 */
@SettingGroups(
    SettingGroup(key = "default",       title = "Default Profile", order = 10,
                 collapsible = true, defaultCollapsed = false),
    SettingGroup(key = "translation",   title = "Translation",     order = 20,
                 collapsible = true, defaultCollapsed = false),
    SettingGroup(key = "rewrite",       title = "Rewrite",         order = 30,
                 collapsible = true, defaultCollapsed = false),
    SettingGroup(key = "summarization", title = "Summarization",   order = 40,
                 collapsible = true, defaultCollapsed = false),
    SettingGroup(key = "spellCheck",    title = "Spell Check",     order = 50,
                 collapsible = true, defaultCollapsed = false),
    SettingGroup(key = "dictionary",    title = "Dictionary",      order = 60,
                 collapsible = true, defaultCollapsed = false),
    SettingGroup(key = "vision",        title = "Vision",          order = 70,
                 collapsible = true, defaultCollapsed = false)
)
data class AISettings(

    // -------------------------------------------------------------------------
    // Default Profile (profile inherited by every capability unless overridden)
    // -------------------------------------------------------------------------

    /**
     * Provider presets for the Default profile ([AiProviderPresets] ids).
     * The preset mainly feeds the base-URL default and the local-endpoint key rule;
     * [baseUrl] itself is always the authority.
     */
    @field:Setting(
        label       = "Provider Preset",
        description = "Well-known endpoint to start from. Picking a preset below fills the " +
                "Base URL with that provider's default address (unless you override it). " +
                "\"Custom\" leaves the Base URL to you.",
        type         = SettingType.DROPDOWN,
        options      = "openrouter,openai,local,custom",
        defaultValue = "openrouter",
        group        = "default",
        order        = 10
    )
    var preset: String = "openrouter",

    @field:Setting(
        label       = "Base URL",
        description = "OpenAI-compatible endpoint. Default: OpenRouter — a single key gives access to 300+ models. " +
                "You can also point this at OpenAI (https://api.openai.com/v1), " +
                "Mistral (https://api.mistral.ai/v1), " +
                "Gemini (https://generativelanguage.googleapis.com/v1beta/openai), " +
                "local Ollama (http://localhost:11434/v1), or any compatible server.",
        type         = SettingType.TEXT,
        defaultValue = "https://openrouter.ai/api/v1",
        group        = "default",
        order        = 20
    )
    var baseUrl: String = "https://openrouter.ai/api/v1",

    @field:Setting(
        label       = "API Key",
        description = "Your API key for the selected endpoint. " +
                "Get an OpenRouter key at openrouter.ai/keys. " +
                "Leave blank for a local endpoint such as Ollama or LM Studio, which need none.",
        type        = SettingType.PASSWORD,
        // Not marked required, though a hosted endpoint does need one. The dialog refuses to save
        // while a required field is blank, and that made a local endpoint impossible to configure
        // at all — the field said "leave blank for Ollama" and then would not let you. Whether a
        // key is needed depends on the endpoint, which an annotation cannot express, so the check
        // moved to where the endpoint is known: see [missingKeyError].
        isRequired  = false,
        group       = "default",
        order       = 30
    )
    var apiKey: String = "",

    @field:Setting(
        label       = "Model",
        description = "Exact model slug for your provider. For OpenRouter, use openrouter/free to " +
                "automatically select an available free model, or copy a current slug from openrouter.ai/models. " +
                "A 404 usually means that the model slug was renamed or removed. " +
                "Direct provider examples: gpt-4o · gemini-2.5-flash · mistral-small-latest.",
        type         = SettingType.TEXT,
        defaultValue = "openrouter/free",
        isRequired   = true,
        group        = "default",
        order        = 40
    )
    var model: String = "openrouter/free",

    @field:Setting(
        label       = "Temperature",
        description = "Controls output randomness. 0.0 = deterministic, 2.0 = very creative. " +
                "Recommended: 0.2 for translation/spell-check, 0.7 for summarization/rewriting.",
        type         = SettingType.SLIDER,
        defaultValue = "0.3",
        minValue     = 0.0,
        maxValue     = 2.0,
        step         = 0.05,
        group        = "default",
        order        = 50
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
        group        = "default",
        order        = 60
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
        group        = "default",
        order        = 70
    )
    var customHeaders: String = """{"HTTP-Referer": "https://github.com/ahatem/QTranslate", "X-Title": "QTranslate", "X-OpenRouter-Title": "QTranslate"}""",

    // -------------------------------------------------------------------------
    // Translation
    // -------------------------------------------------------------------------

    @field:Setting(
        label       = "Use Default Profile",
        description = "When on, Translation uses the Default Profile exactly and the overrides below are ignored.",
        type         = SettingType.BOOLEAN,
        defaultValue = "true",
        group        = "translation",
        order        = 10
    )
    var translationUseDefault: Boolean = true,

    @field:Setting(
        label       = "Provider Preset",
        description = "Overrides the Default preset for Translation. Leave blank to inherit the Default preset.",
        type         = SettingType.DROPDOWN,
        options      = "openrouter,openai,local,custom",
        defaultValue = "",
        group        = "translation",
        order        = 20,
        showIf       = "translationUseDefault=false"
    )
    var translationPreset: String = "",

    @field:Setting(
        label       = "Base URL",
        description = "Translation endpoint override. Leave blank to inherit the Default Base URL.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "translation",
        order        = 30,
        showIf       = "translationUseDefault=false"
    )
    var translationBaseUrl: String = "",

    @field:Setting(
        label       = "API Key",
        description = "Translation key override. Leave blank to inherit the Default key (or no key, for a local endpoint).",
        type         = SettingType.PASSWORD,
        defaultValue = "",
        group        = "translation",
        order        = 40,
        showIf       = "translationUseDefault=false"
    )
    var translationApiKey: String = "",

    @field:Setting(
        label       = "Model",
        description = "Translation model override. Leave blank to inherit the Default model.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "translation",
        order        = 50,
        showIf       = "translationUseDefault=false"
    )
    var translationModel: String = "",

    @field:Setting(
        label       = "Custom Headers (JSON)",
        description = "Translation headers override, as a JSON object. Leave blank to inherit the Default headers.",
        type         = SettingType.TEXTAREA,
        defaultValue = "",
        rows         = 2,
        group        = "translation",
        order        = 60,
        showIf       = "translationUseDefault=false"
    )
    var translationCustomHeaders: String = "",

    @field:Setting(
        label       = "Temperature",
        description = "Translation temperature override. -1 means inherit the Default temperature.",
        type         = SettingType.NUMBER,
        defaultValue = "-1.0",
        minValue     = -1.0,
        maxValue     = 2.0,
        step         = 0.1,
        group        = "translation",
        order        = 70,
        showIf       = "translationUseDefault=false"
    )
    var translationTemperature: Double = -1.0,

    @field:Setting(
        label       = "Max Tokens",
        description = "Translation max tokens override. -1 means inherit the Default max tokens.",
        type         = SettingType.NUMBER,
        defaultValue = "-1",
        minValue     = -1.0,
        maxValue     = 131072.0,
        step         = 256.0,
        group        = "translation",
        order        = 80,
        showIf       = "translationUseDefault=false"
    )
    var translationMaxTokens: Int = -1,

    // -------------------------------------------------------------------------
    // Rewrite
    // -------------------------------------------------------------------------

    @field:Setting(
        label       = "Use Default Profile",
        description = "When on, Rewrite uses the Default Profile exactly and the overrides below are ignored.",
        type         = SettingType.BOOLEAN,
        defaultValue = "true",
        group        = "rewrite",
        order        = 10
    )
    var rewriteUseDefault: Boolean = true,

    @field:Setting(
        label       = "Provider Preset",
        description = "Overrides the Default preset for Rewrite. Leave blank to inherit the Default preset.",
        type         = SettingType.DROPDOWN,
        options      = "openrouter,openai,local,custom",
        defaultValue = "",
        group        = "rewrite",
        order        = 20,
        showIf       = "rewriteUseDefault=false"
    )
    var rewritePreset: String = "",

    @field:Setting(
        label       = "Base URL",
        description = "Rewrite endpoint override. Leave blank to inherit the Default Base URL.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "rewrite",
        order        = 30,
        showIf       = "rewriteUseDefault=false"
    )
    var rewriteBaseUrl: String = "",

    @field:Setting(
        label       = "API Key",
        description = "Rewrite key override. Leave blank to inherit the Default key (or no key, for a local endpoint).",
        type         = SettingType.PASSWORD,
        defaultValue = "",
        group        = "rewrite",
        order        = 40,
        showIf       = "rewriteUseDefault=false"
    )
    var rewriteApiKey: String = "",

    @field:Setting(
        label       = "Model",
        description = "Rewrite model override. Leave blank to inherit the Default model.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "rewrite",
        order        = 50,
        showIf       = "rewriteUseDefault=false"
    )
    var rewriteModel: String = "",

    @field:Setting(
        label       = "Custom Headers (JSON)",
        description = "Rewrite headers override, as a JSON object. Leave blank to inherit the Default headers.",
        type         = SettingType.TEXTAREA,
        defaultValue = "",
        rows         = 2,
        group        = "rewrite",
        order        = 60,
        showIf       = "rewriteUseDefault=false"
    )
    var rewriteCustomHeaders: String = "",

    @field:Setting(
        label       = "Temperature",
        description = "Rewrite temperature override. -1 means inherit the Default temperature.",
        type         = SettingType.NUMBER,
        defaultValue = "-1.0",
        minValue     = -1.0,
        maxValue     = 2.0,
        step         = 0.1,
        group        = "rewrite",
        order        = 70,
        showIf       = "rewriteUseDefault=false"
    )
    var rewriteTemperature: Double = -1.0,

    @field:Setting(
        label       = "Max Tokens",
        description = "Rewrite max tokens override. -1 means inherit the Default max tokens.",
        type         = SettingType.NUMBER,
        defaultValue = "-1",
        minValue     = -1.0,
        maxValue     = 131072.0,
        step         = 256.0,
        group        = "rewrite",
        order        = 80,
        showIf       = "rewriteUseDefault=false"
    )
    var rewriteMaxTokens: Int = -1,

    // -------------------------------------------------------------------------
    // Summarization
    // -------------------------------------------------------------------------

    @field:Setting(
        label       = "Use Default Profile",
        description = "When on, Summarization uses the Default Profile exactly and the overrides below are ignored.",
        type         = SettingType.BOOLEAN,
        defaultValue = "true",
        group        = "summarization",
        order        = 10
    )
    var summarizationUseDefault: Boolean = true,

    @field:Setting(
        label       = "Provider Preset",
        description = "Overrides the Default preset for Summarization. Leave blank to inherit the Default preset.",
        type         = SettingType.DROPDOWN,
        options      = "openrouter,openai,local,custom",
        defaultValue = "",
        group        = "summarization",
        order        = 20,
        showIf       = "summarizationUseDefault=false"
    )
    var summarizationPreset: String = "",

    @field:Setting(
        label       = "Base URL",
        description = "Summarization endpoint override. Leave blank to inherit the Default Base URL.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "summarization",
        order        = 30,
        showIf       = "summarizationUseDefault=false"
    )
    var summarizationBaseUrl: String = "",

    @field:Setting(
        label       = "API Key",
        description = "Summarization key override. Leave blank to inherit the Default key (or no key, for a local endpoint).",
        type         = SettingType.PASSWORD,
        defaultValue = "",
        group        = "summarization",
        order        = 40,
        showIf       = "summarizationUseDefault=false"
    )
    var summarizationApiKey: String = "",

    @field:Setting(
        label       = "Model",
        description = "Summarization model override. Leave blank to inherit the Default model.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "summarization",
        order        = 50,
        showIf       = "summarizationUseDefault=false"
    )
    var summarizationModel: String = "",

    @field:Setting(
        label       = "Custom Headers (JSON)",
        description = "Summarization headers override, as a JSON object. Leave blank to inherit the Default headers.",
        type         = SettingType.TEXTAREA,
        defaultValue = "",
        rows         = 2,
        group        = "summarization",
        order        = 60,
        showIf       = "summarizationUseDefault=false"
    )
    var summarizationCustomHeaders: String = "",

    @field:Setting(
        label       = "Temperature",
        description = "Summarization temperature override. -1 means inherit the Default temperature.",
        type         = SettingType.NUMBER,
        defaultValue = "-1.0",
        minValue     = -1.0,
        maxValue     = 2.0,
        step         = 0.1,
        group        = "summarization",
        order        = 70,
        showIf       = "summarizationUseDefault=false"
    )
    var summarizationTemperature: Double = -1.0,

    @field:Setting(
        label       = "Max Tokens",
        description = "Summarization max tokens override. -1 means inherit the Default max tokens.",
        type         = SettingType.NUMBER,
        defaultValue = "-1",
        minValue     = -1.0,
        maxValue     = 131072.0,
        step         = 256.0,
        group        = "summarization",
        order        = 80,
        showIf       = "summarizationUseDefault=false"
    )
    var summarizationMaxTokens: Int = -1,

    // -------------------------------------------------------------------------
    // Spell Check
    // -------------------------------------------------------------------------

    @field:Setting(
        label       = "Use Default Profile",
        description = "When on, Spell Check uses the Default Profile exactly and the overrides below are ignored.",
        type         = SettingType.BOOLEAN,
        defaultValue = "true",
        group        = "spellCheck",
        order        = 10
    )
    var spellCheckUseDefault: Boolean = true,

    @field:Setting(
        label       = "Provider Preset",
        description = "Overrides the Default preset for Spell Check. Leave blank to inherit the Default preset.",
        type         = SettingType.DROPDOWN,
        options      = "openrouter,openai,local,custom",
        defaultValue = "",
        group        = "spellCheck",
        order        = 20,
        showIf       = "spellCheckUseDefault=false"
    )
    var spellCheckPreset: String = "",

    @field:Setting(
        label       = "Base URL",
        description = "Spell Check endpoint override. Leave blank to inherit the Default Base URL.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "spellCheck",
        order        = 30,
        showIf       = "spellCheckUseDefault=false"
    )
    var spellCheckBaseUrl: String = "",

    @field:Setting(
        label       = "API Key",
        description = "Spell Check key override. Leave blank to inherit the Default key (or no key, for a local endpoint).",
        type         = SettingType.PASSWORD,
        defaultValue = "",
        group        = "spellCheck",
        order        = 40,
        showIf       = "spellCheckUseDefault=false"
    )
    var spellCheckApiKey: String = "",

    @field:Setting(
        label       = "Model",
        description = "Spell Check model override. Leave blank to inherit the Default model.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "spellCheck",
        order        = 50,
        showIf       = "spellCheckUseDefault=false"
    )
    var spellCheckModel: String = "",

    @field:Setting(
        label       = "Custom Headers (JSON)",
        description = "Spell Check headers override, as a JSON object. Leave blank to inherit the Default headers.",
        type         = SettingType.TEXTAREA,
        defaultValue = "",
        rows         = 2,
        group        = "spellCheck",
        order        = 60,
        showIf       = "spellCheckUseDefault=false"
    )
    var spellCheckCustomHeaders: String = "",

    @field:Setting(
        label       = "Temperature",
        description = "Spell Check temperature override. -1 means inherit the Default temperature.",
        type         = SettingType.NUMBER,
        defaultValue = "-1.0",
        minValue     = -1.0,
        maxValue     = 2.0,
        step         = 0.1,
        group        = "spellCheck",
        order        = 70,
        showIf       = "spellCheckUseDefault=false"
    )
    var spellCheckTemperature: Double = -1.0,

    @field:Setting(
        label       = "Max Tokens",
        description = "Spell Check max tokens override. -1 means inherit the Default max tokens.",
        type         = SettingType.NUMBER,
        defaultValue = "-1",
        minValue     = -1.0,
        maxValue     = 131072.0,
        step         = 256.0,
        group        = "spellCheck",
        order        = 80,
        showIf       = "spellCheckUseDefault=false"
    )
    var spellCheckMaxTokens: Int = -1,

    // -------------------------------------------------------------------------
    // Dictionary
    // -------------------------------------------------------------------------

    @field:Setting(
        label       = "Use Default Profile",
        description = "When on, Dictionary uses the Default Profile exactly and the overrides below are ignored.",
        type         = SettingType.BOOLEAN,
        defaultValue = "true",
        group        = "dictionary",
        order        = 10
    )
    var dictionaryUseDefault: Boolean = true,

    @field:Setting(
        label       = "Provider Preset",
        description = "Overrides the Default preset for Dictionary. Leave blank to inherit the Default preset.",
        type         = SettingType.DROPDOWN,
        options      = "openrouter,openai,local,custom",
        defaultValue = "",
        group        = "dictionary",
        order        = 20,
        showIf       = "dictionaryUseDefault=false"
    )
    var dictionaryPreset: String = "",

    @field:Setting(
        label       = "Base URL",
        description = "Dictionary endpoint override. Leave blank to inherit the Default Base URL.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "dictionary",
        order        = 30,
        showIf       = "dictionaryUseDefault=false"
    )
    var dictionaryBaseUrl: String = "",

    @field:Setting(
        label       = "API Key",
        description = "Dictionary key override. Leave blank to inherit the Default key (or no key, for a local endpoint).",
        type         = SettingType.PASSWORD,
        defaultValue = "",
        group        = "dictionary",
        order        = 40,
        showIf       = "dictionaryUseDefault=false"
    )
    var dictionaryApiKey: String = "",

    @field:Setting(
        label       = "Model",
        description = "Dictionary model override. Leave blank to inherit the Default model.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "dictionary",
        order        = 50,
        showIf       = "dictionaryUseDefault=false"
    )
    var dictionaryModel: String = "",

    @field:Setting(
        label       = "Custom Headers (JSON)",
        description = "Dictionary headers override, as a JSON object. Leave blank to inherit the Default headers.",
        type         = SettingType.TEXTAREA,
        defaultValue = "",
        rows         = 2,
        group        = "dictionary",
        order        = 60,
        showIf       = "dictionaryUseDefault=false"
    )
    var dictionaryCustomHeaders: String = "",

    @field:Setting(
        label       = "Temperature",
        description = "Dictionary temperature override. -1 means inherit the Default temperature.",
        type         = SettingType.NUMBER,
        defaultValue = "-1.0",
        minValue     = -1.0,
        maxValue     = 2.0,
        step         = 0.1,
        group        = "dictionary",
        order        = 70,
        showIf       = "dictionaryUseDefault=false"
    )
    var dictionaryTemperature: Double = -1.0,

    @field:Setting(
        label       = "Max Tokens",
        description = "Dictionary max tokens override. -1 means inherit the Default max tokens.",
        type         = SettingType.NUMBER,
        defaultValue = "-1",
        minValue     = -1.0,
        maxValue     = 131072.0,
        step         = 256.0,
        group        = "dictionary",
        order        = 80,
        showIf       = "dictionaryUseDefault=false"
    )
    var dictionaryMaxTokens: Int = -1,

    // -------------------------------------------------------------------------
    // Vision
    // -------------------------------------------------------------------------

    @field:Setting(
        label       = "Use Default Profile",
        description = "When on, Vision uses the Default Profile exactly and the overrides below are ignored.",
        type         = SettingType.BOOLEAN,
        defaultValue = "true",
        group        = "vision",
        order        = 10
    )
    var visionUseDefault: Boolean = true,

    @field:Setting(
        label       = "Provider Preset",
        description = "Overrides the Default preset for Vision. Leave blank to inherit the Default preset.",
        type         = SettingType.DROPDOWN,
        options      = "openrouter,openai,local,custom",
        defaultValue = "",
        group        = "vision",
        order        = 20,
        showIf       = "visionUseDefault=false"
    )
    var visionPreset: String = "",

    @field:Setting(
        label       = "Base URL",
        description = "Vision endpoint override. Leave blank to inherit the Default Base URL.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "vision",
        order        = 30,
        showIf       = "visionUseDefault=false"
    )
    var visionBaseUrl: String = "",

    @field:Setting(
        label       = "API Key",
        description = "Vision key override. Leave blank to inherit the Default key (or no key, for a local endpoint).",
        type         = SettingType.PASSWORD,
        defaultValue = "",
        group        = "vision",
        order        = 40,
        showIf       = "visionUseDefault=false"
    )
    var visionApiKey: String = "",

    @field:Setting(
        label       = "Model",
        description = "Vision model override. Leave blank to inherit the Default model.",
        type         = SettingType.TEXT,
        defaultValue = "",
        group        = "vision",
        order        = 50,
        showIf       = "visionUseDefault=false"
    )
    var visionModel: String = "",

    @field:Setting(
        label       = "Custom Headers (JSON)",
        description = "Vision headers override, as a JSON object. Leave blank to inherit the Default headers.",
        type         = SettingType.TEXTAREA,
        defaultValue = "",
        rows         = 2,
        group        = "vision",
        order        = 60,
        showIf       = "visionUseDefault=false"
    )
    var visionCustomHeaders: String = "",

    @field:Setting(
        label       = "Temperature",
        description = "Vision temperature override. -1 means inherit the Default temperature.",
        type         = SettingType.NUMBER,
        defaultValue = "-1.0",
        minValue     = -1.0,
        maxValue     = 2.0,
        step         = 0.1,
        group        = "vision",
        order        = 70,
        showIf       = "visionUseDefault=false"
    )
    var visionTemperature: Double = -1.0,

    @field:Setting(
        label       = "Max Tokens",
        description = "Vision max tokens override. -1 means inherit the Default max tokens.",
        type         = SettingType.NUMBER,
        defaultValue = "-1",
        minValue     = -1.0,
        maxValue     = 131072.0,
        step         = 256.0,
        group        = "vision",
        order        = 80,
        showIf       = "visionUseDefault=false"
    )
    var visionMaxTokens: Int = -1

) : PluginSettings.Configurable() {

    /**
     * Whether the Default [baseUrl] points at something running on this machine or
     * network. Delegates to [defaultProfile]; see [AiProfile.isLocalEndpoint] for the
     * matching rule.
     */
    val isLocalEndpoint: Boolean
        get() = defaultProfile().isLocalEndpoint

    /**
     * The error to report when the Default profile has no key and needs one, or null when
     * none is needed. Delegates to [defaultProfile]; see [AiProfile.missingKeyError].
     */
    fun missingKeyError(): ServiceError? = defaultProfile().missingKeyError()

    /**
     * The resolved profile for the Default itself: the stored Default fields verbatim,
     * except that a blank [baseUrl] with a non-custom [preset] falls back to that
     * preset's default address ([AiProviderPresets.defaultBaseUrl]).
     */
    fun defaultProfile(): AiProfile {
        val resolvedBaseUrl = baseUrl.ifBlank {
            val presetDefault = AiProviderPresets.defaultBaseUrl(preset)
            if (preset == "custom") "" else presetDefault ?: ""
        }
        return AiProfile(
            preset       = preset,
            baseUrl      = resolvedBaseUrl,
            apiKey       = apiKey,
            model        = model,
            customHeaders = customHeaders,
            temperature  = temperature,
            maxTokens    = maxTokens
        )
    }

    /**
     * The fully resolved [AiProfile] for [capability].
     *
     * When the capability's "use default" switch is on, the Default profile is returned
     * outright and any stored overrides are ignored. Otherwise each field resolves as:
     * - preset: override when non-blank, else the Default preset
     * - baseUrl: override when non-blank, else the Default base URL; when the result is
     *   still blank and the resolved preset is non-custom, that preset's default address
     * - apiKey: override when non-blank; blank resolves to no key when the resolved
     *   base URL is a local endpoint, else the Default key. (Consequence: an independent
     *   profile that genuinely needs no key must point at a local endpoint — a blank
     *   override cannot erase the Default key for a hosted endpoint.)
     * - model: override when non-blank, else the Default model
     * - customHeaders: override when non-blank, else the Default headers
     * - temperature: override when >= 0, else the Default temperature (negative sentinel
     *   means inherit)
     * - maxTokens: override when >= 1, else the Default max tokens
     */
    fun profileFor(capability: AiCapability): AiProfile {
        val defaults = defaultProfile()
        val (useDefault, capPreset, capBaseUrl, capApiKey, capModel, capHeaders, capTemp, capTokens) =
            when (capability) {
                AiCapability.TRANSLATION -> Override(
                    translationUseDefault, translationPreset, translationBaseUrl, translationApiKey,
                    translationModel, translationCustomHeaders, translationTemperature, translationMaxTokens
                )
                AiCapability.REWRITE -> Override(
                    rewriteUseDefault, rewritePreset, rewriteBaseUrl, rewriteApiKey,
                    rewriteModel, rewriteCustomHeaders, rewriteTemperature, rewriteMaxTokens
                )
                AiCapability.SUMMARIZATION -> Override(
                    summarizationUseDefault, summarizationPreset, summarizationBaseUrl, summarizationApiKey,
                    summarizationModel, summarizationCustomHeaders, summarizationTemperature, summarizationMaxTokens
                )
                AiCapability.SPELL_CHECK -> Override(
                    spellCheckUseDefault, spellCheckPreset, spellCheckBaseUrl, spellCheckApiKey,
                    spellCheckModel, spellCheckCustomHeaders, spellCheckTemperature, spellCheckMaxTokens
                )
                AiCapability.DICTIONARY -> Override(
                    dictionaryUseDefault, dictionaryPreset, dictionaryBaseUrl, dictionaryApiKey,
                    dictionaryModel, dictionaryCustomHeaders, dictionaryTemperature, dictionaryMaxTokens
                )
                AiCapability.VISION -> Override(
                    visionUseDefault, visionPreset, visionBaseUrl, visionApiKey,
                    visionModel, visionCustomHeaders, visionTemperature, visionMaxTokens
                )
            }

        if (useDefault) return defaults

        val resolvedPreset = capPreset.ifBlank { defaults.preset }
        val resolvedBaseUrl = capBaseUrl.ifBlank { defaults.baseUrl }.ifBlank {
            val presetDefault = AiProviderPresets.defaultBaseUrl(resolvedPreset)
            if (resolvedPreset == "custom") "" else presetDefault ?: ""
        }
        val resolvedApiKey = capApiKey.ifBlank {
            if (isLocalBaseUrl(resolvedBaseUrl)) "" else defaults.apiKey
        }
        return AiProfile(
            preset       = resolvedPreset,
            baseUrl      = resolvedBaseUrl,
            apiKey       = resolvedApiKey,
            model        = capModel.ifBlank { defaults.model },
            customHeaders = capHeaders.ifBlank { defaults.customHeaders },
            temperature  = if (capTemp < 0.0) defaults.temperature else capTemp,
            maxTokens    = if (capTokens < 1) defaults.maxTokens else capTokens
        )
    }

    /** The eight stored fields of one capability's override block. */
    private data class Override(
        val useDefault: Boolean,
        val preset: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val customHeaders: String,
        val temperature: Double,
        val maxTokens: Int
    )
}
