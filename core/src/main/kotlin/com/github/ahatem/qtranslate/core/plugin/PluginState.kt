package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.plugin.Service

/**
 * Represents the runtime status of a plugin, informing the core application how to manage it.
 */
enum class PluginStatus {
    /** The plugin is active and providing services. */
    ENABLED,

    /** The plugin is loaded and valid, but has been turned off by the user. */
    DISABLED,

    /** The plugin encountered a critical error and cannot be used. */
    FAILED,

    /**
     * The plugin's JAR file has been modified outside the application.
     * The system has paused this plugin and is waiting for user confirmation on how to proceed.
     */
    AWAITING_VERIFICATION
}

/**
 * An immutable snapshot of a single plugin's state.
 * This is the data model used by the UI to render the plugin management screen.
 */
data class PluginState(
    /** The metadata for the plugin, loaded from its `plugin.json` manifest. */
    val manifest: PluginManifest,

    /** The current runtime status of the plugin (e.g., enabled, disabled). */
    val status: PluginStatus,

    /**
     * The absolute path to the plugin's JAR file on disk.
     * This is exposed to the UI primarily for display purposes, giving users
     * context when resolving issues like the `AWAITING_VERIFICATION` state.
     */
    val jarPath: String,

    /** The list of services currently being provided by this plugin. Will be empty if not enabled. */
    val services: List<Service> = emptyList(),

    /** The last error encountered by this plugin, if any. */
    val lastError: PluginError? = null
) {
    val id: String get() = manifest.id
}

