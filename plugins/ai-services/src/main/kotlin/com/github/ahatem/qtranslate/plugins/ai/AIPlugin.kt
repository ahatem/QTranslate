package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * Entry point for the AI Plugin.
 *
 * Provides six AI-powered services backed by a single OpenAI-compatible chat
 * completions client ([AIServiceClient]):
 * - **AI Translate** — translates text between any language pair
 * - **AI Summarizer** — condenses text to short / medium / long summaries
 * - **AI Rewriter** — rewrites text in formal / casual / concise / detailed / simplified style
 * - **AI Spell Checker** — detects and highlights spelling, grammar, and punctuation errors
 * - **AI Dictionary** — full definitions, part-of-speech, phonetics, synonyms for any word
 * - **AI Vision OCR** — extracts text from images using vision-capable models
 *
 * All four services share one [AIServiceClient] instance that reads [AISettings]
 * via a lambda, so changes in Settings → Plugins take effect on the next API
 * call without any service rebuild.
 *
 * ### Endpoint
 * Defaults to **OpenRouter** (`https://openrouter.ai/api/v1`), a unified
 * OpenAI-compatible gateway with access to 300+ models from a single API key.
 * Users can override [AISettings.baseUrl] to point at any OpenAI-compatible server.
 *
 * ### Upgrading from an older version
 * Previous versions stored a `provider` key (Gemini / OpenAI / Mistral / Anthropic).
 * That key is no longer read. API keys (`apiKey`) and model names (`model`) carry
 * forward. Users who relied on the Gemini or Anthropic native endpoints should
 * either switch to OpenRouter or update the Base URL in plugin settings.
 */
class AIPlugin : Plugin<AISettings> {

    private lateinit var pluginContext: PluginContext
    private val httpClient: HttpClient get() = pluginContext.http
    private lateinit var serviceClient: AIServiceClient

    private var settings: AISettings = AISettings()
    private var activeServices: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        pluginContext = context

        settings = AISettings(
            baseUrl       = context.settings.getString(KEY_BASE_URL) ?: AISettings().baseUrl,
            apiKey        = context.secrets.get(KEY_API_KEY) ?: "",
            model         = context.settings.getString(KEY_MODEL) ?: AISettings().model,
            temperature   = context.settings.getDouble(KEY_TEMPERATURE, 0.3),
            maxTokens     = context.settings.getInt(KEY_MAX_TOKENS, 4096),
            customHeaders = context.settings.getString(KEY_CUSTOM_HEADERS) ?: AISettings().customHeaders
        )

        serviceClient = AIServiceClient(
            pluginContext = context,
            httpClient    = httpClient,
            settings      = { this.settings }
        )

        pluginContext.logger.info(
            "AI Plugin initialized [baseUrl=${settings.baseUrl}, model=${settings.model}]"
        )
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        // Only a warning when a key is actually needed. A local endpoint runs perfectly well
        // without one, and warning there would send someone hunting for a problem that is not
        // there.
        if (settings.missingKeyError() != null) {
            pluginContext.logger.warn(
                "AI Plugin enabled without an API key for ${settings.baseUrl} — " +
                    "services will report a configuration error until a key is set."
            )
        }
        buildServices()
        pluginContext.logger.info("AI Plugin enabled with ${activeServices.size} services")
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: AISettings): Result<Unit, ServiceError> {
        val error = validateSettings(settings)
        if (error != null) return Err(error)

        pluginContext.settings.put(KEY_BASE_URL, settings.baseUrl)
        pluginContext.secrets.put(KEY_API_KEY, settings.apiKey)
        pluginContext.settings.put(KEY_MODEL, settings.model)
        pluginContext.settings.put(KEY_TEMPERATURE, settings.temperature)
        pluginContext.settings.put(KEY_MAX_TOKENS, settings.maxTokens)
        pluginContext.settings.put(KEY_CUSTOM_HEADERS, settings.customHeaders)

        this.settings = settings

        pluginContext.logger.info(
            "AI Plugin settings updated [baseUrl=${settings.baseUrl}, model=${settings.model}]"
        )
        return Ok(Unit)
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

    private fun buildServices() {
        activeServices = listOf(
            AITranslatorService(client = serviceClient),
            AISummarizerService(client = serviceClient),
            AIRewriterService(client = serviceClient),
            AISpellCheckerService(client = serviceClient),
            AIDictionaryService(client = serviceClient),
            AIVisionOcrService(client = serviceClient)
        )
    }

    private fun validateSettings(settings: AISettings): ServiceError.ValidationError? = when {
        settings.baseUrl.isBlank() ->
            ServiceError.ValidationError("Base URL must not be empty.")
        settings.model.isBlank() ->
            ServiceError.ValidationError("Model name must not be empty.")
        settings.temperature !in 0.0..2.0 ->
            ServiceError.ValidationError("Temperature must be between 0.0 and 2.0.")
        settings.maxTokens < 1 ->
            ServiceError.ValidationError("Max Tokens must be at least 1.")
        else -> null
    }

    private companion object {
        const val KEY_BASE_URL       = "baseUrl"
        const val KEY_API_KEY        = "apiKey"
        const val KEY_MODEL          = "model"
        const val KEY_TEMPERATURE    = "temperature"
        const val KEY_MAX_TOKENS     = "maxTokens"
        const val KEY_CUSTOM_HEADERS = "customHeaders"
    }
}
