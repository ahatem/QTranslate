package com.github.ahatem.qtranslate.plugins.google


import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class GooglePlugin : Plugin<GoogleSettings> {
    // Core dependencies, initialized once.
    private lateinit var pluginContext: PluginContext
    private lateinit var httpClient: KtorHttpClient

    // The plugin's current state.
    private lateinit var settings: GoogleSettings
    private var activeServices: List<Service> = emptyList()

    // Stateless, shared helpers.
    private val languageMapper = GoogleLanguageMapper
    private val apiConfig = ApiConfig()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.pluginContext = context

        // On first load, populate initial settings from secure storage.
        this.settings = GoogleSettings(
            visionApiKey = context.getSecret("visionApiKey") ?: "",
            translateApiKey = context.getSecret("translateApiKey") ?: ""
        )
        this.httpClient = KtorHttpClient(context)

        pluginContext.logger.info("Google Plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        pluginContext.logger.info("Enabling Google services")
        buildServices()
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: GoogleSettings): Result<Unit, ServiceError> {
        pluginContext.logger.info("Applying new Google settings...")

        // 1. Validate the incoming settings.
        if (settings.visionApiKey.isBlank()) {
            return Err(ServiceError.InvalidInputError("Google Vision API Key cannot be empty."))
        }
        // You could add more validation here (e.g., regex check the key format).

        // 2. Persist the secrets securely.
        pluginContext.storeSecret("visionApiKey", settings.visionApiKey)
        pluginContext.storeSecret("translateApiKey", settings.translateApiKey)

        // 3. Apply the new settings to the plugin's state.
        this.settings = settings

        // 4. React to the change by rebuilding the services with the new configuration.
        buildServices()

        return Ok(Unit)
    }

    /** A private helper to create service instances based on the current settings. */
    private fun buildServices() {
        activeServices = buildList {
            // Only provide the OCR service if its API key is configured.
            if (settings.visionApiKey.isNotBlank()) {
                add(GoogleOCRService(pluginContext, settings, httpClient, languageMapper, apiConfig))
            }
            add(GoogleTranslatorService(pluginContext, settings, httpClient, languageMapper, apiConfig))
            add(GoogleTTSService(pluginContext, httpClient, languageMapper, apiConfig))
            add(GoogleDictionaryService(pluginContext, httpClient, languageMapper, apiConfig))
            add(GoogleSpellCheckerService(pluginContext, httpClient, languageMapper, apiConfig))
        }
        pluginContext.logger.info("Built ${activeServices.size} active Google services.")
    }

    override suspend fun onDisable() {
        pluginContext.logger.info("Disabling Google services")
        activeServices = emptyList()
    }

    override suspend fun shutdown() {
        pluginContext.logger.info("Google Plugin shutting down")
        httpClient.close()
    }

    override fun getServices(): List<Service> {
        return activeServices
    }

    override fun getSettingsClass(): Class<GoogleSettings> {
        return GoogleSettings::class.java
    }
}

/**
 * Defines the user-configurable settings for the Google Plugin.
 * The core application will use the @Setting annotations to auto-generate a UI.
 */
data class GoogleSettings(
    @Setting(
        label = "Vision API Key (for OCR)",
        description = "Your personal Google Cloud Vision API key. This is required for text recognition from images (OCR).",
        type = SettingType.PASSWORD,
        isRequired = true,
        order = 1
    )
    var visionApiKey: String = "",

    @Setting(
        label = "Translation API Key (Optional)",
        description = "An optional Google Cloud Translation API key. If provided, it may grant higher usage quotas.",
        type = SettingType.PASSWORD,
        order = 2
    )
    var translateApiKey: String = ""
)