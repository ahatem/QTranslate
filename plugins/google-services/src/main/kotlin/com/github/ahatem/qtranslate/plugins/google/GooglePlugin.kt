package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class GooglePlugin : Plugin<GoogleSettings> {

    private lateinit var pluginContext: PluginContext
    private lateinit var httpClient: KtorHttpClient

    private lateinit var settings: GoogleSettings
    private var activeServices: List<Service> = emptyList()

    private val languageMapper = GoogleLanguageMapper
    private val apiConfig = ApiConfig()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.pluginContext = context
        // Restore persisted keys on first load so the user doesn't have to re-enter them.
        this.settings = GoogleSettings(
            visionApiKey = context.getValue("visionApiKey") ?: "",
            translateApiKey = context.getValue("translateApiKey") ?: ""
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
        // Persist both keys so they survive app restarts.
        pluginContext.storeValue("visionApiKey", settings.visionApiKey)
        pluginContext.storeValue("translateApiKey", settings.translateApiKey)
        this.settings = settings
        // Rebuild services — OCR is conditionally included based on the vision key.
        buildServices()
        return Ok(Unit)
    }

    private fun buildServices() {
        activeServices = buildList {
            // OCR requires a Vision API key — only register the service when one is configured.
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

    override fun getServices(): List<Service> = activeServices

    override fun getSettings(): GoogleSettings = settings
}

/**
 * User-configurable settings for the Google Plugin.
 * Extends [PluginSettings.Configurable] so the core can reflect on the @Setting
 * annotations and auto-generate the settings UI — no Swing code needed here.
 *
 * Both keys are optional at the data-class level. The Vision key is required only
 * when the user wants OCR; its absence simply excludes that service from the registry.
 */
data class GoogleSettings(
    @field:Setting(
        label = "Vision API Key (for OCR)",
        description = "Your Google Cloud Vision API key. Required for image text recognition (OCR). Leave blank to disable OCR.",
        type = SettingType.PASSWORD,
        order = 10
    )
    var visionApiKey: String = "",

    @field:Setting(
        label = "Translation API Key (optional)",
        description = "An optional Google Cloud Translation API key. If provided, it grants higher usage quotas than the free endpoint.",
        type = SettingType.PASSWORD,
        order = 20
    )
    var translateApiKey: String = ""
) : PluginSettings.Configurable()