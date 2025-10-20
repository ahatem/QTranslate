package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class PluginLifecycleManager(
    private val appDataDirectory: File,
    private val loggerFactory: LoggerFactory,
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val notificationBus: NotificationBus,
    private val settingsRepository: SettingsRepository,
    private val pluginFingerprintRepository: PluginFingerprintRepository
) {
    private val logger = loggerFactory.getLogger("PluginLifecycleManager")
    private val loadedPlugins = mutableMapOf<String, PluginContainer>()
    private val stateMutex = Mutex()

    private val settingsManager = PluginSettingsManager(pluginKeyValueStore, logger)

    private val _plugins = MutableStateFlow<List<PluginState>>(emptyList())
    private val _activeServices = MutableStateFlow<Map<String, Service>>(emptyMap())

    val plugins: StateFlow<List<PluginState>> = _plugins.asStateFlow()
    val activeServices: StateFlow<Map<String, Service>> = _activeServices.asStateFlow()

    private val pluginsDir = File(appDataDirectory, "plugins").also { it.mkdirs() }

    suspend fun loadAllPlugins() = stateMutex.withLock {
        val loader = PluginLoader(loggerFactory.getLogger("PluginLoader"))
        val jarFiles = loader.loadPluginsFromDirectory(pluginsDir)
        val knownFingerprints = pluginFingerprintRepository.loadFingerprints()
        val disabledIds = settingsRepository.loadDisabledPluginIds()
        val unique = filterUniquePlugins(jarFiles)

        supervisorScope {
            unique.map { result ->
                async {
                    val savedHash = knownFingerprints[result.manifest.id]
                    if (savedHash != null && savedHash != result.jarHash) {
                        handleReplacedPlugin(result)
                    } else {
                        initializePlugin(result, disabledIds)
                    }
                }
            }.awaitAll()
        }

        updateFlows()
        logger.info("Finished loading ${loadedPlugins.size} plugins")
    }

    private fun handleReplacedPlugin(result: LoadedPluginResult) {
        logger.warn("Plugin '${result.manifest.id}' changed. Awaiting user verification.")
        val pluginLogger = loggerFactory.getLogger(result.manifest.id)
        val context = ScopedPluginContext(
            pluginId = result.manifest.id,
            appDataDirectory = appDataDirectory,
            pluginKeyValueStore = pluginKeyValueStore,
            notificationBus = notificationBus,
            logger = pluginLogger
        )
        loadedPlugins[result.manifest.id] = PluginContainer(
            plugin = result.plugin,
            manifest = result.manifest,
            context = context,
            jarFile = result.jarFile,
            jarHash = result.jarHash,
            classLoader = result.classLoader,
            status = PluginStatus.AWAITING_VERIFICATION
        )
    }

    private suspend fun initializePlugin(result: LoadedPluginResult, disabledIds: Set<String>) {
        val (plugin, manifest, jarFile, jarHash, classLoader) = result
        val logger = loggerFactory.getLogger(manifest.id)
        val context = ScopedPluginContext(
            pluginId = manifest.id,
            appDataDirectory = appDataDirectory,
            pluginKeyValueStore = pluginKeyValueStore,
            notificationBus = notificationBus,
            logger = logger
        )

        try {
            plugin.initialize(context).onFailure { error ->
                logger.error("Failed to initialize plugin '${manifest.id}': ${error.message}", error.cause)
                loadedPlugins[manifest.id] =
                    PluginContainer(plugin, manifest, context, jarFile, jarHash, classLoader, PluginStatus.FAILED)
                return
            }

            val container = PluginContainer(plugin, manifest, context, jarFile, jarHash, classLoader)
            loadedPlugins[manifest.id] = container

            if (manifest.id !in disabledIds) enablePluginInternal(container)
        } catch (e: Throwable) {
            logger.error("Unexpected exception during initialization of plugin '${manifest.id}'", e)
            loadedPlugins[manifest.id] =
                PluginContainer(plugin, manifest, context, jarFile, jarHash, classLoader, PluginStatus.FAILED)
        }
    }

    suspend fun enablePlugin(pluginId: String) = stateMutex.withLock {
        val container = loadedPlugins[pluginId] ?: return
        if (container.status == PluginStatus.ENABLED) return

        enablePluginInternal(container)
        updateFlows()
    }

    suspend fun disablePlugin(pluginId: String) = stateMutex.withLock {
        val container = loadedPlugins[pluginId] ?: return
        if (container.status != PluginStatus.ENABLED) return

        disablePluginInternal(container)
        updateFlows()
    }

    private suspend fun enablePluginInternal(container: PluginContainer) {
        if (container.status == PluginStatus.FAILED) return
        try {
            container.plugin.onEnable().onSuccess {
                container.services = container.plugin.getServices()
                container.status = PluginStatus.ENABLED
            }.onFailure { error ->
                container.status = PluginStatus.FAILED
                logger.error("Failed to enable plugin '${container.id}': ${error.message}", error.cause)
            }
        } catch (e: Throwable) {
            container.status = PluginStatus.FAILED
            logger.error("Unexpected exception enabling plugin '${container.id}'", e)
        }
    }

    private suspend fun disablePluginInternal(container: PluginContainer) {
        try {
            container.plugin.onDisable()
            container.services = emptyList()
            container.status = PluginStatus.DISABLED
        } catch (e: Throwable) {
            container.status = PluginStatus.FAILED
            logger.error("Unexpected exception disabling plugin '${container.id}'", e)
        }
    }

    private fun filterUniquePlugins(plugins: List<LoadedPluginResult>): List<LoadedPluginResult> {
        val seen = mutableMapOf<String, String>()
        val unique = mutableListOf<LoadedPluginResult>()
        plugins.forEach { result ->
            val id = result.manifest.id
            val jar = result.jarFile.name
            if (seen.containsKey(id)) {
                logger.error("Duplicate plugin ID '$id' in '$jar' and '${seen[id]}'. Skipping.")
                unique.removeAll { it.manifest.id == id }
            } else {
                seen[id] = jar
                unique.add(result)
            }
        }
        return unique
    }

    private fun updateFlows() {
        _plugins.value = loadedPlugins.values.map {
            PluginState(it.manifest, it.status, it.jarFile.absolutePath, it.services)
        }
        _activeServices.value = loadedPlugins.values
            .filter { it.status == PluginStatus.ENABLED }
            .flatMap { it.services }
            .associateBy { it.id }
    }

    fun getPluginClassLoaderForService(serviceId: String): ClassLoader? =
        loadedPlugins.values.find { it.services.any { it.id == serviceId } }?.classLoader
}
