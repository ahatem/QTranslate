package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.plugin.installer.PluginInstallLocation
import com.github.ahatem.qtranslate.core.plugin.installer.PluginInstaller
import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginLifecycleHandler
import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginTransitionOwner
import com.github.ahatem.qtranslate.core.plugin.registry.PluginContainer
import com.github.ahatem.qtranslate.core.plugin.registry.PluginError
import com.github.ahatem.qtranslate.core.plugin.registry.PluginLoadResult
import com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry
import com.github.ahatem.qtranslate.core.plugin.settings.PluginSettingsManager
import com.github.ahatem.qtranslate.core.plugin.settings.PluginSettingsModel
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprint
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.http.HttpClientConfig
import com.github.ahatem.qtranslate.core.plugin.http.toHttpClientConfig
import com.github.ahatem.qtranslate.core.plugin.storage.AppSecretStore
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.text.PluginTextResolver
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationCode
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
 * All mutations to [PluginRegistry] go through [PluginRegistry.mutex]. Lifecycle transitions —
 * initialization, enable/disable, install/uninstall, update resolution, validation, settings
 * application, shutdown — are serialized per plugin id by [transitions] and revalidate the
 * registry on entry, so the callbacks [com.github.ahatem.qtranslate.api.plugin.Plugin]
 * documents as sequential stay sequential no matter which dispatcher requests them.
 * [updateFlows] must be called after any mutation to push the new state to observers.
 * UI observers collect [plugins] and [activeServices] on their own dispatcher.
 */
class PluginManager(
    private val appDataDirectory: File,
    private val settingsRepository: SettingsRepository,
    private val pluginFingerprintRepository: PluginFingerprintRepository,
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val loggerFactory: LoggerFactory,
    private val notificationBus: NotificationBus,
    private val networkConfig: () -> NetworkConfig,
    /**
     * Resolves the [com.github.ahatem.qtranslate.api.plugin.DisplayText] plugins hand back.
     * Defaults to the fallback-only resolver so a host without localization still runs.
     */
    private val textResolver: PluginTextResolver = PluginTextResolver.Fallback,
    // Where the bundled distribution keeps its plugin JARs, when that differs from the user's own
    // folder. Those JARs are read-only here: they are loaded, but install and uninstall only ever
    // touch appDataDirectory. Null when the two are one folder, as in a portable distribution.
    installationRoot: File? = null,
    /** How long shutdown waits per transition before treating it as a straggler; test knob. */
    transitionSettleTimeoutMs: Long = PluginTransitionOwner.DEFAULT_SETTLE_TIMEOUT_MS
) {
    private val logger = loggerFactory.getLogger("PluginManager")

    /** The user's own plugin folder — the only one written to or deleted from. */
    private val pluginsDir = File(appDataDirectory, AppConstants.PLUGIN_DIRECTORY).also { it.mkdirs() }

    /** The distribution's bundled plugin folder, read-only. Null when there is only one folder. */
    private val bundledPluginsDir: File? = installationRoot
        ?.takeIf { it != appDataDirectory }
        ?.let { File(it, AppConstants.PLUGIN_DIRECTORY) }
        ?.takeIf { it.isDirectory }

    /**
     * Loads a directory and returns its results alongside the failures.
     *
     * The failures are copied out immediately because [PluginLoader] clears its own list on the next
     * scan, so scanning a second folder would otherwise discard the first folder's.
     */
    private fun loadDirectory(
        loader: PluginLoader,
        directory: File
    ): Pair<List<PluginAssessment>, List<PluginError>> {
        val results = loader.assessDirectory(directory)
        return results to loader.loadFailures
    }

    private val registry = PluginRegistry()

    /**
     * Owns lifecycle transitions: one at a time per plugin id. Every path that drives a plugin's
     * lifecycle — startup initialization, enable/disable, install/uninstall, update resolution,
     * validation, settings application, and shutdown — goes through here, so the plugin-facing
     * contract (lifecycle callbacks are sequential) holds regardless of which dispatcher the
     * request came from.
     */
    private val transitions = PluginTransitionOwner(
        loggerFactory.getLogger("PluginTransitionOwner"),
        transitionSettleTimeoutMs,
        onLateTransition = ::finalizeLateTransition
    )

    /**
     * Containers whose accepted transition outlived the shutdown settle. Their transition owns
     * the instance until its callback returns, so shutdown parks the container here instead of
     * tearing it down concurrently; the transition itself finalizes and removes it.
     */
    private val lateFinalization = ConcurrentHashMap<String, PluginContainer>()

    private val appSecrets = AppSecretStore(pluginKeyValueStore)

    internal suspend fun currentHttpConfig(): HttpClientConfig {
        val network = networkConfig()
        val proxyPassword = if (network.proxyEnabled && network.proxyUrl.isNotBlank()) {
            appSecrets.get(NetworkConfig.proxyPasswordKey)
        } else {
            null
        }
        return network.toHttpClientConfig(proxyPassword = proxyPassword)
    }

    private val lifecycleHandler = PluginLifecycleHandler(
        appDataDirectory = appDataDirectory,
        pluginKeyValueStore = pluginKeyValueStore,
        notificationBus = notificationBus,
        textResolver = textResolver,
        loggerFactory = loggerFactory,
        httpConfig = ::currentHttpConfig
    )

    private val installer = PluginInstaller(
        userPluginsDirectory = pluginsDir,
        appDataDirectory = appDataDirectory,
        registry = registry,
        lifecycleHandler = lifecycleHandler,
        pluginKeyValueStore = pluginKeyValueStore,
        settingsRepository = settingsRepository,
        transitions = transitions,
        loggerFactory = loggerFactory
    )

    private val settingsManager = PluginSettingsManager(pluginKeyValueStore, loggerFactory)

    private val _plugins = MutableStateFlow<List<PluginState>>(emptyList())
    private val _activeServices = MutableStateFlow<Map<String, Service>>(emptyMap())

    /**
     * Discovery failures reported alongside loaded plugins. Written at startup and on
     * discovery-failure uninstall, read when uninstalling and when flows are pushed: the
     * reference is volatile so a UI thread always sees a complete list, never a torn one.
     */
    @Volatile
    private var discoveryFailureStates: List<PluginState> = emptyList()

    /** First caller owns shutdown; a second call would otherwise persist an empty fingerprint set. */
    private val shutdownStarted = AtomicBoolean(false)

    /**
     * IDs whose loaded JAR came from the distribution rather than the user's own plugins folder.
     *
     * Empty in a portable distribution, where both are one folder and every JAR is the user's to
     * remove.
     */
    private var bundledPluginIds: Set<String> = emptySet()

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
            bundledPluginsDir?.let { logger.info("Bundled plugins read from: ${it.absolutePath}") }

            val knownFingerprints = pluginFingerprintRepository.loadFingerprints()
            val disabledPluginIds = settingsRepository.loadDisabledPluginIds()
            val snapshotsDirectory = File(appDataDirectory, "plugin-snapshots")
            val loader = PluginLoader(loggerFactory.getLogger("PluginLoader"), snapshotsDirectory)
            // Orphaned snapshots from a crashed previous run are inert but would accumulate
            // forever; this process has not created any yet, so clearing the cache is safe.
            sweepStaleSnapshots(snapshotsDirectory)

            val discovery = discoverPlugins(loader)
            bundledPluginIds = discovery.bundledIds
            val loadResult = discovery.result
            val assessedFailureCount = loader.loadFailures.size

            supervisorScope {
                loadResult.successful.map { result ->
                    async {
                        val savedHash = knownFingerprints[result.manifest.id]
                        if (savedHash != null && result.jarHash != savedHash) {
                            handleReplacedPlugin(result, savedHash)
                        } else {
                            initializePlugin(loader, result, disabledPluginIds)
                        }
                    }
                }.joinAll()
            }

            val activationFailures = loader.loadFailures.drop(assessedFailureCount)
            val discoveryErrors = discovery.failures + loadResult.failed + loadResult.skipped + activationFailures
            discoveryFailureStates = discoveryErrors.mapIndexed(::toFailedPluginState)
            logDiscoverySummary(loadResult, discovery.failures + activationFailures)

            val allPlugins = registry.mutex.withLock { registry.getAll().toList() }
            val pendingPlugins = registry.mutex.withLock { registry.getAllPending().toList() }
            val enabled = allPlugins.count { it.status == PluginStatus.ENABLED }
            logger.info(
                "Plugin loading complete: ${allPlugins.size + pendingPlugins.size} total, $enabled enabled."
            )

            // Surface load failures and JAR-change warnings to the UI via NotificationBus.
            // Errors that happened during validateAndFilter are in loadResult.failed —
            // those plugins never made it into the registry so we report them separately.
            discoveryErrors.forEach { error ->
                notificationBus.post(
                    AppNotification(
                        type           = com.github.ahatem.qtranslate.api.plugin.NotificationType.ERROR,
                        code           = NotificationCode.Custom("Plugin failed to load", "${error.pluginId}: ${error.message}"),
                        sourcePluginId = error.pluginId
                    )
                )
            }

            // Plugins that loaded but are paused pending user confirmation
            pendingPlugins.forEach { container ->
                notificationBus.post(
                    AppNotification(
                        type           = com.github.ahatem.qtranslate.api.plugin.NotificationType.WARNING,
                        code           = NotificationCode.Custom("Plugin update detected", "${container.id}: JAR has changed — open Plugin Manager to verify."),
                        sourcePluginId = container.id
                    )
                )
            }

            // First-run / no plugins: guide the user
            if (allPlugins.isEmpty() && pendingPlugins.isEmpty()) {
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

    /**
     * Enables [pluginId].
     *
     * The whole transition — state revalidation, `onEnable()`, service registration, disabled-ID
     * persistence — runs serialized against every other transition of the same plugin, and is
     * rejected once shutdown has settled in-flight work. A request racing an in-flight
     * activation waits for it, then revalidates: the state on entry decides, never the state
     * read before the transition was queued.
     */
    suspend fun enablePlugin(pluginId: String) {
        transitions.transition(pluginId) {
            val container = registry.mutex.withLock { registry.get(pluginId) } ?: return@transition
            if (container.status == PluginStatus.ENABLED) return@transition

            lifecycleHandler.enable(container)

            if (container.status == PluginStatus.ENABLED) {
                settingsRepository.updateDisabledPluginIds { it - pluginId }.getError()?.let {
                    logger.error("Failed to persist enabled state for plugin '$pluginId'")
                }
            }

            updateFlows()
        } ?: logger.warn("Ignored enable request for '$pluginId': plugin transitions are closed")
    }

    /**
     * Disables [pluginId], serialized per plugin like [enablePlugin]. A disable queued behind an
     * in-flight activation runs once that activation settles, so the plugin ends disabled and its
     * `onDisable()` runs exactly once.
     */
    suspend fun disablePlugin(pluginId: String) {
        transitions.transition(pluginId) {
            val container = registry.mutex.withLock { registry.get(pluginId) } ?: return@transition
            if (container.status != PluginStatus.ENABLED) return@transition

            lifecycleHandler.disable(container)

            settingsRepository.updateDisabledPluginIds { it + pluginId }.getError()?.let {
                logger.error("Failed to persist disabled state for plugin '$pluginId'")
            }

            updateFlows()
        } ?: logger.warn("Ignored disable request for '$pluginId': plugin transitions are closed")
    }

    // -------------------------------------------------------------------------
    // Installation & JAR-replacement resolution (delegate to PluginInstaller)
    // -------------------------------------------------------------------------

    suspend fun installPlugin(sourceJar: File): Result<Unit, String> =
        installer.installPlugin(sourceJar).also { updateFlows() }

    /**
     * Whether [pluginId] is a plugin the distribution ships, and so may not be uninstalled.
     *
     * The UI asks this before offering the action. A bundled plugin can still be disabled, which is
     * the operation that actually turns it off.
     */
    fun isBundled(pluginId: String): Boolean = bundledPluginIds.contains(pluginId)

    /**
     * Removes a user-installed plugin.
     *
     * A bundled plugin is refused outright. Ignoring the request would bring it back on the next
     * launch, and deleting its JAR would leave an installed copy without a component it cannot
     * restore, so neither half of "uninstall until restart" is offered.
     */
    suspend fun uninstallPlugin(pluginId: String) {
        if (isBundled(pluginId)) {
            logger.info("Refusing to uninstall bundled plugin '$pluginId'; disable it instead.")
            return
        }

        val discoveryFailure = discoveryFailureStates.firstOrNull { it.id == pluginId }
        if (discoveryFailure != null) {
            transitions.transition(pluginId) {
                val jar = File(discoveryFailure.jarPath)
                // A JAR the distribution shipped is not the user's to remove, so removing the broken
                // one is limited to the user's own folder exactly as the normal uninstall path is.
                val removable = PluginInstallLocation.isUserInstalled(pluginsDir, jar)
                if (removable) {
                    withContext(Dispatchers.IO) { jar.delete() }
                } else {
                    logger.info("Kept bundled plugin JAR at ${jar.absolutePath}")
                }
                discoveryFailureStates = discoveryFailureStates.filterNot { it.id == pluginId }
                // The broken JAR may have been disabled before it broke; leaving the id behind
                // would silently disable a same-id plugin the user installs later.
                settingsRepository.updateDisabledPluginIds { it - pluginId }.getError()?.let {
                    logger.error("Failed to remove disabled state for plugin '$pluginId'")
                }
            } ?: logger.warn("Ignored uninstall of broken plugin '$pluginId': plugin transitions are closed")
            updateFlows()
            return
        }
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
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Asks each of a plugin's services whether it is correctly configured.
     *
     * Runs serialized with the plugin's other transitions — validation calls into plugin code —
     * and concurrently across the plugin's own services: a check is a network round trip, and a
     * plugin exposing six of them should not take six times as long to answer. Each is given its
     * own timeout so one unresponsive endpoint cannot hold up the rest.
     *
     * A service that does not implement a check answers successfully, which is honest: there is
     * nothing about it that can be misconfigured.
     */
    suspend fun validateServices(pluginId: String): List<ServiceValidation> {
        return transitions.transition(pluginId) {
            val container = registry.mutex.withLock { registry.get(pluginId) } ?: return@transition emptyList()
            val timeoutMs = validateTimeoutMs(networkConfig())

            return@transition withContext(Dispatchers.IO) {
                supervisorScope {
                    container.services.map { service ->
                        async {
                            val outcome = withTimeoutOrNull(timeoutMs) {
                                runCatching { service.validate() }
                                    .getOrElse { thrown ->
                                        // A check that throws is a failed check, not a crashed app.
                                        Err(
                                            ServiceError.UnknownError(
                                                "Validation threw ${thrown::class.java.simpleName}",
                                                thrown
                                            )
                                        )
                                    }
                            } ?: Err(
                                ServiceError.TimeoutError(
                                    "Validation did not answer within ${timeoutMs / 1000}s"
                                )
                            )

                            ServiceValidation(
                                serviceId = container.serviceIdOf(service),
                                serviceName = service.name,
                                error = outcome.getError()
                            )
                        }
                    }.awaitAll()
                }
            }
        } ?: emptyList()
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    /**
     * Returns the settings model for [pluginId], or null.
     *
     * Reads stay outside the transition slot: they only call the plugin's pure `getSettings()`,
     * while every write goes through [applySettingsFromMap] and its transition.
     */
    suspend fun getPluginSettingsModel(pluginId: String): PluginSettingsModel? {
        val container = registry.mutex.withLock { registry.get(pluginId) } ?: return null
        return settingsManager.getSettingsModel(pluginId, container.plugin)
    }

    /**
     * Returns the live settings instance for [pluginId], cast to
     * [com.github.ahatem.qtranslate.api.plugin.PluginSettings.Configurable], or `null`
     * if the plugin has no configurable settings.
     *
     * Used by the settings dialog to invoke `@PluginAction`-annotated methods on the live
     * settings object.
     */
    suspend fun getPluginSettingsInstance(pluginId: String): com.github.ahatem.qtranslate.api.plugin.PluginSettings.Configurable? {
        val container = registry.mutex.withLock { registry.get(pluginId) } ?: return null
        return container.plugin.getSettings() as? com.github.ahatem.qtranslate.api.plugin.PluginSettings.Configurable
    }

    /**
     * Applies [settingsMap] to the plugin identified by [pluginId].
     * On success, rebuilds the plugin's service list and updates flows.
     *
     * Serialized with the plugin's other transitions: applying settings calls back into the
     * plugin (`onSettingsChanged`) and swaps its service list.
     */
    suspend fun applySettingsFromMap(pluginId: String, settingsMap: Map<String, String>): Result<Unit, ServiceError> {
        return transitions.transition(pluginId) {
            val container = registry.mutex.withLock { registry.get(pluginId) }
                ?: return@transition Err(ServiceError.UnknownError("Plugin '$pluginId' not found", null))

            val result = withTimeoutOrNull(SETTINGS_APPLY_TIMEOUT_MS) {
                settingsManager.applySettings(pluginId, container.plugin, settingsMap)
            } ?: Err(ServiceError.TimeoutError("Applying settings to '$pluginId' timed out"))

            if (result.isOk) {
                registry.mutex.withLock {
                    container.services = container.plugin.getServices()
                    container.declaredServices = container.services
                }
                updateFlows()
            }

            result
        } ?: Err(
            ServiceError.UnknownError(
                "Plugin '$pluginId' settings were not applied: plugin transitions are closed", null
            )
        )
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Shuts down every plugin.
     *
     * Ownership contract: [PluginTransitionOwner.shutdown] first closes the gate — transitions
     * requested from here on are rejected — and then settles every already-accepted transition
     * concurrently against one global deadline. Only containers whose transition settled are
     * finalized here. A straggler is left to its own accepted transition: that transition calls
     * [finalizeLateTransition] before releasing its slot, so application shutdown never runs
     * `onDisable()`/`shutdown()` at the same time as a callback on the same plugin instance.
     * Finalization is claimed atomically, so the two paths cannot run the final callbacks twice.
     */
    suspend fun shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            logger.info("Plugin shutdown already ran; ignoring the duplicate request.")
            return
        }

        logger.info("Shutting down all plugins...")

        // Park every live container before the gate closes: a transition that later turns out
        // to be a straggler finds its instance here, even after the registry is cleared.
        registry.mutex.withLock { registry.getAll().toList() }.forEach { container ->
            lateFinalization[container.id] = container
        }

        val stragglers = transitions.shutdown()

        withContext(NonCancellable) {
            saveRegistryOnShutdown()

            registry.mutex.withLock { registry.getAll().toList() }
                .filterNot { it.id in stragglers }
                .forEach { container ->
                    lateFinalization.remove(container.id)
                    lifecycleHandler.shutdown(container)
                }

            // Settled transitions have been finalized above (or by their own completion before
            // the gate closed). Only stragglers still need the parked reference.
            lateFinalization.keys.retainAll(stragglers)

            registry.mutex.withLock { registry.getAllPending().toList() }.forEach { it.assessment.discard() }

            registry.mutex.withLock { registry.clear() }
            discoveryFailureStates = emptyList()
            updateFlows()
        }

        logger.info("All plugins shut down.")
    }

    /**
     * Finalizes a container whose accepted transition completed after shutdown began. Runs
     * inside that transition's slot, after the callback returned, so it cannot overlap plugin
     * lifecycle work; the atomic claim in [PluginLifecycleHandler.shutdown] keeps a racing
     * shutdown loop from running the final callbacks a second time.
     */
    private suspend fun finalizeLateTransition(pluginId: String) {
        if (!transitions.isShutdown()) return
        // The registry is intentionally left to shutdown: keeping the container registered
        // until then preserves its fingerprint and lets the normal clear remove it.
        val container = lateFinalization.remove(pluginId) ?: return
        lifecycleHandler.shutdown(container)
    }

    // -------------------------------------------------------------------------
    // Service routing
    // -------------------------------------------------------------------------

    /**
     * Returns the [ClassLoader] that loaded the plugin providing [serviceId], or null.
     *
     * Non-suspending because icon rendering calls it on the EDT; the lookup itself is safe
     * without the registry mutex (see [com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry.findByServiceId]).
     */
    fun getPluginClassLoaderForService(serviceId: String): ClassLoader? =
        registry.findByServiceId(serviceId)?.classLoader

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
 * Loads plugins from the user's folder and, when it is separate, from the distribution's.
 *
     * A bundled JAR whose ID the user has since installed is dropped rather than reported as a
     * duplicate: that shadowing is how a user replaces a bundled plugin without the installation
     * directory being written to. Two JARs claiming one ID within the same folder stay ambiguous,
     * and [PluginRegistry.validateAndFilter] still rejects both.
     */
    private fun discoverPlugins(loader: PluginLoader): PluginDiscovery {
        val (userResults, userFailures) = loadDirectory(loader, pluginsDir)
        val bundledDir = bundledPluginsDir ?: return PluginDiscovery(
            results = userResults,
            bundledIds = emptySet(),
            result = registry.validateAndFilter(userResults),
            failures = userFailures
        )

        val (bundledAll, bundledFailures) = loadDirectory(loader, bundledDir)
        val userIds = userResults.mapTo(mutableSetOf()) { it.manifest.id }
        val bundledResults = bundledAll.filter { it.manifest.id !in userIds }
        bundledAll.filter { it.manifest.id in userIds }.forEach { it.discard() }
        val shadowed = bundledAll.size - bundledResults.size
        if (shadowed > 0) {
            logger.info("$shadowed bundled plugin(s) superseded by a user-installed copy.")
        }

        // The user's folder is listed first so that a duplicate ID spanning the two is reported with
        // the user's copy as the one already seen.
        val all = userResults + bundledResults
        return PluginDiscovery(
            results = all,
            bundledIds = bundledResults.mapTo(mutableSetOf()) { it.manifest.id },
            result = registry.validateAndFilter(all),
            failures = userFailures + bundledFailures
        )
    }

    /** What discovery found: the plugins to initialise, which are bundled, and the ones to report. */
    private data class PluginDiscovery(
        val results: List<PluginAssessment>,
        val bundledIds: Set<String>,
        val result: PluginLoadResult,
        val failures: List<PluginError>
    )

    /**
     * Loads, constructs, initializes, and registers one assessed plugin — one transition.
     *
     * Construction ([PluginLoader.loadApproved]) happens only here, inside the plugin's
     * transition, after the fingerprint decision that approved these bytes. If shutdown closes
     * the gate while startup is still discovering plugins, the assessment is discarded instead:
     * a snapshot nobody approved is never constructed from.
     */
    private suspend fun initializePlugin(loader: PluginLoader, assessment: PluginAssessment, disabledPluginIds: Set<String>) {
        try {
            transitions.transition(assessment.manifest.id) {
                val result = loader.loadApproved(assessment) ?: run { assessment.discard(); return@transition }
                val context = lifecycleHandler.createContext(result)
                val container = PluginContainer(
                    plugin = result.plugin,
                    manifest = result.manifest,
                    context = context,
                    jarFile = result.jarFile,
                    jarHash = result.jarHash,
                    classLoader = result.classLoader,
                    snapshotFile = result.snapshotFile,
                    bundled = bundledPluginIds.contains(result.manifest.id)
                )

                val initialized = lifecycleHandler.initialize(container)
                if (initialized) {
                    container.declaredServices = runCatching {
                        withTimeoutOrNull(GET_SERVICES_TIMEOUT_MS) { container.plugin.getServices() }
                    }.onFailure {
                        logger.warn(
                            "Could not inspect services for '${container.id}' " +
                                    "(${it::class.simpleName})"
                        )
                    }
                        .getOrNull()
                        ?: emptyList()
                }
                registry.mutex.withLock { registry.put(container) }

                // Shutdown may have timed out waiting for this transition (see the owner's settle
                // bound). The registry it registers into has already been cleared, so finalize the
                // registration here instead of leaving a live instance nobody owns.
                if (transitions.isShutdown()) {
                    lifecycleHandler.shutdown(container)
                    registry.mutex.withLock { registry.remove(container.id) }
                    return@transition
                }

                if (initialized && container.id !in disabledPluginIds) {
                    lifecycleHandler.enable(container)
                }
            } ?: assessment.discard()
        } catch (e: CancellationException) {
            // A cancelled discovery still owns the assessed snapshot; the transition body is
            // NonCancellable, so cancellation can only land here while queued for the slot.
            assessment.discard()
            throw e
        }
    }

    /**
     * Pauses a plugin whose JAR no longer matches the hash the user last approved.
     *
     * Only the inert assessment is registered — no provider is constructed, no class loader is
     * created, no snapshot is executed. [previousHash] is the approved baseline: it is persisted
     * verbatim on shutdown so the mismatch is still detected after a restart instead of
     * degrading into a first-run trust decision.
     */
    private suspend fun handleReplacedPlugin(result: PluginAssessment, previousHash: String) {
        logger.warn(
            "Plugin '${result.manifest.id}' JAR has changed since last run. " +
                    "Pausing until user confirms the change."
        )
        transitions.transition(result.manifest.id) {
            registry.mutex.withLock {
                registry.putPending(
                    PluginRegistry.PendingPlugin(
                        result,
                        bundledPluginIds.contains(result.manifest.id),
                        previousHash
                    )
                )
            }
        } ?: result.discard()
    }

    /**
     * Persists the approved fingerprint of every known plugin.
     *
     * Containers contribute their current hash — including FAILED ones, so a broken plugin does
     * not fall back to trust-on-first-use next launch. Pending plugins contribute the hash the
     * user last approved, which is what keeps an unresolved modification detected across
     * restarts.
     */
    private suspend fun saveRegistryOnShutdown() {
        val fingerprints = registry.mutex.withLock {
            registry.getAll().map { PluginFingerprint(it.id, it.jarHash) } +
                    registry.getAllPending().map { PluginFingerprint(it.id, it.previousHash) }
        }
        pluginFingerprintRepository.storeFingerprints(fingerprints)
        logger.info("Saved plugin registry (${fingerprints.size} fingerprint(s)).")
    }

    /**
     * Removes assessed snapshots left behind by a previous process that crashed before
     * finalizing them. They are inert and read-only, but they would otherwise accumulate in the
     * app data directory forever. Safe at startup only: this process has not created any
     * snapshot yet, so everything in the cache belongs to a dead process.
     */
    private fun sweepStaleSnapshots(snapshotsDirectory: File) {
        val stale = snapshotsDirectory.listFiles().orEmpty()
        if (stale.isEmpty()) return
        stale.forEach { file ->
            if (!file.delete()) {
                logger.warn("Could not remove stale plugin snapshot ${file.absolutePath}")
            }
        }
        logger.info("Removed ${stale.size} stale plugin snapshot(s) from a previous run.")
    }

    private fun logDiscoverySummary(
        loadResult: PluginLoadResult,
        loaderFailures: List<PluginError>
    ) {
        logger.info(
            "Plugin discovery: ${loadResult.successful.size} valid, " +
                    "${loadResult.failed.size + loaderFailures.size} failed, ${loadResult.skipped.size} skipped"
        )
        loadResult.successful.forEach {
            logger.info("  LOADED  ${it.manifest.id} v${it.manifest.version} (${it.jarFile.name})")
        }
        loaderFailures.forEach { logger.error("  FAILED  ${it.pluginId} (${it::class.simpleName})") }
        loadResult.failed.forEach { logger.error("  FAILED  ${it.pluginId} (${it::class.simpleName})") }
        loadResult.skipped.forEach { logger.warn("  SKIPPED ${it.pluginId} (${it::class.simpleName})") }
    }

    private fun toFailedPluginState(index: Int, error: PluginError): PluginState {
        val jarPath = when (error) {
            is PluginError.LoadFailure -> error.jarPath
            is PluginError.InvalidManifest -> error.jarPath
            // Duplicate errors carry only JAR names, so the folder they sat in is not recoverable from the
            // error itself. Both folders are searched, and the match inside the user's own folder
            // wins, because only that one may be deleted by the uninstall path below.
            is PluginError.DuplicateId ->
                listOf(pluginsDir, bundledPluginsDir)
                    .filterNotNull()
                    .firstNotNullOfOrNull { dir -> File(dir, error.duplicateJar).takeIf { it.isFile } }
                    ?.absolutePath
                    ?: File(pluginsDir, error.duplicateJar).absolutePath
            else -> File(pluginsDir, error.pluginId).absolutePath
        }
        val displayName = File(jarPath).name.takeIf { it.isNotBlank() } ?: error.pluginId
        val safeName = displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return PluginState(
            manifest = PluginManifest(
                id = "load-failure-$index-$safeName",
                name = displayName,
                version = "unknown",
                author = "unknown",
                description = "This plugin could not be loaded.",
                minApiVersion = "1.0.0"
            ),
            status = PluginStatus.FAILED,
            jarPath = jarPath,
            lastError = error
        )
    }

    /**
     * Pushes the current registry state to both StateFlows.
     * Both flows are updated inside a single mutex lock so they always reflect a
     * consistent snapshot of the same registry state.
     */
    private suspend fun updateFlows() {
        registry.mutex.withLock {
            _plugins.value = registry.snapshot() + discoveryFailureStates
            _activeServices.value = registry.activeServices()
        }
    }
}

/** Matches the clamp [com.github.ahatem.qtranslate.core.plugin.http.toHttpClientConfig] applies. */
private const val MAX_VALIDATE_RETRIES = 10

/** Ceiling for one plugin service declaration, which is plugin code despite its fast contract. */
private const val GET_SERVICES_TIMEOUT_MS = 15_000L

/** Ceiling for one settings apply, which calls back into plugin code. */
private const val SETTINGS_APPLY_TIMEOUT_MS = 15_000L

/** Scheduling and answer-reading time around the last attempt, on top of its request timeout. */
private const val VALIDATE_HEADROOM_MS = 2_000L

/**
 * The floor for a service check, and the backstop for one that hangs on something that is not a
 * request. How much higher the ceiling climbs depends on the network settings the check's own
 * request is subject to; see [validateTimeoutMs].
 */
private const val VALIDATE_TIMEOUT_FLOOR_MS = 15_000L

/**
 * How long a plugin service's connection test may run before it is abandoned.
 *
 * A check is a real request, so it lives under the very timeouts the user set in Settings →
 * Network — and a cap shorter than those convicts a working endpoint: the same request would be
 * allowed to succeed in ordinary use, yet the test reports a timeout. The cap is therefore never
 * below what one check could legitimately take: the request timeout — or a larger per-host
 * override — times the attempts a retry policy may spend, plus the backoff between them, plus a
 * little headroom. The backoff sum is deliberately the worst case rather than an exact mirror of
 * the client's ladder, whose jitter makes an exact figure fiction; overestimating only delays a
 * cap that normally has nothing to stop.
 *
 * The floor stays in place so a check that never issues a request — a plugin whose validate()
 * spins on non-network work — still cannot hold the settings dialog open forever.
 */
internal fun validateTimeoutMs(network: NetworkConfig): Long {
    val attemptTimeoutSeconds = maxOf(
        network.requestTimeoutSeconds,
        network.hostTimeoutSeconds.values.maxOrNull() ?: 0
    ).coerceAtLeast(1)

    val retries = if (network.retryEnabled) network.maxRetries.coerceIn(0, MAX_VALIDATE_RETRIES) else 0
    var backoffMs = 0L
    var delayMs = network.retryInitialDelaySeconds.coerceIn(1, 60) * 1_000L
    repeat(retries) { backoffMs += delayMs; delayMs *= 2 }

    val longestLegalRequestMs = attemptTimeoutSeconds * 1_000L * (retries + 1) + backoffMs
    return maxOf(VALIDATE_TIMEOUT_FLOOR_MS, longestLegalRequestMs + VALIDATE_HEADROOM_MS)
}
