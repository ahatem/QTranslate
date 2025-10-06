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
     * Called once when the plugin is first loaded at application startup.
     *
     * This method is the plugin's opportunity to initialize its internal resources,
     * such as creating HTTP clients, reading configuration from its data directory,
     * or validating secrets provided by the user.
     *
     * @param context Provides a sandboxed, secure context for the plugin to interact
     *                with the core application.
     * @return `Ok(Unit)` if initialization was successful. If initialization fails for any
     *         reason (e.g., invalid API key, network error), it should return an `Err`
     *         containing a descriptive `ServiceError`. A failed initialization will
     *         prevent this plugin from being activated.
     */
    fun initialize(context: PluginContext): Result<Unit, ServiceError>

    /**
     * Called by the core application *after* a successful `initialize()` call.
     *
     * This method should return a list of all functional `Service` instances
     * that this plugin provides.
     *
     * @return A list of services (e.g., `Translator`, `OCR`). The list can be empty if the
     *         plugin has no services to offer under the current configuration.
     */
    fun getServices(): List<Service>

    /**
     * Returns the Class of a simple data class whose properties are annotated with `@Setting`.
     * The core application will use this to automatically generate a settings UI for the plugin.
     *
     * @return The `.java` class of your settings object, or `null` if the plugin has no settings.
     */
    fun getSettingsClass(): Class<*>?

    /**
     * Called just before the application shuts down. This is the plugin's final chance
     * to clean up its resources, such as closing network connections or flushing data to disk.
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

