package com.github.ahatem.qtranslate.plugins.mymemory

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class MyMemoryPlugin : Plugin<PluginSettings.None> {
    private lateinit var context: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        httpClient = KtorHttpClient(context)
        context.logger.info("MyMemory plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        services = listOf(MyMemoryTranslatorService(context, httpClient, ApiConfig()))
        context.logger.info("MyMemory free translation service enabled")
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
