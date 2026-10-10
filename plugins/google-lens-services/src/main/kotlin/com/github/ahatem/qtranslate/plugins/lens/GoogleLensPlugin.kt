package com.github.ahatem.qtranslate.plugins.lens

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class GoogleLensPlugin : Plugin<PluginSettings.None> {

    private lateinit var pluginContext: PluginContext
    private var activeServices: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.pluginContext = context
        pluginContext.logger.info("Google Lens Plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        pluginContext.logger.info("Enabling Google Lens OCR service")
        activeServices = listOf(GoogleLensOCRService(pluginContext))
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        pluginContext.logger.info("Disabling Google Lens OCR service")
        activeServices = emptyList()
    }

    override suspend fun shutdown() {
        pluginContext.logger.info("Google Lens Plugin shutting down")
    }

    override fun getServices(): List<Service> = activeServices

    override fun getSettings(): PluginSettings.None = PluginSettings.None
}
