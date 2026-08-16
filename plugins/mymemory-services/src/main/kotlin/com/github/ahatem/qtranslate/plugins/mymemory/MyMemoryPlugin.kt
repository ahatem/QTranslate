package com.github.ahatem.qtranslate.plugins.mymemory

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class MyMemoryPlugin : Plugin<PluginSettings.None> {
    private lateinit var context: PluginContext
    private val httpClient: HttpClient get() = context.http
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
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
    }

    override fun getServices(): List<Service> = services

    override fun getSettings(): PluginSettings.None = PluginSettings.None
}
