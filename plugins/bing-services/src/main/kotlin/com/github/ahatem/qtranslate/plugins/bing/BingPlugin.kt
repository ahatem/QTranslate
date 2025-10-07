package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.Logger
import com.github.ahatem.qtranslate.api.Plugin
import com.github.ahatem.qtranslate.api.PluginContext
import com.github.ahatem.qtranslate.api.Service
import com.github.ahatem.qtranslate.api.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class BingPlugin : Plugin {
    // Core dependencies, initialized once and live for the application's lifetime.
    private lateinit var pluginContext: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private lateinit var authManager: BingAuthManager

    // Stateless, shared helpers for services.
    private val languageMapper = BingLanguageMapper
    private val apiConfig = ApiConfig()

    // Holds service instances only when the plugin is active.
    private var activeServices: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.pluginContext = context
        this.httpClient = KtorHttpClient(context)
        this.authManager = BingAuthManager(context, httpClient)

        pluginContext.logger.info("Bing Plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable() {
        pluginContext.logger.info("Enabling Bing services")
        activeServices = listOf(
            BingTranslatorService(pluginContext, httpClient, authManager, languageMapper, apiConfig),
            BingSpellCheckerService(pluginContext, httpClient, authManager, languageMapper, apiConfig),
            BingTTSService(pluginContext, httpClient, authManager, languageMapper, apiConfig)
        )
    }

    override suspend fun onDisable() {
        pluginContext.logger.info("Disabling Bing services")
        activeServices = emptyList()
        // The httpClient is intentionally kept alive in case the plugin is re-enabled.
    }

    override suspend fun shutdown() {
        pluginContext.logger.info("Bing Plugin shutting down")
        httpClient.close()
    }

    override fun getServices(): List<Service> {
        return activeServices
    }

    override fun getSettingsClass(): Class<*>? {
        return null // This plugin has no configurable settings.
    }
}