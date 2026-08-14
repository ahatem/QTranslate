package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.plugin.registry.PluginError
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId

/**
 * Represents the runtime status of a loaded plugin.
 */
enum class PluginStatus {
    /** The plugin is active and providing services to the application. */
    ENABLED,

    /** The plugin is loaded and valid, but has been turned off by the user. */
    DISABLED,

    /** The plugin encountered a critical error and cannot be used until reinstalled. */
    FAILED,

    /**
     * The plugin's JAR file has been modified since the last run.
     * The core has paused this plugin and is waiting for the user to confirm
     * whether to treat the change as a legitimate update or a security concern.
     */
    AWAITING_VERIFICATION
}

/**
 * An immutable snapshot of a single plugin's runtime state.
 * This is the data model consumed by the UI to render the plugin management screen.
 * It is produced by [com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry]
 * and should never be mutated — a new snapshot is emitted whenever state changes.
 */
data class PluginState(
    /** Metadata loaded from the plugin's `plugin.json` manifest. */
    val manifest: PluginManifest,

    /** The current runtime status of this plugin. */
    val status: PluginStatus,

    /**
     * Absolute path to the plugin's JAR file on disk.
     * Exposed for display in the UI, particularly when the user needs to
     * locate the file to resolve an [PluginStatus.AWAITING_VERIFICATION] state.
     */
    val jarPath: String,

    /** Services declared by this plugin, retained while the plugin is disabled. */
    val services: List<Service> = emptyList(),

    /**
     * Which instance of the plugin this is.
     *
     * Only one exists per plugin today, but the runtime service id is composed from it, and the
     * UI needs that id to key icon caches and to map a selection back to its plugin.
     */
    val instanceId: String = ServiceId.DEFAULT_INSTANCE,

    /** The last error encountered by this plugin, or `null` if healthy. */
    val lastError: PluginError? = null
) {
    val id: String get() = manifest.id

    /**
     * The id the host registers [service] under.
     *
     * Services carry a key that is unique only within their plugin, so the identifier the rest of
     * the application uses has to be composed. Reproduced here rather than passed down because
     * the pieces are all present and a parallel list would be one more thing to keep in step.
     */
    fun serviceIdOf(service: Service): String = ServiceId.of(id, instanceId, service.key)
}
