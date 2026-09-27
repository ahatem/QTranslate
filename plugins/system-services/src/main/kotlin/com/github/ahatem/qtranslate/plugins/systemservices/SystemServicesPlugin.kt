package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.backend.SystemOcrBackend
import com.github.ahatem.qtranslate.plugins.systemservices.backend.SystemOcrBackends
import com.github.ahatem.qtranslate.plugins.systemservices.tts.SystemTtsBackend
import com.github.ahatem.qtranslate.plugins.systemservices.tts.SystemTtsBackends
import com.github.ahatem.qtranslate.plugins.systemservices.tts.VoiceLocaleMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException

class SystemServicesPlugin internal constructor(
    private val ocrDiscovery: suspend (PluginContext) -> Result<SystemOcrBackend, ServiceError>,
    private val ttsDiscovery: suspend (PluginContext) -> Result<SystemTtsBackend, ServiceError>,
) : Plugin<PluginSettings.None> {
    constructor() : this({ SystemOcrBackends.create(it) }, { SystemTtsBackends.create(it) })

    private lateinit var context: PluginContext
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        context.logger.info("System Services plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        services = emptyList()
        val found = mutableListOf<Service>()
        discover("OCR") {
            ocrDiscovery(context).fold(
                success = { backend ->
                    backend.validate().fold(
                        success = { found += SystemOcrService(backend, context.logger) },
                        failure = { context.logger.info("System OCR unavailable: " + it.message) },
                    )
                },
                failure = { context.logger.info("System OCR unavailable: " + it.message) },
            )
        }
        discover("TTS") {
            ttsDiscovery(context).fold(
                success = { backend ->
                    backend.discoverVoices().fold(
                        success = { voices ->
                            val usable = voices.filter { VoiceLocaleMapper.codes(it.locale).isNotEmpty() }
                            if (usable.isEmpty()) context.logger.info("System TTS unavailable: no usable voices")
                            else found += SystemTtsService(backend, usable, context.logger)
                        },
                        failure = { context.logger.info("System TTS unavailable: " + it.message) },
                    )
                },
                failure = { context.logger.info("System TTS unavailable: " + it.message) },
            )
        }
        services = found.distinctBy { it.key }
        context.logger.info("System Services enabled with " + services.size + " service(s)")
        return Ok(Unit)
    }

    private suspend fun discover(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            context.logger.warn("System " + name + " discovery failed: " + exception.javaClass.simpleName)
        }
    }

    override suspend fun onDisable() {
        services = emptyList()
        context.logger.info("System Services plugin disabled")
    }

    override suspend fun shutdown() {
        services = emptyList()
    }

    override fun getServices(): List<Service> = services
    override fun getSettings(): PluginSettings.None = PluginSettings.None
}
