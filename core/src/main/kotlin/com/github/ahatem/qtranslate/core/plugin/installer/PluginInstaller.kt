package com.github.ahatem.qtranslate.core.plugin.installer

import com.github.ahatem.qtranslate.core.plugin.LoadedPluginResult
import com.github.ahatem.qtranslate.core.plugin.PluginLoader
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginLifecycleHandler
import com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Handles plugin installation, uninstallation, and JAR-replacement resolution.
 *
 * This class deals exclusively with the filesystem side of plugin management
 * (copying JARs, deleting data directories) and delegates runtime state changes
 * to [PluginLifecycleHandler] and [PluginRegistry].
 *
 * All public methods acquire the [PluginRegistry.mutex] themselves — callers must
 * NOT already hold the mutex when calling these methods.
 */
internal class PluginInstaller(
    /**
     * Where user-installed plugin JARs live. The only folder this installer writes to or deletes
     * from, so an installed copy's bundled plugins are never overwritten or removed.
     */
    private val userPluginsDirectory: File,
    private val appDataDirectory: File,
    private val registry: PluginRegistry,
    private val lifecycleHandler: PluginLifecycleHandler,
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val settingsRepository: SettingsRepository,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("PluginInstaller")
    private val loader = PluginLoader(loggerFactory.getLogger("PluginLoader"))

    /**
     * Whether [jarFile] is one the user installed rather than one the distribution shipped.
     *
     * Uninstall deletes a JAR only when this holds. A bundled JAR belongs to the installation and
     * would be unrecoverable without reinstalling, so it is kept and reported instead. In a portable
     * distribution both folders are one, so every JAR is the user's own and uninstall behaves exactly
     * as it always has.
     */
    private fun isUserInstalled(jarFile: File): Boolean =
        PluginInstallLocation.isUserInstalled(userPluginsDirectory, jarFile)

    // -------------------------------------------------------------------------
    // Install
    // -------------------------------------------------------------------------

    /**
     * Installs a new plugin from [sourceJar].
     *
     * Steps:
     * 1. Validates the JAR has a parseable manifest.
     * 2. Rejects if a plugin with the same ID is already loaded.
     * 3. Copies the JAR into the user's own plugins folder.
     * 4. Loads, initializes, and enables the plugin.
     *
     * Copying to the user's folder rather than over the distribution's own is what lets an installed
     * copy be updated without writing to the installation directory. A bundled plugin replaced this
     * way simply shadows it; the bundled copy stays intact underneath.
     */
    suspend fun installPlugin(sourceJar: File): Result<Unit, String> {
        val manifest = loader.getManifestFromJar(sourceJar)
            ?: return Err("The selected file is not a valid QTranslate plugin (no plugin.json found).")

        // Check for duplicate ID before touching the filesystem.
        // Read the registry under the mutex, then return early outside it —
        // withLock is not reentrant and we must not hold it across I/O.
        val alreadyInstalled = registry.mutex.withLock { registry.contains(manifest.id) }
        if (alreadyInstalled) {
            return Err(
                "A plugin with ID '${manifest.id}' is already installed. " +
                        "Uninstall it first, or use the Update function."
            )
        }

        return try {
            val destinationJar = File(userPluginsDirectory, sourceJar.name)
            withContext(Dispatchers.IO) {
                Files.copy(sourceJar.toPath(), destinationJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            val result = loader.loadPluginFromFile(destinationJar)
                ?: return Err("The plugin JAR was copied but could not be loaded. Check the logs for details.")

            initializeAndEnable(result)
            Ok(Unit)
        } catch (e: Exception) {
            logger.error("Failed to install plugin from ${sourceJar.path}", e)
            Err("An error occurred during installation: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Uninstall
    // -------------------------------------------------------------------------

    /**
     * Fully uninstalls a plugin: disables it, wipes its sandbox, removes its JAR,
     * and removes it from the disabled-IDs list in settings.
     *
     * A bundled plugin's JAR is left in place — it is not the user's to delete, and removing it
     * would leave an installed copy missing a component with no way to restore it short of
     * reinstalling. Everything else about the uninstall still happens, so the plugin stops
     * running, its data is purged, and the operator is told the JAR was kept.
     */
    suspend fun uninstallPlugin(pluginId: String) {
        val container = registry.mutex.withLock { registry.remove(pluginId) } ?: return
        logger.info("Uninstalling plugin '$pluginId'...")

        if (container.status == PluginStatus.ENABLED) {
            lifecycleHandler.disable(container)
        }
        // Its bundle would otherwise stay cached and keep answering for a plugin that is gone.
        lifecycleHandler.forgetLocalization(pluginId)
        purgePluginSandbox(pluginId)

        val currentDisabled = settingsRepository.loadDisabledPluginIds()
        settingsRepository.saveDisabledPluginIds(currentDisabled - pluginId)

        if (isUserInstalled(container.jarFile)) {
            runCatching { container.jarFile.delete() }.onFailure {
                logger.error("Failed to delete JAR file: ${container.jarFile.path}", it)
            }
            logger.info("Plugin '$pluginId' uninstalled successfully.")
        } else {
            logger.info(
                "Plugin '$pluginId' is bundled with this installation; kept its JAR at " +
                        container.jarFile.path
            )
        }
    }

    // -------------------------------------------------------------------------
    // JAR replacement resolution
    // -------------------------------------------------------------------------

    /**
     * The user confirmed a modified JAR is a legitimate update.
     * Keeps existing plugin data and re-initializes the plugin.
     */
    suspend fun resolveAsUpdate(pluginId: String) {
        val container = registry.mutex.withLock {
            registry.get(pluginId)?.takeIf { it.status == PluginStatus.AWAITING_VERIFICATION }
        } ?: return

        logger.info("User confirmed UPDATE for plugin '$pluginId'. Keeping existing data.")
        container.status = PluginStatus.DISABLED

        val result = LoadedPluginResult(
            plugin = container.plugin,
            manifest = container.manifest,
            jarFile = container.jarFile,
            jarHash = container.jarHash,
            classLoader = container.classLoader
        )
        initializeAndEnable(result)
    }

    /**
     * The user flagged a modified JAR as suspicious and wants a clean reinstall.
     * Wipes all plugin data before re-initializing.
     */
    suspend fun resolveAsCleanInstall(pluginId: String) {
        val container = registry.mutex.withLock {
            registry.get(pluginId)?.takeIf { it.status == PluginStatus.AWAITING_VERIFICATION }
        } ?: return

        logger.warn("User confirmed CLEAN RE-INSTALL for '$pluginId'. Wiping all plugin data.")
        purgePluginSandbox(pluginId)

        container.status = PluginStatus.DISABLED
        val result = LoadedPluginResult(
            plugin = container.plugin,
            manifest = container.manifest,
            jarFile = container.jarFile,
            jarHash = container.jarHash,
            classLoader = container.classLoader
        )
        initializeAndEnable(result)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Initializes and conditionally enables a newly loaded plugin.
     * Acquires the registry mutex for all registry writes.
     */
    private suspend fun initializeAndEnable(result: LoadedPluginResult) {
        val context = lifecycleHandler.createContext(result)
        val container = com.github.ahatem.qtranslate.core.plugin.registry.PluginContainer(
            plugin = result.plugin,
            manifest = result.manifest,
            context = context,
            jarFile = result.jarFile,
            jarHash = result.jarHash,
            classLoader = result.classLoader
        )

        val initialized = lifecycleHandler.initialize(container)
        if (initialized) {
            container.declaredServices = runCatching { container.plugin.getServices() }
                .onFailure { logger.warn("Could not inspect services for '${container.id}': ${it.message}") }
                .getOrDefault(emptyList())
        }

        registry.mutex.withLock { registry.put(container) }

        if (initialized) {
            val disabledIds = settingsRepository.loadDisabledPluginIds()
            if (container.id !in disabledIds) {
                lifecycleHandler.enable(container)
            }
        }
    }

    private suspend fun purgePluginSandbox(pluginId: String) {
        pluginKeyValueStore.deleteAllData(pluginId)
        val dataDir = File(appDataDirectory, "plugins_data/$pluginId")
        if (dataDir.exists()) dataDir.deleteRecursively()
        logger.info("Purged data sandbox for plugin '$pluginId'")
    }
}
