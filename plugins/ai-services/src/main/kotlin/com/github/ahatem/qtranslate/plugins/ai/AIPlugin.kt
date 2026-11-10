package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold

/**
 * Entry point for the AI Plugin.
 *
 * Provides six AI-powered services backed by a shared OpenAI-compatible chat
 * completions client ([AIServiceClient]):
 * - **AI Translate** — translates text between any language pair
 * - **AI Summarizer** — condenses text to short / medium / long summaries
 * - **AI Rewriter** — rewrites text in formal / casual / concise / detailed / simplified style
 * - **AI Spell Checker** — detects and highlights spelling, grammar, and punctuation errors
 * - **AI Dictionary** — full definitions, part-of-speech, phonetics, synonyms for any word
 * - **AI Vision OCR** — extracts text from images using vision-capable models
 *
 * ### Profiles (Default + per-capability overrides)
 * Settings are a **Default profile** plus one override block per capability
 * (Translation, Rewrite, Summarization, Spell Check, Dictionary, Vision).
 * While a capability's "Use Default Profile" switch is on, it resolves to
 * exactly the Default profile. Switching it off enables field-wise overrides:
 * every blank field — or the `-1` sentinel for temperature / max tokens —
 * inherits the corresponding Default value (see [AISettings.profileFor]).
 * Each service is built with `{ settings.profileFor(<its capability>) }`, so
 * changes in Settings → Plugins take effect on the next API call without any
 * service rebuild.
 *
 * API keys are never persisted in settings: the Default key lives in the
 * secret store under `apiKey`, and each override key under its own secret
 * key (`translationApiKey`, `rewriteApiKey`, `summarizationApiKey`,
 * `spellCheckApiKey`, `dictionaryApiKey`, `visionApiKey`). Keys are never
 * logged.
 *
 * ### Protocol
 * The plugin speaks exactly one provider shape: `POST {baseUrl}/chat/completions`
 * (OpenAI-compatible chat completions). It does not implement the Responses API
 * (`/responses`), model listing (`/models`), embeddings, or Azure deployment
 * routes. Defaults to **OpenRouter** (`https://openrouter.ai/api/v1`); users
 * can override [AISettings.baseUrl] to point at any OpenAI-compatible server.
 *
 * ### Upgrading from an older version
 * Previous versions stored a `provider` key (Gemini / OpenAI / Mistral / Anthropic).
 * That key is no longer read. The Default fields keep their original names and
 * persistence keys (`baseUrl`, `apiKey`, `model`, `temperature`, `maxTokens`,
 * `customHeaders`), so profiles configured before AI Plugin 2.0 load unchanged
 * as the Default profile, with every capability inheriting it by default.
 * Users who relied on the Gemini or Anthropic native endpoints should either
 * switch to OpenRouter or update the Base URL in plugin settings.
 */
class AIPlugin : Plugin<AISettings> {

    private lateinit var pluginContext: PluginContext
    private val httpClient: HttpClient get() = pluginContext.http
    private lateinit var serviceClient: AIServiceClient

    private var settings: AISettings = AISettings()
    private var activeServices: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        pluginContext = context
        val defaults = AISettings()

        settings = AISettings(
            preset = context.settings.getString(KEY_PRESET) ?: defaults.preset,
            baseUrl = context.settings.getString(KEY_BASE_URL) ?: defaults.baseUrl,
            apiKey = context.secrets.get(KEY_API_KEY).orEmpty(),
            model = context.settings.getString(KEY_MODEL) ?: defaults.model,
            temperature = context.settings.getDouble(KEY_TEMPERATURE, defaults.temperature),
            maxTokens = context.settings.getInt(KEY_MAX_TOKENS, defaults.maxTokens),
            customHeaders = context.settings.getString(KEY_CUSTOM_HEADERS) ?: defaults.customHeaders,
            translationUseDefault = context.settings.getBoolean(KEY_TRANSLATION_USE_DEFAULT, defaults.translationUseDefault),
            translationPreset = context.settings.getString(KEY_TRANSLATION_PRESET) ?: defaults.translationPreset,
            translationBaseUrl = context.settings.getString(KEY_TRANSLATION_BASE_URL) ?: defaults.translationBaseUrl,
            translationApiKey = context.secrets.get(KEY_TRANSLATION_API_KEY).orEmpty(),
            translationModel = context.settings.getString(KEY_TRANSLATION_MODEL) ?: defaults.translationModel,
            translationCustomHeaders = context.settings.getString(KEY_TRANSLATION_CUSTOM_HEADERS) ?: defaults.translationCustomHeaders,
            translationTemperature = context.settings.getDouble(KEY_TRANSLATION_TEMPERATURE, defaults.translationTemperature),
            translationMaxTokens = context.settings.getInt(KEY_TRANSLATION_MAX_TOKENS, defaults.translationMaxTokens),
            rewriteUseDefault = context.settings.getBoolean(KEY_REWRITE_USE_DEFAULT, defaults.rewriteUseDefault),
            rewritePreset = context.settings.getString(KEY_REWRITE_PRESET) ?: defaults.rewritePreset,
            rewriteBaseUrl = context.settings.getString(KEY_REWRITE_BASE_URL) ?: defaults.rewriteBaseUrl,
            rewriteApiKey = context.secrets.get(KEY_REWRITE_API_KEY).orEmpty(),
            rewriteModel = context.settings.getString(KEY_REWRITE_MODEL) ?: defaults.rewriteModel,
            rewriteCustomHeaders = context.settings.getString(KEY_REWRITE_CUSTOM_HEADERS) ?: defaults.rewriteCustomHeaders,
            rewriteTemperature = context.settings.getDouble(KEY_REWRITE_TEMPERATURE, defaults.rewriteTemperature),
            rewriteMaxTokens = context.settings.getInt(KEY_REWRITE_MAX_TOKENS, defaults.rewriteMaxTokens),
            summarizationUseDefault = context.settings.getBoolean(KEY_SUMMARIZATION_USE_DEFAULT, defaults.summarizationUseDefault),
            summarizationPreset = context.settings.getString(KEY_SUMMARIZATION_PRESET) ?: defaults.summarizationPreset,
            summarizationBaseUrl = context.settings.getString(KEY_SUMMARIZATION_BASE_URL) ?: defaults.summarizationBaseUrl,
            summarizationApiKey = context.secrets.get(KEY_SUMMARIZATION_API_KEY).orEmpty(),
            summarizationModel = context.settings.getString(KEY_SUMMARIZATION_MODEL) ?: defaults.summarizationModel,
            summarizationCustomHeaders = context.settings.getString(KEY_SUMMARIZATION_CUSTOM_HEADERS) ?: defaults.summarizationCustomHeaders,
            summarizationTemperature = context.settings.getDouble(KEY_SUMMARIZATION_TEMPERATURE, defaults.summarizationTemperature),
            summarizationMaxTokens = context.settings.getInt(KEY_SUMMARIZATION_MAX_TOKENS, defaults.summarizationMaxTokens),
            spellCheckUseDefault = context.settings.getBoolean(KEY_SPELL_CHECK_USE_DEFAULT, defaults.spellCheckUseDefault),
            spellCheckPreset = context.settings.getString(KEY_SPELL_CHECK_PRESET) ?: defaults.spellCheckPreset,
            spellCheckBaseUrl = context.settings.getString(KEY_SPELL_CHECK_BASE_URL) ?: defaults.spellCheckBaseUrl,
            spellCheckApiKey = context.secrets.get(KEY_SPELL_CHECK_API_KEY).orEmpty(),
            spellCheckModel = context.settings.getString(KEY_SPELL_CHECK_MODEL) ?: defaults.spellCheckModel,
            spellCheckCustomHeaders = context.settings.getString(KEY_SPELL_CHECK_CUSTOM_HEADERS) ?: defaults.spellCheckCustomHeaders,
            spellCheckTemperature = context.settings.getDouble(KEY_SPELL_CHECK_TEMPERATURE, defaults.spellCheckTemperature),
            spellCheckMaxTokens = context.settings.getInt(KEY_SPELL_CHECK_MAX_TOKENS, defaults.spellCheckMaxTokens),
            dictionaryUseDefault = context.settings.getBoolean(KEY_DICTIONARY_USE_DEFAULT, defaults.dictionaryUseDefault),
            dictionaryPreset = context.settings.getString(KEY_DICTIONARY_PRESET) ?: defaults.dictionaryPreset,
            dictionaryBaseUrl = context.settings.getString(KEY_DICTIONARY_BASE_URL) ?: defaults.dictionaryBaseUrl,
            dictionaryApiKey = context.secrets.get(KEY_DICTIONARY_API_KEY).orEmpty(),
            dictionaryModel = context.settings.getString(KEY_DICTIONARY_MODEL) ?: defaults.dictionaryModel,
            dictionaryCustomHeaders = context.settings.getString(KEY_DICTIONARY_CUSTOM_HEADERS) ?: defaults.dictionaryCustomHeaders,
            dictionaryTemperature = context.settings.getDouble(KEY_DICTIONARY_TEMPERATURE, defaults.dictionaryTemperature),
            dictionaryMaxTokens = context.settings.getInt(KEY_DICTIONARY_MAX_TOKENS, defaults.dictionaryMaxTokens),
            visionUseDefault = context.settings.getBoolean(KEY_VISION_USE_DEFAULT, defaults.visionUseDefault),
            visionPreset = context.settings.getString(KEY_VISION_PRESET) ?: defaults.visionPreset,
            visionBaseUrl = context.settings.getString(KEY_VISION_BASE_URL) ?: defaults.visionBaseUrl,
            visionApiKey = context.secrets.get(KEY_VISION_API_KEY).orEmpty(),
            visionModel = context.settings.getString(KEY_VISION_MODEL) ?: defaults.visionModel,
            visionCustomHeaders = context.settings.getString(KEY_VISION_CUSTOM_HEADERS) ?: defaults.visionCustomHeaders,
            visionTemperature = context.settings.getDouble(KEY_VISION_TEMPERATURE, defaults.visionTemperature),
            visionMaxTokens = context.settings.getInt(KEY_VISION_MAX_TOKENS, defaults.visionMaxTokens)
        )

        serviceClient = AIServiceClient(
            pluginContext = context,
            httpClient = httpClient
        )

        pluginContext.logger.info(
            "AI Plugin initialized [baseUrl=${settings.baseUrl}, model=${settings.model}]"
        )
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        // Warn once per distinct profile that needs a key. A local endpoint runs
        // perfectly well without one, and warning there would send someone hunting
        // for a problem that is not there. Messages name the endpoint, never a key.
        distinctProfiles().forEach { profile ->
            if (profile.missingKeyError() != null) {
                pluginContext.logger.warn(
                    "AI Plugin enabled without an API key for ${profile.baseUrl} — " +
                        "services will report a configuration error until a key is set."
                )
            }
        }
        buildServices()
        pluginContext.logger.info("AI Plugin enabled with ${activeServices.size} services")
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: AISettings): Result<Unit, ServiceError> {
        // Trim keys in memory before validating/writing; validation runs before
        // ANY write so a failure never mutates stored settings or secrets.
        val normalized = settings.copy(
            apiKey = settings.apiKey.trim(),
            translationApiKey = settings.translationApiKey.trim(),
            rewriteApiKey = settings.rewriteApiKey.trim(),
            summarizationApiKey = settings.summarizationApiKey.trim(),
            spellCheckApiKey = settings.spellCheckApiKey.trim(),
            dictionaryApiKey = settings.dictionaryApiKey.trim(),
            visionApiKey = settings.visionApiKey.trim()
        )

        val error = validateSettings(normalized)
        if (error != null) return Err(error)

        // Persist non-secret fields to settings; keys only to the secret store.
        pluginContext.settings.put(KEY_PRESET, normalized.preset)
        pluginContext.settings.put(KEY_BASE_URL, normalized.baseUrl)
        pluginContext.settings.put(KEY_MODEL, normalized.model)
        pluginContext.settings.put(KEY_TEMPERATURE, normalized.temperature)
        pluginContext.settings.put(KEY_MAX_TOKENS, normalized.maxTokens)
        pluginContext.settings.put(KEY_CUSTOM_HEADERS, normalized.customHeaders)

        pluginContext.settings.put(KEY_TRANSLATION_USE_DEFAULT, normalized.translationUseDefault)
        pluginContext.settings.put(KEY_TRANSLATION_PRESET, normalized.translationPreset)
        pluginContext.settings.put(KEY_TRANSLATION_BASE_URL, normalized.translationBaseUrl)
        pluginContext.settings.put(KEY_TRANSLATION_MODEL, normalized.translationModel)
        pluginContext.settings.put(KEY_TRANSLATION_CUSTOM_HEADERS, normalized.translationCustomHeaders)
        pluginContext.settings.put(KEY_TRANSLATION_TEMPERATURE, normalized.translationTemperature)
        pluginContext.settings.put(KEY_TRANSLATION_MAX_TOKENS, normalized.translationMaxTokens)

        pluginContext.settings.put(KEY_REWRITE_USE_DEFAULT, normalized.rewriteUseDefault)
        pluginContext.settings.put(KEY_REWRITE_PRESET, normalized.rewritePreset)
        pluginContext.settings.put(KEY_REWRITE_BASE_URL, normalized.rewriteBaseUrl)
        pluginContext.settings.put(KEY_REWRITE_MODEL, normalized.rewriteModel)
        pluginContext.settings.put(KEY_REWRITE_CUSTOM_HEADERS, normalized.rewriteCustomHeaders)
        pluginContext.settings.put(KEY_REWRITE_TEMPERATURE, normalized.rewriteTemperature)
        pluginContext.settings.put(KEY_REWRITE_MAX_TOKENS, normalized.rewriteMaxTokens)

        pluginContext.settings.put(KEY_SUMMARIZATION_USE_DEFAULT, normalized.summarizationUseDefault)
        pluginContext.settings.put(KEY_SUMMARIZATION_PRESET, normalized.summarizationPreset)
        pluginContext.settings.put(KEY_SUMMARIZATION_BASE_URL, normalized.summarizationBaseUrl)
        pluginContext.settings.put(KEY_SUMMARIZATION_MODEL, normalized.summarizationModel)
        pluginContext.settings.put(KEY_SUMMARIZATION_CUSTOM_HEADERS, normalized.summarizationCustomHeaders)
        pluginContext.settings.put(KEY_SUMMARIZATION_TEMPERATURE, normalized.summarizationTemperature)
        pluginContext.settings.put(KEY_SUMMARIZATION_MAX_TOKENS, normalized.summarizationMaxTokens)

        pluginContext.settings.put(KEY_SPELL_CHECK_USE_DEFAULT, normalized.spellCheckUseDefault)
        pluginContext.settings.put(KEY_SPELL_CHECK_PRESET, normalized.spellCheckPreset)
        pluginContext.settings.put(KEY_SPELL_CHECK_BASE_URL, normalized.spellCheckBaseUrl)
        pluginContext.settings.put(KEY_SPELL_CHECK_MODEL, normalized.spellCheckModel)
        pluginContext.settings.put(KEY_SPELL_CHECK_CUSTOM_HEADERS, normalized.spellCheckCustomHeaders)
        pluginContext.settings.put(KEY_SPELL_CHECK_TEMPERATURE, normalized.spellCheckTemperature)
        pluginContext.settings.put(KEY_SPELL_CHECK_MAX_TOKENS, normalized.spellCheckMaxTokens)

        pluginContext.settings.put(KEY_DICTIONARY_USE_DEFAULT, normalized.dictionaryUseDefault)
        pluginContext.settings.put(KEY_DICTIONARY_PRESET, normalized.dictionaryPreset)
        pluginContext.settings.put(KEY_DICTIONARY_BASE_URL, normalized.dictionaryBaseUrl)
        pluginContext.settings.put(KEY_DICTIONARY_MODEL, normalized.dictionaryModel)
        pluginContext.settings.put(KEY_DICTIONARY_CUSTOM_HEADERS, normalized.dictionaryCustomHeaders)
        pluginContext.settings.put(KEY_DICTIONARY_TEMPERATURE, normalized.dictionaryTemperature)
        pluginContext.settings.put(KEY_DICTIONARY_MAX_TOKENS, normalized.dictionaryMaxTokens)

        pluginContext.settings.put(KEY_VISION_USE_DEFAULT, normalized.visionUseDefault)
        pluginContext.settings.put(KEY_VISION_PRESET, normalized.visionPreset)
        pluginContext.settings.put(KEY_VISION_BASE_URL, normalized.visionBaseUrl)
        pluginContext.settings.put(KEY_VISION_MODEL, normalized.visionModel)
        pluginContext.settings.put(KEY_VISION_CUSTOM_HEADERS, normalized.visionCustomHeaders)
        pluginContext.settings.put(KEY_VISION_TEMPERATURE, normalized.visionTemperature)
        pluginContext.settings.put(KEY_VISION_MAX_TOKENS, normalized.visionMaxTokens)

        putSecret(KEY_API_KEY, normalized.apiKey)
        putSecret(KEY_TRANSLATION_API_KEY, normalized.translationApiKey)
        putSecret(KEY_REWRITE_API_KEY, normalized.rewriteApiKey)
        putSecret(KEY_SUMMARIZATION_API_KEY, normalized.summarizationApiKey)
        putSecret(KEY_SPELL_CHECK_API_KEY, normalized.spellCheckApiKey)
        putSecret(KEY_DICTIONARY_API_KEY, normalized.dictionaryApiKey)
        putSecret(KEY_VISION_API_KEY, normalized.visionApiKey)

        this.settings = normalized

        pluginContext.logger.info(
            "AI Plugin settings updated [baseUrl=${normalized.baseUrl}, model=${normalized.model}]"
        )
        return Ok(Unit)
    }

    private suspend fun putSecret(key: String, value: String) {
        // Mirror GoogleLensPlugin / LibreTranslatePlugin: blank removes, else
        // stores the trimmed value. Keys are never written to settings.
        if (value.isBlank()) {
            pluginContext.secrets.remove(key)
        } else {
            pluginContext.secrets.put(key, value)
        }
    }

    override suspend fun onDisable() {
        pluginContext.logger.info("AI Plugin disabled")
        activeServices = emptyList()
    }

    override suspend fun shutdown() {
        pluginContext.logger.info("AI Plugin shutting down")
    }

    override fun getServices(): List<Service> = activeServices

    override fun getSettings(): AISettings = settings

    /**
     * Validates every distinct resolved profile once, in capability order,
     * returning the first failure without persisting anything.
     *
     * Profiles are deduplicated by [AiProfile.identityKey], so a configuration
     * where every capability inherits the Default costs exactly one request.
     */
    suspend fun validateAllDistinctProfiles(client: AIServiceClient): Result<Unit, ServiceError> {
        for (profile in distinctProfiles()) {
            client.validate(profile).fold(
                success = { },
                failure = { return Err(it) }
            )
        }
        return Ok(Unit)
    }

    private fun distinctProfiles(): List<AiProfile> =
        AiCapability.entries
            .map { settings.profileFor(it) }
            .distinctBy { it.identityKey }

    private fun buildServices() {
        activeServices = listOf(
            AITranslatorService(client = serviceClient) { settings.profileFor(AiCapability.TRANSLATION) },
            AISummarizerService(client = serviceClient) { settings.profileFor(AiCapability.SUMMARIZATION) },
            AIRewriterService(client = serviceClient) { settings.profileFor(AiCapability.REWRITE) },
            AISpellCheckerService(client = serviceClient) { settings.profileFor(AiCapability.SPELL_CHECK) },
            AIDictionaryService(client = serviceClient) { settings.profileFor(AiCapability.DICTIONARY) },
            AIVisionOcrService(client = serviceClient) { settings.profileFor(AiCapability.VISION) }
        )
    }

    private fun validateSettings(settings: AISettings): ServiceError.ValidationError? {
        // Default profile checks (pre-existing).
        when {
            settings.baseUrl.isBlank() ->
                return ServiceError.ValidationError("Base URL must not be empty.")
            settings.model.isBlank() ->
                return ServiceError.ValidationError("Model name must not be empty.")
            settings.temperature !in 0.0..2.0 ->
                return ServiceError.ValidationError("Temperature must be between 0.0 and 2.0.")
            settings.maxTokens < 1 ->
                return ServiceError.ValidationError("Max Tokens must be at least 1.")
        }

        // Per-capability checks for independent profiles: validate the resolved
        // profile so inherited fields are checked against the Default, and
        // sentinel values (-1 temperature / max tokens) resolve to the Default.
        val capabilities = listOf(
            Triple(AiCapability.TRANSLATION, settings.translationUseDefault, "Translation"),
            Triple(AiCapability.REWRITE, settings.rewriteUseDefault, "Rewrite"),
            Triple(AiCapability.SUMMARIZATION, settings.summarizationUseDefault, "Summarization"),
            Triple(AiCapability.SPELL_CHECK, settings.spellCheckUseDefault, "Spell Check"),
            Triple(AiCapability.DICTIONARY, settings.dictionaryUseDefault, "Dictionary"),
            Triple(AiCapability.VISION, settings.visionUseDefault, "Vision")
        )
        for ((capability, useDefault, label) in capabilities) {
            if (useDefault) continue
            val resolved = settings.profileFor(capability)
            when {
                resolved.baseUrl.isBlank() ->
                    return ServiceError.ValidationError("$label Base URL must not be empty.")
                resolved.model.isBlank() ->
                    return ServiceError.ValidationError("$label Model name must not be empty.")
                resolved.temperature !in 0.0..2.0 ->
                    return ServiceError.ValidationError("$label Temperature must be between 0.0 and 2.0.")
                resolved.maxTokens < 1 ->
                    return ServiceError.ValidationError("$label Max Tokens must be at least 1.")
                !isCustomHeadersValid(resolved.customHeaders) ->
                    return ServiceError.ValidationError("$label Custom Headers must be a JSON object with string values.")
            }
        }
        return null
    }

    private companion object {
        const val KEY_PRESET = "preset"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_API_KEY = "apiKey"
        const val KEY_MODEL = "model"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "maxTokens"
        const val KEY_CUSTOM_HEADERS = "customHeaders"

        const val KEY_TRANSLATION_USE_DEFAULT = "translationUseDefault"
        const val KEY_TRANSLATION_PRESET = "translationPreset"
        const val KEY_TRANSLATION_BASE_URL = "translationBaseUrl"
        const val KEY_TRANSLATION_API_KEY = "translationApiKey"
        const val KEY_TRANSLATION_MODEL = "translationModel"
        const val KEY_TRANSLATION_CUSTOM_HEADERS = "translationCustomHeaders"
        const val KEY_TRANSLATION_TEMPERATURE = "translationTemperature"
        const val KEY_TRANSLATION_MAX_TOKENS = "translationMaxTokens"

        const val KEY_REWRITE_USE_DEFAULT = "rewriteUseDefault"
        const val KEY_REWRITE_PRESET = "rewritePreset"
        const val KEY_REWRITE_BASE_URL = "rewriteBaseUrl"
        const val KEY_REWRITE_API_KEY = "rewriteApiKey"
        const val KEY_REWRITE_MODEL = "rewriteModel"
        const val KEY_REWRITE_CUSTOM_HEADERS = "rewriteCustomHeaders"
        const val KEY_REWRITE_TEMPERATURE = "rewriteTemperature"
        const val KEY_REWRITE_MAX_TOKENS = "rewriteMaxTokens"

        const val KEY_SUMMARIZATION_USE_DEFAULT = "summarizationUseDefault"
        const val KEY_SUMMARIZATION_PRESET = "summarizationPreset"
        const val KEY_SUMMARIZATION_BASE_URL = "summarizationBaseUrl"
        const val KEY_SUMMARIZATION_API_KEY = "summarizationApiKey"
        const val KEY_SUMMARIZATION_MODEL = "summarizationModel"
        const val KEY_SUMMARIZATION_CUSTOM_HEADERS = "summarizationCustomHeaders"
        const val KEY_SUMMARIZATION_TEMPERATURE = "summarizationTemperature"
        const val KEY_SUMMARIZATION_MAX_TOKENS = "summarizationMaxTokens"

        const val KEY_SPELL_CHECK_USE_DEFAULT = "spellCheckUseDefault"
        const val KEY_SPELL_CHECK_PRESET = "spellCheckPreset"
        const val KEY_SPELL_CHECK_BASE_URL = "spellCheckBaseUrl"
        const val KEY_SPELL_CHECK_API_KEY = "spellCheckApiKey"
        const val KEY_SPELL_CHECK_MODEL = "spellCheckModel"
        const val KEY_SPELL_CHECK_CUSTOM_HEADERS = "spellCheckCustomHeaders"
        const val KEY_SPELL_CHECK_TEMPERATURE = "spellCheckTemperature"
        const val KEY_SPELL_CHECK_MAX_TOKENS = "spellCheckMaxTokens"

        const val KEY_DICTIONARY_USE_DEFAULT = "dictionaryUseDefault"
        const val KEY_DICTIONARY_PRESET = "dictionaryPreset"
        const val KEY_DICTIONARY_BASE_URL = "dictionaryBaseUrl"
        const val KEY_DICTIONARY_API_KEY = "dictionaryApiKey"
        const val KEY_DICTIONARY_MODEL = "dictionaryModel"
        const val KEY_DICTIONARY_CUSTOM_HEADERS = "dictionaryCustomHeaders"
        const val KEY_DICTIONARY_TEMPERATURE = "dictionaryTemperature"
        const val KEY_DICTIONARY_MAX_TOKENS = "dictionaryMaxTokens"

        const val KEY_VISION_USE_DEFAULT = "visionUseDefault"
        const val KEY_VISION_PRESET = "visionPreset"
        const val KEY_VISION_BASE_URL = "visionBaseUrl"
        const val KEY_VISION_API_KEY = "visionApiKey"
        const val KEY_VISION_MODEL = "visionModel"
        const val KEY_VISION_CUSTOM_HEADERS = "visionCustomHeaders"
        const val KEY_VISION_TEMPERATURE = "visionTemperature"
        const val KEY_VISION_MAX_TOKENS = "visionMaxTokens"
    }
}
