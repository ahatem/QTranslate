package com.github.ahatem.qtranslate.core.plugin


import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.michaelbull.result.*
import com.github.michaelbull.result.onFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.Any
import kotlin.Exception
import kotlin.Float
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.Throwable
import kotlin.Unit
import kotlin.also
import kotlin.let
import kotlin.onFailure
import kotlin.run
import kotlin.runCatching
import kotlin.synchronized

// ============================================================================
// Structured Error Types
// ============================================================================

/**
 * Comprehensive error types for plugin operations
 */
sealed class PluginError(
    open val pluginId: String,
    open val message: String,
    open val cause: Throwable?
) {
    data class LoadFailure(
        override val pluginId: String,
        override val message: String,
        override val cause: Throwable?,
        val jarPath: String
    ) : PluginError(pluginId, message, cause)

    data class InitializationFailure(
        override val pluginId: String,
        override val message: String,
        override val cause: Throwable?
    ) : PluginError(pluginId, message, cause)

    data class EnableFailure(
        override val pluginId: String,
        override val message: String,
        override val cause: Throwable?
    ) : PluginError(pluginId, message, cause)

    data class DisableFailure(
        override val pluginId: String,
        override val message: String,
        override val cause: Throwable?
    ) : PluginError(pluginId, message, cause)

    data class DuplicateId(
        override val pluginId: String,
        val existingJar: String,
        val duplicateJar: String
    ) : PluginError(pluginId, "Duplicate plugin ID found", null)

    data class InvalidManifest(
        override val pluginId: String,
        override val message: String,
        val jarPath: String
    ) : PluginError(pluginId, message, null)
}

/**
 * Result of plugin loading operation with detailed tracking
 */
data class PluginLoadResult(
    val successful: List<LoadedPluginResult>,
    val failed: List<PluginError>,
    val skipped: List<PluginError>
) {
    val totalAttempted: Int = successful.size + failed.size + skipped.size
    val successRate: Float = if (totalAttempted > 0) successful.size.toFloat() / totalAttempted else 0f
}

data class PluginContainer(
    val plugin: Plugin<*>,
    val manifest: PluginManifest,
    val context: PluginContext,
    val jarFile: File,
    val jarHash: String,
    val classLoader: ClassLoader,
    var status: PluginStatus = PluginStatus.DISABLED,
    var services: List<Service> = emptyList(),
    var lastError: PluginError? = null // Track last error for debugging
) {
    val id: String get() = manifest.id
}

// ============================================================================
// Enhanced Plugin Manager
// ============================================================================

class PluginManager(
    private val appDataDirectory: File,
    private val settingsRepository: SettingsRepository,
    private val pluginFingerprintRepository: PluginFingerprintRepository,
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val loggerFactory: LoggerFactory,
    private val notificationBus: NotificationBus
) {
    private val logger = loggerFactory.getLogger("PluginManager")
    private val pluginsDir = File(appDataDirectory, AppConstants.PLUGIN_DIRECTORY).also { it.mkdirs() }
    private val stateMutex = Mutex()

    private val settingsManager = PluginSettingsManager(pluginKeyValueStore, loggerFactory)

    private val loadedPlugins = mutableMapOf<String, PluginContainer>()
    private val _plugins = MutableStateFlow<List<PluginState>>(emptyList())
    private val _activeServices = MutableStateFlow<Map<String, Service>>(emptyMap())

    val plugins: StateFlow<List<PluginState>> = _plugins.asStateFlow()
    val activeServices: StateFlow<Map<String, Service>> = _activeServices.asStateFlow()

    // ============================================================================
    // Improved Plugin Loading with Comprehensive Error Tracking
    // ============================================================================

    suspend fun loadAndProcessPlugins() {
        withContext(Dispatchers.IO) {
            logger.info("Starting plugin discovery from: ${pluginsDir.absolutePath}")

            val knownPlugins = pluginFingerprintRepository.loadFingerprints()
            val disabledPluginIds = settingsRepository.loadDisabledPluginIds()
            val loader = PluginLoader(loggerFactory.getLogger("PluginLoader"))

            // Enhanced discovery with validation
            val rawPlugins = loader.loadPluginsFromDirectory(pluginsDir)
            val loadResult = validateAndFilterPlugins(rawPlugins)

            // Log comprehensive summary
            logger.info(
                "Plugin discovery complete: ${loadResult.successful.size} valid, " +
                        "${loadResult.failed.size} failed, ${loadResult.skipped.size} skipped"
            )

            if (loadResult.failed.isNotEmpty()) {
                logger.error("Failed to load ${loadResult.failed.size} plugin(s):")
                loadResult.failed.forEach { error ->
                    logger.error("  - ${error.pluginId}: ${error.message}", error.cause)
                }
            }

            if (loadResult.skipped.isNotEmpty()) {
                logger.warn("Skipped ${loadResult.skipped.size} plugin(s):")
                loadResult.skipped.forEach { error ->
                    logger.warn("  - ${error.pluginId}: ${error.message}")
                }
            }

            // Robust parallel initialization with error tracking
            val initErrors = mutableListOf<PluginError>()

            supervisorScope {
                loadResult.successful.map { result ->
                    async {
                        runCatching {
                            val savedHash = knownPlugins[result.manifest.id]
                            if (savedHash != null && result.jarHash != savedHash) {
                                handleReplacedPlugin(result)
                            } else {
                                initializePlugin(result, disabledPluginIds)
                            }
                        }.onFailure { error ->
                            val pluginError = PluginError.InitializationFailure(
                                pluginId = result.manifest.id,
                                message = "Unexpected exception during initialization",
                                cause = error
                            )
                            synchronized(initErrors) {
                                initErrors.add(pluginError)
                            }
                            logger.error(
                                "Failed to initialize plugin '${result.manifest.id}': ${error.message}",
                                error
                            )
                        }
                    }
                }.awaitAll()
            }

            // Report final statistics
            val finalLoadedCount = loadedPlugins.size
            val enabledCount = loadedPlugins.values.count { it.status == PluginStatus.ENABLED }
            val failedCount = loadedPlugins.values.count { it.status == PluginStatus.FAILED }

            logger.info(
                "Plugin loading finished: $finalLoadedCount total " +
                        "($enabledCount enabled, $failedCount failed, ${initErrors.size} init errors)"
            )
        }

        updateFlows()
    }

    /**
     * Validates and filters plugins with detailed error tracking
     */
    private fun validateAndFilterPlugins(rawPlugins: List<LoadedPluginResult>): PluginLoadResult {
        val seenIds = mutableMapOf<String, LoadedPluginResult>()
        val successful = mutableListOf<LoadedPluginResult>()
        val failed = mutableListOf<PluginError>()
        val skipped = mutableListOf<PluginError>()

        rawPlugins.forEach { result ->
            val id = result.manifest.id
            val jarName = result.jarFile.name

            // Validate plugin ID
            if (id.isBlank()) {
                failed.add(
                    PluginError.InvalidManifest(
                        pluginId = jarName,
                        message = "Plugin ID cannot be blank",
                        jarPath = result.jarFile.absolutePath
                    )
                )
                return@forEach
            }

            // Check for duplicates
            val existing = seenIds[id]
            if (existing != null) {
                val duplicateError = PluginError.DuplicateId(
                    pluginId = id,
                    existingJar = existing.jarFile.name,
                    duplicateJar = jarName
                )

                logger.error(
                    "SECURITY ALERT: Duplicate plugin ID '$id' found in " +
                            "'$jarName' and '${existing.jarFile.name}'. Both will be rejected."
                )

                // Remove previously added plugin
                successful.removeAll { it.manifest.id == id }

                // Add errors for both
                skipped.add(duplicateError)
                skipped.add(
                    duplicateError.copy(
                        duplicateJar = existing.jarFile.name,
                        existingJar = jarName
                    )
                )

                // Mark as seen but invalid
                seenIds[id] = result
            } else {
                // Valid unique plugin
                seenIds[id] = result
                successful.add(result)
            }
        }

        return PluginLoadResult(
            successful = successful,
            failed = failed,
            skipped = skipped
        )
    }

    private fun handleReplacedPlugin(result: LoadedPluginResult) {
        logger.warn("Plugin '${result.manifest.id}' JAR has changed since last run. Awaiting user verification.")
        val pluginLogger = loggerFactory.getLogger(result.manifest.id)
        val context = ScopedPluginContext(
            pluginId = result.manifest.id,
            appDataDirectory = appDataDirectory,
            pluginKeyValueStore = pluginKeyValueStore,
            notificationBus = notificationBus,
            logger = pluginLogger,
        )
        val container = PluginContainer(
            plugin = result.plugin,
            manifest = result.manifest,
            context = context,
            jarFile = result.jarFile,
            jarHash = result.jarHash,
            classLoader = result.classLoader,
            status = PluginStatus.AWAITING_VERIFICATION
        )
        loadedPlugins[result.manifest.id] = container
    }

    /**
     * Enhanced initialization with comprehensive error handling
     */
    private suspend fun initializePlugin(result: LoadedPluginResult, disabledPluginIds: Set<String>) {
        val (plugin, manifest, jarFile, jarHash, classLoader) = result
        val pluginLogger = loggerFactory.getLogger(manifest.id)
        val context = ScopedPluginContext(
            pluginId = result.manifest.id,
            appDataDirectory = appDataDirectory,
            pluginKeyValueStore = pluginKeyValueStore,
            notificationBus = notificationBus,
            logger = pluginLogger,
        )

        val container = PluginContainer(
            plugin = plugin,
            manifest = manifest,
            context = context,
            jarFile = jarFile,
            jarHash = jarHash,
            classLoader = classLoader
        )

        try {
            // Initialize with timeout protection
            val initResult = withTimeoutOrNull(30_000) {
                plugin.initialize(context)
            }

            if (initResult == null) {
                val error = PluginError.InitializationFailure(
                    pluginId = manifest.id,
                    message = "Plugin initialization timed out after 30 seconds",
                    cause = null
                )
                container.status = PluginStatus.FAILED
                container.lastError = error
                loadedPlugins[manifest.id] = container
                logger.error("Plugin '${manifest.id}' initialization timed out")
                return
            }

            initResult.onFailure { error ->
                val pluginError = PluginError.InitializationFailure(
                    pluginId = manifest.id,
                    message = error.message,
                    cause = error.cause
                )
                container.status = PluginStatus.FAILED
                container.lastError = pluginError
                loadedPlugins[manifest.id] = container
                logger.error("Failed to initialize plugin '${manifest.id}': ${error.message}", error.cause)
                return
            }

            // Success
            loadedPlugins[manifest.id] = container
            logger.info("Initialized plugin '${manifest.name}' (ID: ${manifest.id})")

            if (manifest.id !in disabledPluginIds) {
                enablePluginInternal(container)
            }
        } catch (e: Throwable) {
            val error = PluginError.InitializationFailure(
                pluginId = manifest.id,
                message = "Unexpected exception during initialization: ${e.message}",
                cause = e
            )
            container.status = PluginStatus.FAILED
            container.lastError = error
            loadedPlugins[manifest.id] = container
            logger.error("Unexpected exception during initialization of plugin '${manifest.id}'.", e)
        }
    }

    // ============================================================================
    // Plugin Installation & Management
    // ============================================================================

    suspend fun installPlugin(sourceJar: File): Result<Unit, String> = stateMutex.withLock {
        val loader = PluginLoader(loggerFactory.getLogger("PluginLoader"))
        val manifest = loader.getManifestFromJar(sourceJar)
            ?: return@withLock Err("The selected file is not a valid plugin.")

        if (loadedPlugins.containsKey(manifest.id)) {
            return@withLock Err("A plugin with ID '${manifest.id}' is already installed. Please uninstall it first or use the 'Update' functionality.")
        }

        try {
            val destinationJar = File(pluginsDir, sourceJar.name)
            Files.copy(sourceJar.toPath(), destinationJar.toPath(), StandardCopyOption.REPLACE_EXISTING)

            val result = loader.loadPluginFromFile(destinationJar)
            if (result != null) {
                initializePlugin(result, emptySet())
                updateFlows()
                return@withLock Ok(Unit)
            } else {
                return@withLock Err("Failed to load the newly installed plugin.")
            }
        } catch (e: Exception) {
            logger.error("Failed to install plugin from ${sourceJar.path}", e)
            return@withLock Err("An error occurred during installation: ${e.message}")
        }
    }

    suspend fun resolveAsUpdate(pluginId: String) = stateMutex.withLock {
        val container = loadedPlugins[pluginId] ?: return@withLock
        if (container.status != PluginStatus.AWAITING_VERIFICATION) return@withLock

        logger.info("User confirmed UPDATE for plugin '$pluginId'. Keeping existing data.")
        container.status = PluginStatus.DISABLED
        initializePlugin(
            LoadedPluginResult(
                container.plugin,
                container.manifest,
                container.jarFile,
                container.jarHash,
                container.classLoader
            ),
            settingsRepository.loadDisabledPluginIds()
        )
        updateFlows()
    }

    suspend fun resolveAsCleanInstall(pluginId: String) = stateMutex.withLock {
        val container = loadedPlugins[pluginId] ?: return@withLock
        if (container.status != PluginStatus.AWAITING_VERIFICATION) return@withLock

        logger.warn("User confirmed CLEAN RE-INSTALL for plugin '$pluginId'. Wiping its data.")
        purgePluginSandbox(pluginId)
        resolveAsUpdate(pluginId)
    }

    suspend fun uninstallPlugin(pluginId: String) = stateMutex.withLock {
        val container = loadedPlugins.remove(pluginId) ?: return@withLock
        logger.info("Uninstalling plugin '$pluginId'...")
        purgePluginSandbox(pluginId)
        val currentDisabled = settingsRepository.loadDisabledPluginIds()
        settingsRepository.saveDisabledPluginIds(currentDisabled - pluginId)
        runCatching { container.jarFile.delete() }.onFailure {
            logger.error("Failed to delete JAR file: ${container.jarFile.path}", it)
        }
        updateFlows()
        logger.info("Plugin '$pluginId' uninstalled successfully.")
    }

    private suspend fun purgePluginSandbox(pluginId: String) {
        pluginKeyValueStore.deleteAllData(pluginId)
        val dataDir = File(appDataDirectory, "plugins_data/$pluginId")
        if (dataDir.exists()) dataDir.deleteRecursively()
        logger.info("Purged data sandbox for plugin ID '$pluginId'")
    }

    private suspend fun saveRegistryOnShutdown() {
        val fingerprints = loadedPlugins.values
            .filter { it.status != PluginStatus.FAILED && it.status != PluginStatus.AWAITING_VERIFICATION }
            .map { PluginFingerprint(it.id, it.jarHash) }
        pluginFingerprintRepository.storeFingerprints(fingerprints)
        logger.info("Saved known plugin registry.")
    }

    // ============================================================================
    // Enable/Disable with Enhanced Error Handling
    // ============================================================================

    suspend fun enablePlugin(pluginId: String) = stateMutex.withLock {
        val container = loadedPlugins[pluginId] ?: return
        if (container.status == PluginStatus.ENABLED) return

        enablePluginInternal(container)

        val currentDisabled = settingsRepository.loadDisabledPluginIds()
        settingsRepository.saveDisabledPluginIds(currentDisabled - pluginId)

        updateFlows()
    }

    suspend fun disablePlugin(pluginId: String) = stateMutex.withLock {
        val container = loadedPlugins[pluginId] ?: return
        if (container.status != PluginStatus.ENABLED) return

        disablePluginInternal(container)

        val currentDisabled = settingsRepository.loadDisabledPluginIds()
        settingsRepository.saveDisabledPluginIds(currentDisabled + pluginId)

        updateFlows()
    }

    private suspend fun enablePluginInternal(container: PluginContainer) {
        if (container.status == PluginStatus.FAILED) {
            logger.warn("Cannot enable plugin '${container.id}' because it is in a FAILED state.")
            return
        }

        try {
            val enableResult = withTimeoutOrNull(15_000) {
                container.plugin.onEnable()
            }

            if (enableResult == null) {
                val error = PluginError.EnableFailure(
                    pluginId = container.id,
                    message = "Plugin enable timed out after 15 seconds",
                    cause = null
                )
                container.status = PluginStatus.FAILED
                container.lastError = error
                logger.error("Plugin '${container.id}' enable timed out")
                return
            }

            enableResult.onSuccess {
                container.services = container.plugin.getServices()
                container.status = PluginStatus.ENABLED
                container.lastError = null
                logger.info("Plugin '${container.id}' enabled successfully with ${container.services.size} services.")
            }.onFailure { error ->
                val pluginError = PluginError.EnableFailure(
                    pluginId = container.id,
                    message = error.message ?: "Unknown enable error",
                    cause = error.cause
                )
                container.status = PluginStatus.FAILED
                container.lastError = pluginError
                logger.error("Plugin '${container.id}' failed to enable: ${error.message}", error.cause)
            }
        } catch (e: Throwable) {
            val error = PluginError.EnableFailure(
                pluginId = container.id,
                message = "Unexpected exception while enabling: ${e.message}",
                cause = e
            )
            container.status = PluginStatus.FAILED
            container.lastError = error
            logger.error("Unexpected exception while enabling plugin '${container.id}'.", e)
        }
    }

    private suspend fun disablePluginInternal(container: PluginContainer) {
        try {
            withTimeoutOrNull(15_000) {
                container.plugin.onDisable()
            } ?: run {
                logger.error("Plugin '${container.id}' disable timed out after 15 seconds")
                // Force disable anyway
            }

            container.services = emptyList()
            container.status = PluginStatus.DISABLED
            container.lastError = null
            logger.info("Plugin '${container.id}' disabled successfully.")
        } catch (e: Throwable) {
            val error = PluginError.DisableFailure(
                pluginId = container.id,
                message = "Unexpected exception while disabling: ${e.message}",
                cause = e
            )
            container.status = PluginStatus.FAILED
            container.lastError = error
            logger.error("Unexpected exception while disabling plugin '${container.id}'.", e)
        }
    }

    suspend fun <S : Any> applySettings(pluginId: String, newSettings: S): Result<Unit, ServiceError> {
        val result = stateMutex.withLock {
            val container = loadedPlugins[pluginId] ?: return@withLock Err(
                ServiceError.UnknownError("Plugin '$pluginId' not found", null)
            )

            @Suppress("UNCHECKED_CAST")
            val typedPlugin = (container.plugin as? Plugin<S>) ?: return@withLock Err(
                ServiceError.InvalidInputError("Mismatched settings type for plugin '$pluginId'", null)
            )

            val applyResult = try {
                typedPlugin.onSettingsChanged(newSettings)
            } catch (e: Throwable) {
                logger.error("Unexpected exception in onSettingsChanged for plugin '${container.id}'.", e)
                Err(ServiceError.UnknownError("Plugin threw an exception", e))
            }

            if (applyResult.isOk) {
                container.services = typedPlugin.getServices()
            }
            applyResult
        }
        if (result.isOk) updateFlows()
        return result
    }

    suspend fun shutdown() = stateMutex.withLock {
        logger.info("Shutting down plugins...")
        saveRegistryOnShutdown()

        loadedPlugins.values.forEach { container ->
            try {
                if (container.status == PluginStatus.ENABLED) {
                    withTimeoutOrNull(10_000) {
                        container.plugin.onDisable()
                    } ?: logger.error("Plugin '${container.id}' disable timed out during shutdown")
                }

                withTimeoutOrNull(10_000) {
                    container.plugin.shutdown()
                } ?: logger.error("Plugin '${container.id}' shutdown timed out")
            } catch (e: Throwable) {
                logger.error("Unexpected exception while shutting down plugin '${container.id}'.", e)
            }
        }
        loadedPlugins.clear()
        updateFlows()
        logger.info("All plugins shut down.")
    }

    private fun updateFlows() {
        _plugins.value = loadedPlugins.values.map { container ->
            PluginState(
                manifest = container.manifest,
                status = container.status,
                jarPath = container.jarFile.absolutePath,
                services = container.services,
                lastError = container.lastError
            )
        }

        _activeServices.value = loadedPlugins.values
            .filter { it.status == PluginStatus.ENABLED }
            .flatMap { it.services }
            .associateBy { it.id }
    }

    fun getPluginClassLoaderForService(serviceId: String): ClassLoader? {
        return loadedPlugins.values.find { container ->
            container.services.any { it.id == serviceId }
        }?.classLoader
    }

    suspend fun getPluginSettingsModel(pluginId: String): PluginSettingsModel? =
        stateMutex.withLock {
            loadedPlugins[pluginId]?.let { container ->
                settingsManager.getSettingsModel(pluginId, container.plugin)
            }
        }

    suspend fun applySettingsFromMap(pluginId: String, settingsMap: Map<String, String>): Result<Unit, ServiceError> {
        val container = stateMutex.withLock { loadedPlugins[pluginId] }
            ?: return Err(ServiceError.UnknownError("Plugin not found", null))

        @Suppress("UNCHECKED_CAST")
        return settingsManager.applySettings(
            pluginId,
            container.plugin as Plugin<Any>,
            settingsMap
        ).also { result ->
            if (result.isOk) {
                stateMutex.withLock {
                    container.services = container.plugin.getServices()
                }
                updateFlows()
            }
        }
    }
}