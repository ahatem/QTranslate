package com.github.ahatem.qtranslate.api.plugin

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * The main entry point for a QTranslate plugin, defining its complete lifecycle.
 *
 * Each plugin must implement this interface, specifying its settings class as the
 * generic type `S`. For plugins without settings, use the `NoSettings` marker object.
 *
 * - With settings: `class MyPlugin : Plugin<MySettingsClass>`
 * - Without settings: `class MyPlugin : Plugin<NoSettings>`
 *
 * This class must be registered via Java's ServiceLoader mechanism by creating a file in
 * `resources/META-INF/services/com.github.ahatem.qtranslate.api.plugin.Plugin` which contains the
 * fully qualified name of the implementing class.
 *
 * ### Threading Model
 * All methods on this interface are guaranteed to be called sequentially from a dedicated
 * background thread by the core application. Implementations **do not** need to be
 * internally thread-safe.
 *
 * ### Plugin Lifecycle
 * The core application manages a precise lifecycle to ensure stability and efficiency.
 *
 * 1.  **`initialize()`**: Called **once** when the plugin is first loaded. Used for heavyweight,
 *     one-time setup. A failure here prevents the plugin from loading further.
 *
 * 2.  **`onEnable()`**: Called after `initialize()` and whenever a user enables the plugin. This
 *     method prepares the plugin's services. A failure here will keep the plugin disabled.
 *
 * 3.  **`getServices()`**: Called immediately after a successful `onEnable()`. This method must
 *     be a fast, synchronous operation that returns the active services.
 *
 * 4.  **`onDisable()`**: Called when a user disables the plugin or before shutdown. This method
 *     should release all active resources.
 *
 * 5.  **`shutdown()`**: Called **once** when the application is closing. Used for final,
 *     irreversible cleanup.
 *
 * @param S The type of the data class used for this plugin's settings.
 * @see PluginContext
 * @see Service
 * @see NoSettings
 */
interface Plugin<S : Any> {
    // --- Core Lifecycle Methods ---

    /**
     * Called once when the plugin is first loaded at application startup.
     *
     * This is a suspending function for non-blocking I/O. Use this method for
     * heavyweight, one-time setup (e.g., creating HTTP clients).
     *
     * @param context Provides a sandboxed, secure context for the plugin to interact with the core.
     * @return `Ok(Unit)` if initialization was successful. An `Err` will prevent the plugin from
     *         being loaded further.
     */
    suspend fun initialize(context: PluginContext): Result<Unit, ServiceError>

    /**
     * Called after a successful `initialize()` and whenever a user enables the plugin.
     *
     * This method signals that the plugin should become "active" by preparing its services.
     * If this method returns an error, the plugin will be considered disabled.
     *
     * @return `Ok(Unit)` if the plugin was enabled successfully, or an `Err` otherwise.
     */
    suspend fun onEnable(): Result<Unit, ServiceError> = Ok(Unit)

    /**
     * Called when a user disables the plugin, or before shutdown if the plugin was active.
     *
     * This method signals that the plugin should become "inactive." It should stop all active
     * processes and clear its list of services. The core will log any exceptions.
     */
    suspend fun onDisable() {}

    /**
     * Called once just before the application shuts down for final, irreversible cleanup.
     *
     * This is the plugin's final chance to perform graceful cleanup. The core will log any
     * exceptions thrown by this method.
     */
    suspend fun shutdown() {}


    // --- Service Management ---

    /**
     * Called by the core immediately after a successful `onEnable()` to retrieve the list of
     * functional services this plugin provides.
     *
     * **This method must be a fast, synchronous operation.** It should simply return a
     * collection of the `Service` instances that were prepared during `onEnable()`.
     *
     * @return A `List` of services.
     */
    fun getServices(): List<Service>


    // --- Settings Management ---

    /**
     * Returns the `Class` of the settings data class for this plugin. This is the essential
     * bridge that allows the core's dynamic runtime to interact with the plugin's type-safe
     * settings model.
     *
     * @return The `.java` class of your settings object. For plugins with no settings, this
     *         must return `NoSettings::class.java`.
     */
    fun getSettingsClass(): Class<S>

    /**
     * Called by the core after the user saves changes to this plugin's settings.
     * This method gives the plugin a chance to validate and apply the new settings.
     *
     * This method is **never** called for plugins that implement `Plugin<NoSettings>`.
     *
     * @param settings The new, fully-populated, type-safe instance of the settings data class.
     * @return `Ok(Unit)` if the settings were successfully validated and applied. Returning an
     *         `Err` will signal to the core that the settings were rejected, allowing the UI
     *         to display the error and remain open for the user to correct.
     */
    suspend fun onSettingsChanged(settings: S): Result<Unit, ServiceError> = Ok(Unit)
}