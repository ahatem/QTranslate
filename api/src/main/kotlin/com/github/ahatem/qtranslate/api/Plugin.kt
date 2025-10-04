package com.github.ahatem.qtranslate.api

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
     * The plugin should use this to initialize its services.
     *
     * @param services Provides access to core application functionality like logging and notifications.
     * @return A list of all services this plugin provides. Return an empty list if the plugin
     *         is not configured correctly (e.g., missing API key) and cannot function.
     */
    fun initialize(services: CoreServices): List<Service>

    /**
     * Optional: Provide a settings class whose fields are annotated with @Setting.
     * The core application will use this to automatically generate a settings UI for the user.
     * If this returns null, the plugin is considered to have no user-configurable settings.
     */
    fun getSettingsClass(): Class<*>? = null

    /**
     * Called just before the application shuts down.
     * Use this method to clean up any resources, close network connections, or save state.
     */
    fun shutdown() {}
}

/**
 * Represents the metadata for a plugin, loaded from `resources/plugin.json`.
 * This file must exist in every plugin's resources.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val entryPoint: String,
    val minApiVersion: String,
    val repositoryUrl: String? = null,
    val homepage: String? = null
)