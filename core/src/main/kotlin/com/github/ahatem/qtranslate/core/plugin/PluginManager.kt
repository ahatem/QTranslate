package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.onFailure
import kotlin.runCatching

data class PluginContainer(
    val plugin: Plugin<*>,
    val manifest: PluginManifest,
    val context: PluginContext,
    val jarFile: File,
    val jarHash: String,
    val classLoader: ClassLoader,
    var status: PluginStatus = PluginStatus.DISABLED,
    var services: List<Service> = emptyList()
) {
    val id: String get() = manifest.id
}

// TODO: refactor/extract PluginLifecycleManager and PluginInstaller
class PluginManager(
    private val appDataDirectory: File,
    private val settingsRepository: SettingsRepository,
    private val pluginFingerprintRepository: PluginFingerprintRepository,
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val loggerFactory: LoggerFactory,
    private val notificationBus: NotificationBus
) {
    private val logger = loggerFactory.getLogger("PluginManager")
    private val pluginsDir = File(appDataDirectory, "plugins").also { it.mkdirs() }
    private val stateMutex = Mutex()

    private val settingsManager = PluginSettingsManager(pluginKeyValueStore, logger)

    private val loadedPlugins = mutableMapOf<String, PluginContainer>()
    private val _plugins = MutableStateFlow<List<PluginState>>(emptyList())
    private val _activeServices = MutableStateFlow<Map<String, Service>>(emptyMap())

    val plugins: StateFlow<List<PluginState>> = _plugins.asStateFlow()
    val activeServices: StateFlow<Map<String, Service>> = _activeServices.asStateFlow()

    suspend fun loadAndProcessPlugins() {
        withContext(Dispatchers.IO) {
            logger.info("Starting plugin discovery from: ${pluginsDir.absolutePath}")

            val knownPlugins = pluginFingerprintRepository.loadFingerprints()
            val disabledPluginIds = settingsRepository.loadDisabledPluginIds()
            val loader = PluginLoader(loggerFactory.getLogger("PluginLoader"))
            val discovered = filterUniquePlugins(loader.loadPluginsFromDirectory(pluginsDir))

            supervisorScope {
                discovered.map { result ->
                    async {
                        val savedHash = knownPlugins[result.manifest.id]
                        if (savedHash != null && result.jarHash != savedHash) {
                            handleReplacedPlugin(result)
                        } else {
                            initializePlugin(result, disabledPluginIds)
                        }
                    }
                }
            }.awaitAll()
        }

        logger.info("Finished plugin discovery. Loaded ${loadedPlugins.size} plugins.")
        updateFlows()
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

        try {
            plugin.initialize(context).onFailure { error ->
                logger.error("Failed to initialize plugin '${manifest.id}': ${error.message}", error.cause)
                loadedPlugins[manifest.id] = PluginContainer(
                    plugin = plugin,
                    manifest = manifest,
                    context = context,
                    jarFile = jarFile,
                    jarHash = jarHash,
                    classLoader = classLoader,
                    status = PluginStatus.FAILED
                )
                return
            }

            val container = PluginContainer(plugin, manifest, context, jarFile, jarHash, classLoader)
            loadedPlugins[manifest.id] = container
            logger.info("Initialized plugin '${manifest.name}' (ID: ${manifest.id})")

            if (manifest.id !in disabledPluginIds) {
                enablePluginInternal(container)
            }
        } catch (e: Throwable) {
            logger.error("Unexpected exception during initialization of plugin '${manifest.id}'.", e)
            loadedPlugins[manifest.id] = PluginContainer(
                plugin = plugin,
                manifest = manifest,
                context = context,
                jarFile = jarFile,
                jarHash = jarHash,
                classLoader = classLoader,
                status = PluginStatus.FAILED
            )
        }
    }

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
        container.status = PluginStatus.DISABLED // Reset status to allow initialization
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
            container.plugin.onEnable().onSuccess {
                container.services = container.plugin.getServices()
                container.status = PluginStatus.ENABLED
                logger.info("Plugin '${container.id}' enabled successfully with ${container.services.size} services.")
            }.onFailure { error ->
                container.status = PluginStatus.FAILED
                logger.error("Plugin '${container.id}' failed to enable: ${error.message}", error.cause)
            }
        } catch (e: Throwable) {
            container.status = PluginStatus.FAILED
            logger.error("Unexpected exception while enabling plugin '${container.id}'.", e)
        }
    }

    private suspend fun disablePluginInternal(container: PluginContainer) {
        try {
            container.plugin.onDisable()
            container.services = emptyList()
            container.status = PluginStatus.DISABLED
            logger.info("Plugin '${container.id}' disabled successfully.")
        } catch (e: Throwable) {
            container.status = PluginStatus.FAILED
            logger.error("Unexpected exception while disabling plugin '${container.id}'.", e)
        }
    }

    suspend fun <S : Any> applySettings(pluginId: String, newSettings: S): Result<Unit, ServiceError> {
        val result = stateMutex.withLock {
            val container = loadedPlugins[pluginId] ?: return@withLock Err(
                ServiceError.UnknownError(
                    "Plugin '$pluginId' not found",
                    null
                )
            )

            @Suppress("UNCHECKED_CAST")
            val typedPlugin = (container.plugin as? Plugin<S>) ?: return@withLock Err(
                ServiceError.InvalidInputError(
                    "Mismatched settings type for plugin '$pluginId'",
                    null
                )
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
                if (container.status == PluginStatus.ENABLED) container.plugin.onDisable()
                container.plugin.shutdown()
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
                services = container.services
            )
        }

        _activeServices.value = loadedPlugins.values
            .filter { it.status == PluginStatus.ENABLED }
            .flatMap { it.services }
            .associateBy { it.id }
    }

    private fun filterUniquePlugins(plugins: List<LoadedPluginResult>): List<LoadedPluginResult> {
        val seenIds = mutableMapOf<String, String>()
        val unique = mutableListOf<LoadedPluginResult>()
        plugins.forEach { result ->
            val id = result.manifest.id
            val jarName = result.jarFile.name
            if (seenIds.containsKey(id)) {
                logger.error("SECURITY ALERT: Duplicate plugin ID '$id' found in '$jarName' and '${seenIds[id]}'. Both will be disabled.")
                unique.removeAll { it.manifest.id == id }
            } else {
                seenIds[id] = jarName
                unique.add(result)
            }
        }
        return unique
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
