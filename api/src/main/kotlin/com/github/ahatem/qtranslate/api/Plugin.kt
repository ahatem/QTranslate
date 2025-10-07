package com.github.ahatem.qtranslate.api

import com.github.michaelbull.result.Result

/**
 * The main entry point for a QTranslate plugin, defining its complete lifecycle.
 *
 * Each plugin JAR must contain exactly one public, non-abstract class that implements this
 * interface. This class must be registered via Java's ServiceLoader mechanism by creating a file in
 * `resources/META-INF/services/com.github.ahatem.qtranslate.api.Plugin` which contains the
 * fully qualified name of the implementing class.
 *
 * ### Plugin Lifecycle
 *
 * The core application manages a precise lifecycle to ensure stability and efficiency.
 * Understanding this sequence is essential for creating a well-behaved plugin.
 *
 * 1.  **`initialize()`** -> `Result<Unit, ServiceError>`
 *     - **When:** Called **once** when the plugin JAR is first loaded by the application.
 *     - **Purpose:** Perform heavyweight, one-time setup that persists for the lifetime of the
 *       application (e.g., creating HTTP clients, reading persistent configuration, setting up
 *       database connections). A failure here prevents the plugin from ever becoming active.
 *
 * 2.  **`onEnable()`**
 *     - **When:** Called after a successful `initialize()`, and every time a user enables the
 *       plugin in the application settings.
 *     - **Purpose:** Make the plugin "active." This is the ideal place to create your `Service`
 *       instances, start any necessary background tasks, or register event listeners.
 *
 * 3.  **`getServices()`** -> `List<Service>`
 *     - **When:** Called by the core immediately after `onEnable()` completes successfully.
 *     - **Purpose:** To collect all the active `Service` instances (like `Translator`, `OCR`)
 *       that the plugin now provides. This method should be fast and synchronous, simply
 *       returning a list of objects that were prepared during `onEnable()`.
 *
 * 4.  **`onDisable()`**
 *     - **When:** Called whenever a user disables the plugin in the settings. It is also called
 *       before `shutdown()` if the plugin was active when the application is closing.
 *     - **Purpose:** Make the plugin "inactive." Release active resources, stop background tasks,
 *       and clear the list of services to reduce the application's memory and CPU footprint.
 *
 * 5.  **`shutdown()`**
 *     - **When:** Called **once** when the application is shutting down.
 *     - **Purpose:** Perform final, irreversible cleanup. This is where you should close network
 *       connections, flush caches to disk, and ensure all resources are released cleanly.
 *
 * @see PluginContext
 * @see Service
 */
interface Plugin {
    /**
     * Called once when the plugin is first loaded at application startup.
     *
     * This is a suspending function, allowing for non-blocking I/O operations like reading
     * configuration files or validating API keys over the network without freezing the application.
     * Use this method for heavyweight, one-time setup.
     *
     * @param context Provides a sandboxed, secure context for the plugin to interact
     *                with the core application's APIs (e.g., logging, secret storage).
     * @return `Ok(Unit)` if initialization was successful. If it fails for any
     *         reason (e.g., invalid API key, network error), it should return an `Err`
     *         containing a descriptive `ServiceError`. A failed initialization will
     *         prevent this plugin from being loaded further.
     */
    suspend fun initialize(context: PluginContext): Result<Unit, ServiceError>

    /**
     * Called after `initialize()` and whenever a user enables the plugin from the settings UI.
     *
     * This suspending function signals that the plugin should become "active." Use this to
     * instantiate your `Service` objects, start background tasks, or prepare resources
     * that are only needed while the plugin is running. This method can be called multiple
     * times during the application's lifetime without a restart.
     */
    suspend fun onEnable() {}

    /**
     * Called when a user disables the plugin from the settings UI, or before shutdown.
     *
     * This suspending function signals that the plugin should become "inactive." Use this to
     * stop all active processes and release memory-intensive resources. After this method
     * completes, `getServices()` should return an empty list.
     */
    suspend fun onDisable() {}

    /**
     * Called by the core application immediately after `onEnable()` to retrieve the list
     * of functional services this plugin provides.
     *
     * **This method must be a fast, synchronous operation.** It should simply return a
     * collection of the `Service` instances that were created and prepared during `onEnable()`.
     * Do not perform I/O or other long-running operations here.
     *
     * @return A `List` of services (e.g., `Translator`, `OCR`). Return an empty list if the
     *         plugin is disabled or has no services to offer.
     */
    fun getServices(): List<Service>

    /**
     * Returns the `Class` of a simple data class whose properties are annotated with `@Setting`.
     * The core application uses this to automatically generate a settings UI for the plugin.
     *
     * This is a synchronous, reflective operation that should return immediately.
     *
     * @return The `.java` class of your settings object, or `null` if the plugin has no settings.
     */
    fun getSettingsClass(): Class<*>?

    /**
     * Called once just before the application shuts down.
     *
     * This is the plugin's final chance to perform graceful, non-blocking cleanup, such as
     * closing network clients, flushing caches to disk, or saving state. This method is
     * guaranteed to be called only once in the plugin's lifecycle.
     */
    suspend fun shutdown() {}
}