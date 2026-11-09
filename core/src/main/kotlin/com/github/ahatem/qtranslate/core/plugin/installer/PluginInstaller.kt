package com.github.ahatem.qtranslate.core.plugin.installer

import com.github.ahatem.qtranslate.core.plugin.LoadedPluginResult
import com.github.ahatem.qtranslate.core.plugin.PluginAssessment
import com.github.ahatem.qtranslate.core.plugin.PluginLoader
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginLifecycleHandler
import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginTransitionOwner
import com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * Handles plugin installation, uninstallation, and JAR-replacement resolution.
 *
 * This class deals exclusively with the filesystem side of plugin management
 * (copying JARs, deleting data directories) and delegates runtime state changes
 * to [PluginLifecycleHandler] and [PluginRegistry].
 *
 * Every public method runs its whole operation inside one [PluginTransitionOwner] transition
 * for the plugin it touches: the duplicate check, the copy, the load, the registry update and
 * the first enable are one atomic transition, so a racing enable, disable, uninstall, resolve,
 * or application shutdown for the same plugin id can never interleave with it. Callers must NOT
 * already hold that plugin's transition — these methods are the transition.
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
    private val transitions: PluginTransitionOwner,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("PluginInstaller")
    private val loader = PluginLoader(loggerFactory.getLogger("PluginLoader"), File(appDataDirectory, "plugin-snapshots"))

    /**
     * Backstop for [PluginManager]: a bundled JAR is not user-owned and must survive an uninstall.
     * In a portable distribution both folders are one, so every JAR is the user's own.
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
     * 1. Inertly assesses the JAR (no provider code runs).
     * 2. Under one transition: rejects a duplicate ID or filename, copies the approved snapshot
     *    into the user's own plugins folder, constructs the provider from that snapshot, and
     *    initializes and enables it.
     *
     * Copying to the user's folder rather than over the distribution's own is what lets an installed
     * copy be updated without writing to the installation directory. A bundled plugin replaced this
     * way simply shadows it; the bundled copy stays intact underneath.
     */
    suspend fun installPlugin(sourceJar: File): Result<Unit, String> {
        val assessment = withContext(Dispatchers.IO) { loader.assessFile(sourceJar) }
            ?: return Err("The selected file is not a valid QTranslate plugin.")
        val manifest = assessment.manifest

        return try {
            val installed = transitions.transition(manifest.id) { installAssessed(assessment, sourceJar.name) }
            if (installed == null) {
                // Refused by the shutdown gate: the inert snapshot was the only thing this call
                // created, and it must not outlive the refusal.
                assessment.discard()
                Err("Installation of '${manifest.id}' was refused: the plugin system is shutting down.")
            } else {
                installed
            }
        } catch (e: CancellationException) {
            // Cancelled while queued for the slot: the snapshot is ours and must go, and the
            // caller's cancelled state must not be disguised as an install failure.
            assessment.discard()
            throw e
        } catch (e: Exception) {
            assessment.discard()
            logger.error("Failed to install plugin from ${sourceJar.path} (${e::class.simpleName})")
            // The exception may originate in provider code, so its message is not surfaced to
            // the user or the log; the failure class is the useful diagnostic.
            Err("An error occurred during installation (${e::class.simpleName}).")
        }
    }

    /**
     * The install transition. Runs under the plugin's transition slot, so the duplicate checks
     * and the registry write cannot interleave with another transition of the same plugin.
     */
    private suspend fun installAssessed(assessment: PluginAssessment, sourceJarName: String): Result<Unit, String> {
        val manifest = assessment.manifest

        if (registry.mutex.withLock { registry.contains(manifest.id) }) {
            assessment.discard()
            return Err(
                "A plugin with ID '${manifest.id}' is already installed. " +
                        "Uninstall it first, or use the Update function."
            )
        }

        val destinationJar = File(userPluginsDirectory, sourceJarName)
        // Fail closed on a symlinked destination: the copy must land on a path the user's own
        // folder actually owns, not be written through a link planted there.
        if (destinationJar.exists() || Files.isSymbolicLink(destinationJar.toPath())) {
            assessment.discard()
            return Err("A plugin JAR with this filename is already installed.")
        }

        // The destination is a copy of the assessed snapshot, so the bytes executed are
        // exactly the bytes that were approved — a change to the source JAR between
        // assessment and install cannot alter what gets constructed.
        withContext(Dispatchers.IO) {
            Files.copy(assessment.snapshotFile.toPath(), destinationJar.toPath())
        }

        val result = loader.loadApproved(assessment.copy(jarFile = destinationJar))
            ?: run {
                // Nothing loadable was ever approved: remove the copy we just made so a
                // broken artifact does not fail discovery on every subsequent launch.
                assessment.discard()
                withContext(Dispatchers.IO) { runCatching { destinationJar.delete() } }
                return Err("The plugin JAR was copied but could not be loaded. Check the logs for details.")
            }

        return try {
            initializeAndEnable(result, bundled = false)
            Ok(Unit)
        } catch (e: Exception) {
            // A provider was constructed but the install could not finish, and the container
            // never entered the registry: this call owns the loader and the snapshot and must
            // release them here, or they leak for the rest of the process.
            release(result)
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Uninstall
    // -------------------------------------------------------------------------

    /**
     * Uninstalls a user-installed plugin: disables it, wipes its sandbox, removes its JAR,
     * and removes it from the disabled-IDs list in settings.
     *
     * The whole teardown runs under the plugin's transition, and the registry removal is the
     * *last* step: until the plugin's callbacks have run and its class loader and snapshot are
     * released, the registry still reports it, so nothing new can be routed to it and nothing
     * can transition it underneath the teardown. Only reachable for a plugin from the user's own
     * folder — [PluginManager] refuses a bundled one before it gets here. The checks below stay
     * as backstops rather than assumptions.
     */
    suspend fun uninstallPlugin(pluginId: String) {
        transitions.transition(pluginId) { uninstallTransition(pluginId) }
            ?: logger.warn("Ignored uninstall of '$pluginId': the plugin system is shutting down")
    }

    private suspend fun uninstallTransition(pluginId: String) {
        val pending = registry.mutex.withLock { registry.removePending(pluginId) }
        if (pending != null) {
            pending.assessment.discard()
            purgePluginSandbox(pluginId)
            settingsRepository.updateDisabledPluginIds { it - pluginId }.getError()?.let {
                logger.error("Failed to remove disabled state for plugin '$pluginId'")
            }
            if (isUserInstalled(pending.assessment.jarFile)) pending.assessment.jarFile.delete()
            return
        }
        val container = registry.mutex.withLock { registry.get(pluginId) } ?: return
        logger.info("Uninstalling plugin '$pluginId'...")

        if (container.status == PluginStatus.ENABLED) {
            lifecycleHandler.disable(container)
        }
        lifecycleHandler.shutdown(container)
        // Its bundle would otherwise stay cached and keep answering for a plugin that is gone.
        lifecycleHandler.forgetLocalization(pluginId)
        purgePluginSandbox(pluginId)

        settingsRepository.updateDisabledPluginIds { it - pluginId }.getError()?.let {
            logger.error("Failed to remove disabled state for plugin '$pluginId'")
        }

        registry.mutex.withLock { registry.remove(pluginId) }

        // A JAR the distribution owns is not the user's to delete: removing it would leave an
        // installed copy missing a component with no way to restore it.
        if (!isUserInstalled(container.jarFile)) {
            logger.warn("Kept plugin JAR at ${container.jarFile.path}; it is not in the user's own folder.")
            return
        }
        runCatching { container.jarFile.delete() }.onFailure {
            logger.error("Failed to delete JAR file: ${container.jarFile.path} (${it::class.simpleName})")
        }
        logger.info("Plugin '$pluginId' uninstalled successfully.")
    }

    // -------------------------------------------------------------------------
    // JAR replacement resolution
    // -------------------------------------------------------------------------

    /**
     * The user confirmed a modified JAR is a legitimate update.
     * Keeps existing plugin data and re-initializes the plugin.
     */
    suspend fun resolveAsUpdate(pluginId: String) {
        transitions.transition(pluginId) {
            val pending = registry.mutex.withLock { registry.getPending(pluginId) } ?: return@transition

            logger.info("User confirmed UPDATE for plugin '$pluginId'. Keeping existing data.")
            val result = loader.loadApproved(pending.assessment)
            if (result == null) {
                // The pending row stays: the changed JAR is still on disk and still unverified,
                // so the user can retry or choose a clean install. The loader has logged the
                // failure, and the preserved baseline re-detects the change on the next launch.
                logger.warn("Cannot load the pending JAR for '$pluginId'; it stays awaiting verification")
                return@transition
            }
            registry.mutex.withLock { if (registry.getPending(pluginId) === pending) registry.removePending(pluginId) }
            initializeAndEnable(result, pending.bundled)
        } ?: logger.warn("Ignored update resolution for '$pluginId': the plugin system is shutting down")
    }

    /**
     * The user flagged a modified JAR as suspicious and wants a clean reinstall.
     * Wipes all plugin data before re-initializing.
     */
    suspend fun resolveAsCleanInstall(pluginId: String) {
        transitions.transition(pluginId) {
            val pending = registry.mutex.withLock { registry.getPending(pluginId) } ?: return@transition

            logger.warn("User confirmed CLEAN RE-INSTALL for '$pluginId'. Wiping all plugin data.")
            val result = loader.loadApproved(pending.assessment)
            if (result == null) {
                logger.warn("Cannot load the pending JAR for '$pluginId'; it stays awaiting verification")
                return@transition
            }
            purgePluginSandbox(pluginId)
            registry.mutex.withLock { if (registry.getPending(pluginId) === pending) registry.removePending(pluginId) }
            initializeAndEnable(result, pending.bundled)
        } ?: logger.warn("Ignored clean-install resolution for '$pluginId': the plugin system is shutting down")
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Initializes and conditionally enables a newly loaded plugin.
     * Acquires the registry mutex for all registry writes.
     *
     * Callers must hold this plugin's transition: the container enters the registry here and
     * its first enable runs here, both of which must be serialized against every other
     * transition of the same plugin. [bundled] is carried from the discovery or pending record
     * so an accepted update of a bundled plugin does not suddenly become uninstallable.
     */
    private suspend fun initializeAndEnable(result: LoadedPluginResult, bundled: Boolean) {
        val context = lifecycleHandler.createContext(result)
        val container = com.github.ahatem.qtranslate.core.plugin.registry.PluginContainer(
            plugin = result.plugin,
            manifest = result.manifest,
            context = context,
            jarFile = result.jarFile,
            jarHash = result.jarHash,
            classLoader = result.classLoader,
            snapshotFile = result.snapshotFile,
            bundled = bundled
        )

        val initialized = lifecycleHandler.initialize(container)
        if (initialized) {
            // A service getter is documented as fast, but it is still plugin code: bound it so a
            // blocking implementation cannot wedge the transition past shutdown's settle bound.
            container.declaredServices = runCatching {
                withTimeoutOrNull(GET_SERVICES_TIMEOUT_MS) { container.plugin.getServices() }
            }.onFailure {
                logger.warn("Could not inspect services for '${container.id}' (${it::class.simpleName})")
            }
                .getOrNull()
                ?: emptyList()
        }

        registry.mutex.withLock { registry.put(container) }

        // Shutdown may have timed out waiting for this transition (see the owner's settle bound):
        // the registry is already cleared, so this call finalizes the late registration itself.
        if (transitions.isShutdown()) {
            lifecycleHandler.shutdown(container)
            registry.mutex.withLock { registry.remove(container.id) }
            return
        }

        if (initialized) {
            val disabledIds = settingsRepository.loadDisabledPluginIds()
            if (container.id !in disabledIds) {
                lifecycleHandler.enable(container)
            }
        }
    }

    private companion object {
        /** Same ceiling as one enable callback; a getter should normally be far below it. */
        const val GET_SERVICES_TIMEOUT_MS = 15_000L
    }

    /** Releases a constructed-but-unregistered provider: its loader and its snapshot. */
    private fun release(result: LoadedPluginResult) {
        runCatching { (result.classLoader as? URLClassLoader)?.close() }
        result.snapshotFile?.let { snapshot ->
            snapshot.setWritable(true)
            if (!snapshot.delete()) {
                logger.warn("Could not remove plugin snapshot ${snapshot.absolutePath}")
            }
        }
    }

    private suspend fun purgePluginSandbox(pluginId: String) {
        // Settings and secrets are separate DataStore files; both must go, or an uninstall
        // would leave the credentials of a removed plugin on disk (and a reinstall of a
        // same-id plugin would silently inherit them). Disabling, failed loads and reloads
        // never call this — only the uninstall and pending-installer removal paths do.
        pluginKeyValueStore.deleteAllData(pluginId)
        pluginKeyValueStore.deleteAllData("$pluginId.secrets")
        val dataDir = File(appDataDirectory, "plugins_data/$pluginId")
        if (dataDir.exists()) dataDir.deleteRecursively()
        logger.info("Purged data sandbox for plugin '$pluginId'")
    }
}
