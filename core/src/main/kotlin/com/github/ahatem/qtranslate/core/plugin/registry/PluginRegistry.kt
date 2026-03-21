package com.github.ahatem.qtranslate.core.plugin.registry

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.plugin.LoadedPluginResult
import com.github.ahatem.qtranslate.core.plugin.PluginManifest
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import kotlinx.coroutines.sync.Mutex
import java.io.File

/**
 * The mutable runtime record for a single loaded plugin.
 *
 * This is an internal type — it is never exposed outside the plugin subsystem.
 * The UI always receives immutable [com.github.ahatem.qtranslate.core.plugin.PluginState]
 * snapshots produced by [PluginRegistry.snapshot].
 *
 * All mutations must be performed while holding [PluginRegistry]'s mutex.
 */
internal data class PluginContainer(
    val plugin: Plugin<*>,
    val manifest: PluginManifest,
    val context: PluginContext,
    val jarFile: File,
    val jarHash: String,
    val classLoader: ClassLoader,
    var status: PluginStatus = PluginStatus.DISABLED,
    var services: List<Service> = emptyList(),
    var lastError: PluginError? = null
) {
    val id: String get() = manifest.id
}

/**
 * Thread-safe in-memory registry of all loaded [PluginContainer]s.
 *
 * This is the single source of truth for which plugins are loaded and what
 * state they are in. All reads and writes go through the [mutex].
 *
 * Responsibilities:
 * - Storing and retrieving [PluginContainer]s by plugin ID
 * - Validating and deduplicating raw [LoadedPluginResult]s before initialization
 * - Producing immutable [com.github.ahatem.qtranslate.core.plugin.PluginState] snapshots for the UI
 * - Producing the active services map for service routing
 */
internal class PluginRegistry {

    val mutex = Mutex()
    private val containers = mutableMapOf<String, PluginContainer>()

    // -------------------------------------------------------------------------
    // Reads (call from within mutex.withLock)
    // -------------------------------------------------------------------------

    fun get(pluginId: String): PluginContainer? = containers[pluginId]

    fun getAll(): Collection<PluginContainer> = containers.values

    fun contains(pluginId: String): Boolean = containers.containsKey(pluginId)

    fun findByServiceId(serviceId: String): PluginContainer? =
        containers.values.find { c -> c.services.any { it.id == serviceId } }

    // -------------------------------------------------------------------------
    // Writes (call from within mutex.withLock)
    // -------------------------------------------------------------------------

    fun put(container: PluginContainer) {
        containers[container.id] = container
    }

    fun remove(pluginId: String): PluginContainer? = containers.remove(pluginId)

    fun clear() = containers.clear()

    // -------------------------------------------------------------------------
    // Snapshot production (safe to call from within or outside mutex)
    // -------------------------------------------------------------------------

    /**
     * Produces an immutable list of [com.github.ahatem.qtranslate.core.plugin.PluginState]
     * snapshots from the current registry contents.
     * Must be called while holding [mutex].
     */
    fun snapshot(): List<com.github.ahatem.qtranslate.core.plugin.PluginState> =
        containers.values.map { c ->
            com.github.ahatem.qtranslate.core.plugin.PluginState(
                manifest = c.manifest,
                status = c.status,
                jarPath = c.jarFile.absolutePath,
                services = c.services,
                lastError = c.lastError
            )
        }

    /**
     * Produces a map of service ID → [Service] for all currently enabled plugins.
     * Must be called while holding [mutex].
     */
    fun activeServices(): Map<String, Service> =
        containers.values
            .filter { it.status == PluginStatus.ENABLED }
            .flatMap { it.services }
            .associateBy { it.id }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Validates and deduplicates a raw list of [LoadedPluginResult]s from [com.github.ahatem.qtranslate.core.plugin.PluginLoader].
     *
     * Rules:
     * - Blank plugin IDs are rejected as [PluginError.InvalidManifest].
     * - If two JARs declare the same plugin ID, **both** are rejected as [PluginError.DuplicateId]
     *   and neither proceeds to initialization. The user must resolve the conflict manually.
     */
    fun validateAndFilter(rawPlugins: List<LoadedPluginResult>): PluginLoadResult {
        val seenIds = mutableMapOf<String, LoadedPluginResult>()
        val successful = mutableListOf<LoadedPluginResult>()
        val failed = mutableListOf<PluginError>()
        val skipped = mutableListOf<PluginError>()

        rawPlugins.forEach { result ->
            val id = result.manifest.id
            val jarName = result.jarFile.name

            if (id.isBlank()) {
                failed.add(
                    PluginError.InvalidManifest(
                        pluginId = jarName,
                        message = "Plugin ID cannot be blank in ${result.jarFile.absolutePath}",
                        jarPath = result.jarFile.absolutePath
                    )
                )
                return@forEach
            }

            val existing = seenIds[id]
            if (existing != null) {
                // Both the original and the duplicate are rejected
                skipped.add(PluginError.DuplicateId(id, existing.jarFile.name, jarName))
                skipped.add(PluginError.DuplicateId(id, jarName, existing.jarFile.name))
                successful.removeAll { it.manifest.id == id }
            } else {
                seenIds[id] = result
                successful.add(result)
            }
        }

        return PluginLoadResult(successful, failed, skipped)
    }
}
