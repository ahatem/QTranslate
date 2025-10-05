package com.github.ahatem.qtranslate.api

import com.github.michaelbull.result.Result

/**
 * The main entry point for a QTranslate plugin.
 *
 * Each plugin JAR must implement this interface and register it via Java's ServiceLoader
 * by creating a file in `resources/META-INF/services/com.github.ahatem.qtranslate.api.Plugin`
 * containing the fully qualified name of the implementing class.
 */
interface Plugin {
    /**
     * Called once when the plugin is loaded at application startup.
     * Use this to initialize resources, validate settings, or connect to APIs.
     *
     * @param context Provides access to core application functionality like logging and notifications.
     * @return Success if initialization succeeds, or a ServiceError if it fails.
     */
    fun initialize(context: PluginContext): Result<List<Service>, ServiceError>

    /**
     * Returns the plugin's settings configuration, if any.
     * The core will generate a UI based on the provided fields.
     */
    fun getSettingsClass(): Class<*>? = null

    /**
     * Called before the application shuts down.
     * Use this to release resources (e.g., close network connections, save state).
     */
    fun shutdown() {}
}

/**
 * Represents the metadata for a plugin, loaded from `resources/plugin.json`.
 * This file must exist in every plugin's resources.
 */
data class PluginManifest(
    val id: String,
    val author: String,
    val description: String,
    val entryPoint: String,
    val minApiVersion: String,
    val repositoryUrl: String? = null,
    val homepage: String? = null,
    val icon: String? = null
)

