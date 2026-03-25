package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * The main entry point for the AI Plugin.
 *
 * Provides four AI-powered text services backed by a single OpenAI-compatible
 * chat completions client:
 *
 * All services share a single [AIServiceClient] instance which reads [AISettings]
 * via a lambda, meaning settings changes in [onSettingsChanged] are reflected on
 * the very next API call — no service rebuild required for settings-only changes.
 * [buildServices] is still called on enable and settings change to update the
 * displayed service names (which embed the provider name).
 */
class AIPlugin : Plugin<AISettings> {

    private lateinit var pluginContext: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private lateinit var serviceClient: AIServiceClient

    private var settings: AISettings = AISettings()
    private var activeServices: List<Service> = emptyList()


    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.pluginContext = context

        this.settings = AISettings(
            provider = context.getValue(KEY_PROVIDER) ?: "Gemini",
            model = context.getValue(KEY_MODEL) ?: "gemini-flash-lite-latest",
            apiKey = context.getValue(KEY_API_KEY) ?: "",
            temperature = context.getValue(KEY_TEMPERATURE)?.toDoubleOrNull() ?: 0.3
        )

        this.httpClient = KtorHttpClient(context)

        // The lambda captures `this.settings` by reference so the client
        // always reads the current settings object after onSettingsChanged.
        this.serviceClient = AIServiceClient(
            pluginContext = context,
            httpClient = httpClient,
            settings = { this.settings }
        )

        pluginContext.logger.info("AI Plugin initialized [provider=${settings.provider}, model=${settings.model}]")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        if (settings.apiKey.isBlank()) {
            pluginContext.logger.warn("AI Plugin enabled without an API key — services will return AuthenticationError until a key is configured.")
        }
        buildServices()
        pluginContext.logger.info("AI Plugin enabled with ${activeServices.size} services [provider=${settings.provider}]")
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: AISettings): Result<Unit, ServiceError> {
        val validationError = validateSettings(settings)
        if (validationError != null) return Err(validationError)

        pluginContext.storeValue(KEY_PROVIDER, settings.provider)
        pluginContext.storeValue(KEY_MODEL, settings.model)
        pluginContext.storeValue(KEY_API_KEY, settings.apiKey)
        pluginContext.storeValue(KEY_TEMPERATURE, settings.temperature.toString())

        this.settings = settings

        // Rebuild the service list so the displayed service names update to
        // reflect the newly selected provider (e.g. "Gemini Translate" → "OpenAI Translate").
        buildServices()

        pluginContext.logger.info("AI Plugin settings updated [provider=${settings.provider}, model=${settings.model}]")
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        pluginContext.logger.info("AI Plugin disabled")
        activeServices = emptyList()
    }

    override suspend fun shutdown() {
        pluginContext.logger.info("AI Plugin shutting down")
        httpClient.close()
    }

    override fun getServices(): List<Service> = activeServices

    override fun getSettings(): AISettings = settings

    /**
     * Constructs the active service list. Each service receives the shared
     * [serviceClient] and a lambda that reads the live [settings], so all four
     * services always use the latest provider/model/key/temperature without
     * needing to be individually recreated.
     */
    private fun buildServices() {
        activeServices = listOf(
            AITranslatorService(client = serviceClient, settings = { this.settings }),
            AISummarizerService(client = serviceClient, settings = { this.settings }),
            AIRewriterService(client = serviceClient, settings = { this.settings }),
            AISpellCheckerService(client = serviceClient, settings = { this.settings })
        )
    }

    private fun validateSettings(settings: AISettings): ServiceError.ValidationError? {
        if (settings.model.isBlank()) {
            return ServiceError.ValidationError("Model name must not be empty.")
        }
        if (settings.temperature !in 0.0..2.0) {
            return ServiceError.ValidationError("Temperature must be between 0.0 and 2.0.")
        }
        return null
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "apiKey"
        const val KEY_TEMPERATURE = "temperature"
    }
}
