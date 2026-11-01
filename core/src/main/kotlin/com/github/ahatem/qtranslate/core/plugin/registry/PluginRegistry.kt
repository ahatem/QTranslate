package com.github.ahatem.qtranslate.core.plugin.registry

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.plugin.PluginAssessment
import com.github.ahatem.qtranslate.core.plugin.PluginManifest
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The mutable runtime record for a single loaded plugin.
 *
 * This is an internal type — it is never exposed outside the plugin subsystem.
 * The UI always receives immutable [com.github.ahatem.qtranslate.core.plugin.PluginState]
 * snapshots produced by [PluginRegistry.snapshot].
 *
 * Map membership is guarded by [PluginRegistry]'s mutex; the mutable fields below are written
 * only from inside a per-plugin transition (serialized by
 * [com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginTransitionOwner]) and are volatile,
 * so the flow snapshot reads and the non-suspending service lookup see whole values.
 */
internal data class PluginContainer(
    val plugin: Plugin<*>,
    val manifest: PluginManifest,
    val context: PluginContext,
    val jarFile: File,
    val jarHash: String,
    val classLoader: ClassLoader,
    val snapshotFile: File? = null,
    @Volatile var status: PluginStatus = PluginStatus.DISABLED,
    @Volatile var services: List<Service> = emptyList(),
    @Volatile var declaredServices: List<Service> = emptyList(),
    @Volatile var lastError: PluginError? = null,
    /** Whether this JAR came from the distribution rather than the user's own plugins folder. */
    val bundled: Boolean = false,
    /**
     * Which copy of this plugin this is. Every plugin has exactly one until the user creates
     * more, but identifiers carry it from the start so their shape never changes.
     */
    val instanceId: String = ServiceId.DEFAULT_INSTANCE,
    /**
     * Set once [com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginLifecycleHandler.shutdown]
     * has finalized this container. Transitions are serialized per plugin, so the check and the
     * set cannot race; the flag is the fence that keeps a second teardown — from a racing
     * uninstall and application shutdown, say — from firing the plugin's final callbacks twice.
     */
    /**
     * Set atomically on entry to finalization; see the handler's shutdown for the contract.
     * Atomic, not a plain flag: application shutdown may finalize a straggler whose transition
     * outlived the settle bound at the same time the straggler's own completion tries to.
     */
    val shutdownComplete: AtomicBoolean = AtomicBoolean(false)
) {
    val id: String get() = manifest.id

    /** The identifier the host persists for [service]. Plugins never construct this. */
    fun serviceIdOf(service: Service): String = ServiceId.of(id, instanceId, service.key)
}

/**
 * Thread-safe in-memory registry of all loaded [PluginContainer]s.
 *
 * This is the single source of truth for which plugins are loaded and what
 * state they are in. Map reads and writes go through the [mutex]; the one exception is
 * [findByServiceId], which is safe unguarded because the maps are concurrent and container
 * service lists are volatile.
 *
 * Responsibilities:
 * - Storing and retrieving [PluginContainer]s by plugin ID
 * - Validating and deduplicating inert [PluginAssessment]s before initialization
 * - Producing immutable [com.github.ahatem.qtranslate.core.plugin.PluginState] snapshots for the UI
 * - Producing the active services map for service routing
 */
internal class PluginRegistry {

    val mutex = Mutex()

    /**
     * Concurrent so the single-map service lookup ([findByServiceId]) is safe for the one
     * non-suspending reader ([com.github.ahatem.qtranslate.core.plugin.PluginManager.getPluginClassLoaderForService],
     * called while rendering icons on the EDT). Compound reads and writes still go through
     * [mutex]: the map's weak consistency is not enough for atomic multi-map snapshots.
     */
    private val containers = ConcurrentHashMap<String, PluginContainer>()
    private val pending = ConcurrentHashMap<String, PendingPlugin>()

    /** A changed JAR awaiting consent; no provider or context is present. */
    data class PendingPlugin(
        val assessment: PluginAssessment,
        val bundled: Boolean,
        /**
         * The hash the user last approved for this plugin id. Persisted verbatim on shutdown so
         * the mismatch that paused this plugin is still detected after a restart — dropping it
         * would turn a detected modification into a first-run trust-on-first-use load.
         */
        val previousHash: String
    ) {
        val id: String get() = assessment.manifest.id
    }

    // -------------------------------------------------------------------------
    // Reads (call from within mutex.withLock)
    // -------------------------------------------------------------------------

    fun get(pluginId: String): PluginContainer? = containers[pluginId]

    fun getAll(): Collection<PluginContainer> = containers.values

    fun getPending(pluginId: String): PendingPlugin? = pending[pluginId]

    fun getAllPending(): Collection<PendingPlugin> = pending.values

    fun contains(pluginId: String): Boolean = containers.containsKey(pluginId) || pending.containsKey(pluginId)

    /**
     * The container providing [serviceId], or null.
     *
     * Safe without [mutex]: [containers] is a concurrent map and each container's service list
     * is volatile, so this read never tears and never trips a concurrent-modification check.
     */
    fun findByServiceId(serviceId: String): PluginContainer? =
        containers.values.find { c -> c.services.any { c.serviceIdOf(it) == serviceId } }

    // -------------------------------------------------------------------------
    // Writes (call from within mutex.withLock)
    // -------------------------------------------------------------------------

    fun put(container: PluginContainer) {
        containers[container.id] = container
    }

    fun putPending(value: PendingPlugin) { pending[value.id] = value }

    fun removePending(pluginId: String): PendingPlugin? = pending.remove(pluginId)

    fun remove(pluginId: String): PluginContainer? = containers.remove(pluginId)

    fun clear() { containers.clear(); pending.clear() }

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
                services = c.declaredServices,
                instanceId = c.instanceId,
                lastError = c.lastError,
                bundled = c.bundled
            )
        } + pending.values.map { p ->
            com.github.ahatem.qtranslate.core.plugin.PluginState(
                manifest = p.assessment.manifest,
                status = com.github.ahatem.qtranslate.core.plugin.PluginStatus.AWAITING_VERIFICATION,
                jarPath = p.assessment.jarFile.absolutePath,
                bundled = p.bundled
            )
        }

    /**
     * Produces a map of service ID → [Service] for all currently enabled plugins.
     * Must be called while holding [mutex].
     */
    fun activeServices(): Map<String, Service> =
        containers.values
            .filter { it.status == PluginStatus.ENABLED }
            .flatMap { container -> container.services.map { container.serviceIdOf(it) to it } }
            .sortedBy { it.first }
            .toMap()

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Validates and deduplicates assessed JARs from [com.github.ahatem.qtranslate.core.plugin.PluginLoader].
     *
     * Rules:
     * - Blank plugin IDs are rejected as [PluginError.InvalidManifest].
     * - If two JARs declare the same plugin ID, **both** are rejected as [PluginError.DuplicateId]
     *   and neither proceeds to initialization. The user must resolve the conflict manually.
     */
    fun validateAndFilter(rawPlugins: List<PluginAssessment>): PluginLoadResult {
        val seenIds = mutableMapOf<String, PluginAssessment>()
        val successful = mutableListOf<PluginAssessment>()
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
                result.discard()
                return@forEach
            }

            val existing = seenIds[id]
            if (existing != null) {
                // Both the original and the duplicate are rejected
                skipped.add(PluginError.DuplicateId(id, existing.jarFile.name, jarName))
                skipped.add(PluginError.DuplicateId(id, jarName, existing.jarFile.name))
                successful.removeAll { it.manifest.id == id }
                existing.discard()
                result.discard()
            } else {
                seenIds[id] = result
                successful.add(result)
            }
        }

        return PluginLoadResult(successful, failed, skipped)
    }
}
