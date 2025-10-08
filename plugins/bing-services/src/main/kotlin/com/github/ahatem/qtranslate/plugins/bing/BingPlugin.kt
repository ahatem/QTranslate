package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.plugin.NoSettings
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * A plugin that provides translation, spell-checking, and TTS services
 * using the Microsoft Bing APIs. This plugin does not have any user-configurable settings.
 */
class BingPlugin : Plugin<NoSettings> {
    // Core dependencies, initialized once.
    private lateinit var pluginContext: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private lateinit var authManager: BingAuthManager

    // Holds service instances only when the plugin is active.
    private var activeServices: List<Service> = emptyList()

    // Stateless, shared helpers.
    private val languageMapper = BingLanguageMapper
    private val apiConfig = ApiConfig()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.pluginContext = context
        this.httpClient = KtorHttpClient(context)
        this.authManager = BingAuthManager(context, httpClient)

        pluginContext.logger.info("Bing Plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        pluginContext.logger.info("Enabling Bing services")
        activeServices = listOf(
            BingTranslatorService(pluginContext, httpClient, authManager, languageMapper, apiConfig),
            BingSpellCheckerService(pluginContext, httpClient, authManager, languageMapper, apiConfig),
            BingTTSService(pluginContext, httpClient, authManager, languageMapper, apiConfig)
        )
        // Since enabling these services is a simple in-memory operation, we can
        // safely return Ok. A more complex plugin might have I/O here that could fail.
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        pluginContext.logger.info("Disabling Bing services")
        activeServices = emptyList()
    }

    override suspend fun shutdown() {
        pluginContext.logger.info("Bing Plugin shutting down")
        httpClient.close()
    }

    override fun getServices(): List<Service> {
        return activeServices
    }

    override fun getSettingsClass(): Class<NoSettings> {
        // This plugin has no settings, so it returns the NoSettings marker class.
        return NoSettings::class.java
    }
}