package com.github.ahatem.qtranslate.core.plugin.lifecycle

import com.github.ahatem.qtranslate.core.plugin.LoadedPluginResult
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import com.github.ahatem.qtranslate.core.plugin.ScopedPluginContext
import com.github.ahatem.qtranslate.core.plugin.registry.PluginContainer
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceValidator
import com.github.ahatem.qtranslate.core.plugin.registry.PluginError
import com.github.ahatem.qtranslate.core.plugin.http.HttpClientConfig
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.text.PluginTextResolver
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Handles the coroutine-based lifecycle operations for individual plugins:
 * initialization, enabling, and disabling.
 *
 * This class owns all the timeout/error-wrapping logic around calls to
 * `Plugin.initialize()`, `Plugin.onEnable()`, and `Plugin.onDisable()`.
 * It does not touch the registry or the exposed StateFlows — that is
 * [com.github.ahatem.qtranslate.core.plugin.PluginManager]'s responsibility.
 */
internal class PluginLifecycleHandler(
    private val appDataDirectory: File,
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val notificationBus: NotificationBus,
    private val textResolver: PluginTextResolver,

    private val loggerFactory: LoggerFactory,
    /**
     * The network settings to build each plugin's client from, read at the moment a context
     * is created rather than captured once. A plugin installed or re-enabled after the user
     * changes their proxy then gets the new one without a restart; the ones already running
     * keep the client they were handed, which is what the settings page says happens.
     */
    private val httpConfig: () -> HttpClientConfig = { HttpClientConfig() }
) {
    private val logger = loggerFactory.getLogger("PluginLifecycleHandler")

    companion object {
        private const val INIT_TIMEOUT_MS = 30_000L
        private const val ENABLE_TIMEOUT_MS = 15_000L
        private const val DISABLE_TIMEOUT_MS = 15_000L
    }

    // -------------------------------------------------------------------------
    // Context factory
    // -------------------------------------------------------------------------

    /**
     * Creates a [ScopedPluginContext] for the given [LoadedPluginResult].
     * The context is created once and reused across enable/disable cycles;
     * only the internal scope is reset on each enable.
     */
    fun createContext(result: LoadedPluginResult): ScopedPluginContext {
        // Every container is built from a context, and this is the only place that has both the
        // plugin's id and the loader that can read the bundle out of its JAR.
        textResolver.onPluginLoaded(result.manifest.id, result.classLoader)

        return ScopedPluginContext(
            pluginId = result.manifest.id,
            appDataDirectory = appDataDirectory,
            pluginKeyValueStore = pluginKeyValueStore,
            notificationBus = notificationBus,
            textResolver = textResolver,
            logger = loggerFactory.getLogger(result.manifest.id),
            httpConfig = httpConfig()
        )
    }

    /** Drops a plugin's cached translations, for when it is uninstalled. */
    fun forgetLocalization(pluginId: String) = textResolver.onPluginRemoved(pluginId)

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    /**
     * Calls `Plugin.initialize(context)` with timeout protection.
     * Mutates [container.status] and [container.lastError] to reflect the outcome.
     *
     * @return `true` if initialization succeeded, `false` otherwise.
     */
    suspend fun initialize(container: PluginContainer): Boolean {
        val initResult = withTimeoutOrNull(INIT_TIMEOUT_MS) {
            runCatching { container.plugin.initialize(container.context) }
        }

        if (initResult == null) {
            val error = PluginError.InitializationFailure(
                pluginId = container.id,
                message = "Plugin initialization timed out after ${INIT_TIMEOUT_MS / 1000}s",
                cause = null
            )
            container.status = PluginStatus.FAILED
            container.lastError = error
            logger.error("Plugin '${container.id}' initialization timed out")
            return false
        }

        initResult.fold(
            onSuccess = { result ->
                result.fold(
                    success = {
                        logger.info("Initialized plugin '${container.manifest.name}' (${container.id})")
                        return true
                    },
                    failure = { error ->
                        val pluginError = PluginError.InitializationFailure(
                            pluginId = container.id,
                            message = error.message,
                            cause = error.cause
                        )
                        container.status = PluginStatus.FAILED
                        container.lastError = pluginError
                        logger.error("Plugin '${container.id}' failed to initialize: ${error.message}", error.cause)
                        return false
                    }
                )
            },
            onFailure = { e ->
                val pluginError = PluginError.InitializationFailure(
                    pluginId = container.id,
                    message = "Unexpected exception during initialization: ${e.message}",
                    cause = e
                )
                container.status = PluginStatus.FAILED
                container.lastError = pluginError
                logger.error("Unexpected exception initializing plugin '${container.id}'", e)
                return false
            }
        )
    }

    // -------------------------------------------------------------------------
    // Enable
    // -------------------------------------------------------------------------

    /**
     * Calls `Plugin.onEnable()` with timeout protection, then calls `getServices()`.
     * Resets the plugin's coroutine scope before enabling so a fresh scope is available.
     * Mutates [container] to reflect the outcome.
     */
    suspend fun enable(container: PluginContainer) {
        if (container.status == PluginStatus.FAILED) {
            logger.warn("Cannot enable plugin '${container.id}': it is in a FAILED state.")
            return
        }

        // Reset scope before enabling so the plugin gets a fresh scope each cycle.
        (container.context as? ScopedPluginContext)?.resetScope()

        val enableResult = withTimeoutOrNull(ENABLE_TIMEOUT_MS) {
            runCatching { container.plugin.onEnable() }
        }

        if (enableResult == null) {
            container.status = PluginStatus.FAILED
            container.lastError = PluginError.EnableFailure(
                pluginId = container.id,
                message = "Plugin enable timed out after ${ENABLE_TIMEOUT_MS / 1000}s",
                cause = null
            )
            logger.error("Plugin '${container.id}' enable timed out")
            return
        }

        enableResult.fold(
            onSuccess = { result ->
                result.fold(
                    success = {
                        val declared = container.plugin.getServices()

                        // A service with no key, or implementing no service interface, can never be
                        // reached by the user. Dropping it here keeps the plugin's other services
                        // usable and puts the reason in the log instead of leaving something
                        // registered that nothing can ever select.
                        val (valid, invalid) = declared.partition { ServiceValidator.validate(it).isEmpty() }
                        invalid.forEach { service ->
                            ServiceValidator.validate(service).forEach { problem ->
                                logger.error("Plugin '${container.id}': $problem — service not registered")
                            }
                        }

                        container.services = valid
                        container.declaredServices = valid
                        container.status = PluginStatus.ENABLED
                        container.lastError = invalid
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                PluginError.EnableFailure(
                                    pluginId = container.id,
                                    message = "${it.size} service(s) cannot be offered to the user",
                                    cause = null
                                )
                            }
                        logger.info(
                            "Plugin '${container.id}' enabled with ${container.services.size} service(s)."
                        )
                    },
                    failure = { error ->
                        container.status = PluginStatus.FAILED
                        container.lastError = PluginError.EnableFailure(
                            pluginId = container.id,
                            message = error.message,
                            cause = error.cause
                        )
                        logger.error("Plugin '${container.id}' failed to enable: ${error.message}", error.cause)
                    }
                )
            },
            onFailure = { e ->
                container.status = PluginStatus.FAILED
                container.lastError = PluginError.EnableFailure(
                    pluginId = container.id,
                    message = "Unexpected exception while enabling: ${e.message}",
                    cause = e
                )
                logger.error("Unexpected exception enabling plugin '${container.id}'", e)
            }
        )
    }

    // -------------------------------------------------------------------------
    // Disable
    // -------------------------------------------------------------------------

    /**
     * Calls `Plugin.onDisable()` with timeout protection, then cancels the plugin's
     * coroutine scope. Always sets the plugin to DISABLED regardless of timeout —
     * we force-disable rather than leaving the plugin in a stuck state.
     * Mutates [container] to reflect the outcome.
     */
    suspend fun disable(container: PluginContainer) {
        try {
            withTimeoutOrNull(DISABLE_TIMEOUT_MS) {
                runCatching { container.plugin.onDisable() }.onFailure { e ->
                    logger.error("Exception in onDisable() for plugin '${container.id}'", e)
                }
            } ?: logger.error(
                "Plugin '${container.id}' disable timed out after ${DISABLE_TIMEOUT_MS / 1000}s — force-disabling."
            )
        } finally {
            // Always cancel the scope and clear services, even if onDisable threw or timed out.
            (container.context as? ScopedPluginContext)?.cancelScope()
            container.services = emptyList()
            container.status = PluginStatus.DISABLED
            container.lastError = null
            logger.info("Plugin '${container.id}' disabled.")
        }
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Calls `Plugin.onDisable()` (if enabled) then `Plugin.shutdown()` for final cleanup.
     * Used only during application shutdown — does not update flows or registry state.
     */
    suspend fun shutdown(container: PluginContainer) {
        try {
            if (container.status == PluginStatus.ENABLED) {
                withTimeoutOrNull(10_000) {
                    runCatching { container.plugin.onDisable() }
                } ?: logger.error("Plugin '${container.id}' onDisable timed out during shutdown")
                (container.context as? ScopedPluginContext)?.cancelScope()
            }

            withTimeoutOrNull(10_000) {
                runCatching { container.plugin.shutdown() }
            } ?: logger.error("Plugin '${container.id}' shutdown timed out")

            // After the plugin has had its say, since it may still make requests on the way out.
            (container.context as? ScopedPluginContext)?.closeHttp()

        } catch (e: Throwable) {
            logger.error("Unexpected exception shutting down plugin '${container.id}'", e)
        }
    }
}
