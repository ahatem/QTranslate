package com.github.ahatem.qtranslate.core.plugin.registry

/**
 * Describes a failure that occurred during plugin discovery, validation, or loading.
 * Used for structured error reporting in [PluginLoadResult] and stored on [PluginContainer]
 * for display in the plugin management UI.
 */
sealed class PluginError(
    open val pluginId: String,
    open val message: String,
    open val cause: Throwable?
) {
    /** The plugin JAR could not be read, parsed, or instantiated. */
    data class LoadFailure(
        override val pluginId: String,
        override val message: String,
        override val cause: Throwable?,
        val jarPath: String
    ) : PluginError(pluginId, message, cause)

    /** `Plugin.initialize()` returned an error or timed out. */
    data class InitializationFailure(
        override val pluginId: String,
        override val message: String,
        override val cause: Throwable?
    ) : PluginError(pluginId, message, cause)

    /** `Plugin.onEnable()` returned an error or timed out. */
    data class EnableFailure(
        override val pluginId: String,
        override val message: String,
        override val cause: Throwable?
    ) : PluginError(pluginId, message, cause)

    /** `Plugin.onDisable()` threw an unexpected exception. */
    data class DisableFailure(
        override val pluginId: String,
        override val message: String,
        override val cause: Throwable?
    ) : PluginError(pluginId, message, cause)

    /**
     * Two JARs in the plugins directory declared the same plugin ID.
     * Both are rejected — the user must resolve the conflict manually.
     */
    data class DuplicateId(
        override val pluginId: String,
        val existingJar: String,
        val duplicateJar: String
    ) : PluginError(pluginId, "Duplicate plugin ID '$pluginId' found in '$existingJar' and '$duplicateJar'", null)

    /** The `plugin.json` manifest is missing, malformed, or has a blank ID. */
    data class InvalidManifest(
        override val pluginId: String,
        override val message: String,
        val jarPath: String
    ) : PluginError(pluginId, message, null)
}

/**
 * The aggregated result of a full plugin discovery pass over the plugins directory.
 * Returned by [com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry.validateAndFilter].
 */
data class PluginLoadResult(
    /** Plugins that passed all validation checks and are ready for initialization. */
    val successful: List<com.github.ahatem.qtranslate.core.plugin.LoadedPluginResult>,

    /** Plugins that failed a hard validation check (blank ID, JAR unreadable, etc.). */
    val failed: List<PluginError>,

    /**
     * Plugins that were skipped due to non-fatal issues (duplicate IDs, API incompatibility).
     * These are distinct from failures — the JAR is valid but was intentionally excluded.
     */
    val skipped: List<PluginError>
) {
    val totalAttempted: Int = successful.size + failed.size + skipped.size
    val successRate: Float = if (totalAttempted > 0) successful.size.toFloat() / totalAttempted else 0f
}