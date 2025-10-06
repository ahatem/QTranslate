package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.Plugin
import com.github.ahatem.qtranslate.api.PluginContext
import com.github.ahatem.qtranslate.api.Service
import com.github.ahatem.qtranslate.api.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class BingPlugin : Plugin {
    private lateinit var pluginContext: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private lateinit var authManager: BingAuthManager
    private lateinit var apiConfig: ApiConfig

    override fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        pluginContext = context
        apiConfig = ApiConfig()
        httpClient = KtorHttpClient(pluginContext)
        authManager = BingAuthManager(pluginContext, httpClient)

        pluginContext.logInfo("Bing Services Plugin initialized")
        return Ok(Unit)
    }

    override fun getServices(): List<Service> = listOf(
        BingTranslatorService(pluginContext, httpClient, authManager, BingLanguageMapper, apiConfig),
        BingSpellCheckerService(pluginContext, httpClient, authManager, BingLanguageMapper, apiConfig),
        BingTTSService(pluginContext, httpClient, authManager, BingLanguageMapper, apiConfig)
    )

    override fun getSettingsClass(): Class<*>? = null

    override fun shutdown() {
        pluginContext.logInfo("Bing Services Plugin shutting down")
        httpClient.close()
    }
}