package com.github.ahatem.qtranslate.plugins.google


import com.github.ahatem.qtranslate.api.*
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class GooglePlugin : Plugin {
    // Core dependencies, initialized once and live for the application's lifetime.
    private lateinit var pluginContext: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private lateinit var settings: GoogleSettings

    // Stateless, shared helpers for services.
    private val languageMapper = GoogleLanguageMapper
    private val apiConfig = ApiConfig()

    // Holds service instances only when the plugin is active.
    private var activeServices: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.pluginContext = context

        settings = GoogleSettings().apply {
            translateApiKey = context.getSecret("translateApiKey") ?: ""
            visionApiKey = context.getSecret("visionApiKey") ?: ""
        }

        // The HTTP client is expensive to create, so it's initialized only once.
        httpClient = KtorHttpClient(pluginContext)

        pluginContext.logger.info("Google Plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable() {
        pluginContext.logger.info("Enabling Google services")
        activeServices = buildList {
            add(GoogleTranslatorService(pluginContext, settings, httpClient, languageMapper, apiConfig))
            add(GoogleTTSService(pluginContext, httpClient, languageMapper, apiConfig))
            add(GoogleDictionaryService(pluginContext, httpClient, languageMapper, apiConfig))
            add(GoogleOCRService(pluginContext, settings, httpClient, languageMapper, apiConfig))
            add(GoogleSpellCheckerService(pluginContext, httpClient, languageMapper, apiConfig))
        }
    }

    override suspend fun onDisable() {
        pluginContext.logger.info("Disabling Google services")
        activeServices = emptyList()
        // The httpClient is intentionally kept alive in case the plugin is re-enabled.
    }

    override suspend fun shutdown() {
        pluginContext.logger.info("Google Plugin shutting down")
        httpClient.close()
    }

    override fun getServices(): List<Service> {
        return activeServices
    }

    override fun getSettingsClass(): Class<*> {
        return GoogleSettings::class.java
    }
}

class GoogleSettings {
    @Setting(
        label = "Vision API Key (for OCR)",
        description = "Your personal Google Cloud Vision API key. This is required for text recognition from images (OCR).",
        type = SettingType.PASSWORD,
        isRequired = true,
        order = 1
    )
    var visionApiKey: String = ""

    @Setting(
        label = "Translation API Key (Optional)",
        description = "An optional Google Cloud Translation API key. If provided, it may grant higher usage quotas.",
        type = SettingType.PASSWORD,
        order = 2
    )
    var translateApiKey: String = ""
}