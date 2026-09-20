package com.github.ahatem.qtranslate.plugins.systemocr

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.backend.SystemOcrBackends
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold

/**
 * Provides offline OCR by wrapping the operating system's own recognition engine.
 *
 * There is nothing to configure, so it declares [PluginSettings.None] and offers a single OCR
 * service. Which engine is used follows the platform.
 */
class SystemOcrPlugin : Plugin<PluginSettings.None> {

    private lateinit var context: PluginContext
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        context.logger.info("System OCR plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> =
        SystemOcrBackends.create(context).fold(
            success = { backend ->
                services = listOf(SystemOcrService(backend, context.logger))
                context.logger.info("System OCR enabled using ${backend.displayName}")
                Ok(Unit)
            },
            failure = { error ->
                services = emptyList()
                context.logger.warn("System OCR is unavailable: ${error.message}")
                Err(error)
            },
        )

    override suspend fun onDisable() {
        services = emptyList()
        context.logger.info("System OCR plugin disabled")
    }

    override suspend fun shutdown() {
        services = emptyList()
    }

    override fun getServices(): List<Service> = services

    override fun getSettings(): PluginSettings.None = PluginSettings.None
}
