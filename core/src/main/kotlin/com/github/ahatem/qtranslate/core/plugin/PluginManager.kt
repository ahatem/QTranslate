package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.plugin.installer.PluginInstaller
import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginLifecycleHandler
import com.github.ahatem.qtranslate.core.plugin.registry.PluginContainer
import com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry
import com.github.ahatem.qtranslate.core.plugin.settings.PluginSettingsManager
import com.github.ahatem.qtranslate.core.plugin.settings.PluginSettingsModel
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprint
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationCode
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The central coordinator for the plugin subsystem.
 *
 * `PluginManager` is intentionally thin — it orchestrates its collaborators
 * rather than owning any logic itself:
 *
 * - [PluginRegistry] — in-memory store of loaded plugins and their state
 * - [PluginLifecycleHandler] — initialize / enable / disable / shutdown logic
 * - [PluginInstaller] — install / uninstall / JAR-replacement resolution
 * - [PluginSettingsManager] — schema building and settings application
 * - [PluginFingerprintRepository] — JAR integrity tracking across runs
 *
 * ### Threading
 * All mutations to [PluginRegistry] go through [PluginRegistry.mutex].
 * [updateFlows] must be called after any mutation to push the new state to observers.
 * UI observers collect [plugins] and [activeServices] on their own dispatcher.
 */
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

    private val registry = PluginRegistry()

    private val lifecycleHandler = PluginLifecycleHandler(
        appDataDirectory = appDataDirectory,
        pluginKeyValueStore = pluginKeyValueStore,
        notificationBus = notificationBus,
        loggerFactory = loggerFactory
    )

    private val installer = PluginInstaller(
        pluginsDir = pluginsDir,
        appDataDirectory = appDataDirectory,
        registry = registry,
        lifecycleHandler = lifecycleHandler,
        pluginKeyValueStore = pluginKeyValueStore,
        settingsRepository = settingsRepository,
        loggerFactory = loggerFactory
    )

    private val settingsManager = PluginSettingsManager(pluginKeyValueStore, loggerFactory)

    private val _plugins = MutableStateFlow<List<PluginState>>(emptyList())
    private val _activeServices = MutableStateFlow<Map<String, Service>>(emptyMap())

    /** Observable list of all loaded plugins with their current state. */
    val plugins: StateFlow<List<PluginState>> = _plugins.asStateFlow()

    /** Observable map of service ID → [Service] for all currently enabled plugins. */
    val activeServices: StateFlow<Map<String, Service>> = _activeServices.asStateFlow()

    // -------------------------------------------------------------------------
    // Startup: discovery and initialization
    // -------------------------------------------------------------------------

    /**
     * Scans the plugins directory, validates, and initializes all discovered plugins.
     * Plugins whose JARs have changed since the last run are paused in
     * [PluginStatus.AWAITING_VERIFICATION] pending user confirmation.
     */
    suspend fun loadAndProcessPlugins() {
        withContext(Dispatchers.IO) {
            logger.info("Starting plugin discovery from: ${pluginsDir.absolutePath}")

            val knownFingerprints = pluginFingerprintRepository.loadFingerprints()
            val disabledPluginIds = settingsRepository.loadDisabledPluginIds()
            val loader = PluginLoader(loggerFactory.getLogger("PluginLoader"))

            val rawPlugins = loader.loadPluginsFromDirectory(pluginsDir)
            val loadResult = registry.validateAndFilter(rawPlugins)

            logDiscoverySummary(loadResult)

            supervisorScope {
                loadResult.successful.map { result ->
                    async {
                        val savedHash = knownFingerprints[result.manifest.id]
                        if (savedHash != null && result.jarHash != savedHash) {
                            handleReplacedPlugin(result)
                        } else {
                            initializePlugin(result, disabledPluginIds)
                        }
                    }
                }.joinAll()
            }

            val allPlugins = registry.mutex.withLock { registry.getAll() }
            val enabled = allPlugins.count { it.status == PluginStatus.ENABLED }
            logger.info(
                "Plugin loading complete: ${allPlugins.size} total, $enabled enabled."
            )

            // Surface load failures and JAR-change warnings to the UI via NotificationBus.
            // Errors that happened during validateAndFilter are in loadResult.failed —
            // those plugins never made it into the registry so we report them separately.
            loadResult.failed.forEach { error ->
                notificationBus.post(
                    AppNotification(
                        type           = com.github.ahatem.qtranslate.api.plugin.NotificationType.ERROR,
                        code           = NotificationCode.Custom("Plugin failed to load", "${error.pluginId}: ${error.message}"),
                        sourcePluginId = error.pluginId
                    )
                )
            }

            // Plugins that loaded but are paused pending user confirmation
            allPlugins.filter { it.status == PluginStatus.AWAITING_VERIFICATION }.forEach { container ->
                notificationBus.post(
                    AppNotification(
                        type           = com.github.ahatem.qtranslate.api.plugin.NotificationType.WARNING,
                        code           = NotificationCode.Custom("Plugin update detected", "${container.id}: JAR has changed — open Plugin Manager to verify."),
                        sourcePluginId = container.id
                    )
                )
            }

            // First-run / no plugins: guide the user
            if (allPlugins.isEmpty()) {
                notificationBus.post(
                    AppNotification(
                        type = com.github.ahatem.qtranslate.api.plugin.NotificationType.WARNING,
                        code = NotificationCode.Custom("No plugins found", "Drop plugin JARs into the plugins folder and restart, or install via Plugin Manager.")
                    )
                )
            } else if (enabled == 0) {
                notificationBus.post(
                    AppNotification(
                        type = com.github.ahatem.qtranslate.api.plugin.NotificationType.WARNING,
                        code = NotificationCode.Custom("No services active", "All plugins are disabled or failed. Open Plugin Manager to enable them.")
                    )
                )
            }
        }

        updateFlows()
    }

    // -------------------------------------------------------------------------
    // Enable / Disable
    // -------------------------------------------------------------------------

    suspend fun enablePlugin(pluginId: String) {
        val container = registry.mutex.withLock { registry.get(pluginId) } ?: return
        if (container.status == PluginStatus.ENABLED) return

        lifecycleHandler.enable(container)

        val currentDisabled = settingsRepository.loadDisabledPluginIds()
        settingsRepository.saveDisabledPluginIds(currentDisabled - pluginId)

        updateFlows()
    }

    suspend fun disablePlugin(pluginId: String) {
        val container = registry.mutex.withLock { registry.get(pluginId) } ?: return
        if (container.status != PluginStatus.ENABLED) return

        lifecycleHandler.disable(container)

        val currentDisabled = settingsRepository.loadDisabledPluginIds()
        settingsRepository.saveDisabledPluginIds(currentDisabled + pluginId)

        updateFlows()
    }

    // -------------------------------------------------------------------------
    // Installation & JAR-replacement resolution (delegate to PluginInstaller)
    // -------------------------------------------------------------------------

    suspend fun installPlugin(sourceJar: File): Result<Unit, String> =
        installer.installPlugin(sourceJar).also { updateFlows() }

    suspend fun uninstallPlugin(pluginId: String) {
        installer.uninstallPlugin(pluginId)
        updateFlows()
    }

    suspend fun resolveAsUpdate(pluginId: String) {
        installer.resolveAsUpdate(pluginId)
        updateFlows()
    }

    suspend fun resolveAsCleanInstall(pluginId: String) {
        installer.resolveAsCleanInstall(pluginId)
        updateFlows()
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    suspend fun getPluginSettingsModel(pluginId: String): PluginSettingsModel? {
        val container = registry.mutex.withLock { registry.get(pluginId) } ?: return null
        return settingsManager.getSettingsModel(pluginId, container.plugin)
    }

    /**
     * Applies [settingsMap] to the plugin identified by [pluginId].
     * On success, rebuilds the plugin's service list and updates flows.
     */
    suspend fun applySettingsFromMap(pluginId: String, settingsMap: Map<String, String>): Result<Unit, ServiceError> {
        val container = registry.mutex.withLock { registry.get(pluginId) }
            ?: return Err(ServiceError.UnknownError("Plugin '$pluginId' not found", null))

        val result = settingsManager.applySettings(pluginId, container.plugin, settingsMap)

        if (result.isOk) {
            registry.mutex.withLock {
                container.services = container.plugin.getServices()
            }
            updateFlows()
        }

        return result
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    suspend fun shutdown() {
        logger.info("Shutting down all plugins...")

        saveRegistryOnShutdown()

        registry.mutex.withLock { registry.getAll().toList() }.forEach { container ->
            lifecycleHandler.shutdown(container)
        }

        registry.mutex.withLock { registry.clear() }
        updateFlows()

        logger.info("All plugins shut down.")
    }

    // -------------------------------------------------------------------------
    // Service routing
    // -------------------------------------------------------------------------

    /** Returns the [ClassLoader] that loaded the plugin providing [serviceId], or null. */
    fun getPluginClassLoaderForService(serviceId: String): ClassLoader? =
        registry.findByServiceId(serviceId)?.classLoader

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private suspend fun initializePlugin(result: LoadedPluginResult, disabledPluginIds: Set<String>) {
        val context = lifecycleHandler.createContext(result)
        val container = PluginContainer(
            plugin = result.plugin,
            manifest = result.manifest,
            context = context,
            jarFile = result.jarFile,
            jarHash = result.jarHash,
            classLoader = result.classLoader
        )

        val initialized = lifecycleHandler.initialize(container)
        registry.mutex.withLock { registry.put(container) }

        if (initialized && container.id !in disabledPluginIds) {
            lifecycleHandler.enable(container)
        }
    }

    private suspend fun handleReplacedPlugin(result: LoadedPluginResult) {
        logger.warn(
            "Plugin '${result.manifest.id}' JAR has changed since last run. " +
                    "Pausing until user confirms the change."
        )
        val context = lifecycleHandler.createContext(result)
        val container = PluginContainer(
            plugin = result.plugin,
            manifest = result.manifest,
            context = context,
            jarFile = result.jarFile,
            jarHash = result.jarHash,
            classLoader = result.classLoader,
            status = PluginStatus.AWAITING_VERIFICATION
        )
        registry.mutex.withLock { registry.put(container) }
    }

    private suspend fun saveRegistryOnShutdown() {
        val fingerprints = registry.mutex.withLock {
            registry.getAll()
                .filter {
                    it.status != PluginStatus.FAILED &&
                            it.status != PluginStatus.AWAITING_VERIFICATION
                }
                .map { PluginFingerprint(it.id, it.jarHash) }
        }
        pluginFingerprintRepository.storeFingerprints(fingerprints)
        logger.info("Saved plugin registry (${fingerprints.size} fingerprint(s)).")
    }

    private fun logDiscoverySummary(loadResult: com.github.ahatem.qtranslate.core.plugin.registry.PluginLoadResult) {
        logger.info(
            "Plugin discovery: ${loadResult.successful.size} valid, " +
                    "${loadResult.failed.size} failed, ${loadResult.skipped.size} skipped"
        )
        loadResult.failed.forEach { logger.error("  FAILED  ${it.pluginId}: ${it.message}", it.cause) }
        loadResult.skipped.forEach { logger.warn("  SKIPPED ${it.pluginId}: ${it.message}") }
    }

    /**
     * Pushes the current registry state to both StateFlows.
     * Both flows are updated inside a single mutex lock so they always reflect a
     * consistent snapshot of the same registry state.
     */
    private suspend fun updateFlows() {
        registry.mutex.withLock {
            _plugins.value = registry.snapshot()
            _activeServices.value = registry.activeServices()
        }
    }
}