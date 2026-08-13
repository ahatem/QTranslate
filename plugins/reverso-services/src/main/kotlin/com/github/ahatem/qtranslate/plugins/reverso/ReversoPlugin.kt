package com.github.ahatem.qtranslate.plugins.reverso

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class ReversoPlugin : Plugin<PluginSettings.None> {
    private lateinit var context: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        httpClient = KtorHttpClient(context)
        context.logger.info("Reverso plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        val client = ReversoClient(context, httpClient, ApiConfig())
        services = listOf(
            ReversoTranslatorService(client),
            ReversoDictionaryService(client)
        )
        context.logger.info("Reverso Translation and Reverso Dictionary enabled")
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        services = emptyList()
    }

    override suspend fun shutdown() {
        httpClient.close()
    }

    override fun getServices(): List<Service> = services
    override fun getSettings(): PluginSettings.None = PluginSettings.None
}
