package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
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
class BingPlugin : Plugin<PluginSettings.None> {

    private lateinit var pluginContext: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private lateinit var authManager: BingAuthManager

    private var activeServices: List<Service> = emptyList()

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

    override fun getServices(): List<Service> = activeServices

    override fun getSettings(): PluginSettings.None = PluginSettings.None
}