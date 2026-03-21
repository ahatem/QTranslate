package com.github.ahatem.qtranslate.api.plugin

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * Defines the settings contract for a plugin. Use this sealed hierarchy as the
 * generic type parameter `S` in [Plugin].
 *
 * - **No settings**: `class MyPlugin : Plugin<PluginSettings.None>`
 * - **Has settings**: create a class extending [PluginSettings.Configurable] and
 *   annotate its properties with [@Setting][com.github.ahatem.qtranslate.api.settings.Setting].
 *
 * The core uses a `when` match on this sealed type to decide whether to render a
 * settings panel, eliminating the need for `Class<S>` reflection bridges and the
 * associated runtime guard logic.
 *
 * ### Example
 * ```kotlin
 * class MyPluginSettings : PluginSettings.Configurable() {
 *     @Setting(label = "API Key", type = SettingType.PASSWORD, order = 1, isRequired = true)
 *     var apiKey: String = ""
 *
 *     @Setting(label = "Enable cache", type = SettingType.BOOLEAN, order = 2)
 *     var cacheEnabled: Boolean = true
 * }
 * ```
 */
sealed class PluginSettings {

    /**
     * Use as the type parameter for plugins that have no user-configurable settings.
     * The core will not render a settings panel for such plugins.
     *
     * Example: `class MyPlugin : Plugin<PluginSettings.None>`
     */
    data object None : PluginSettings()

    /**
     * Base class for plugins that expose user-configurable settings.
     * Subclass this and annotate fields with
     * [@Setting][com.github.ahatem.qtranslate.api.settings.Setting] to have the core
     * automatically generate a settings UI via reflection.
     *
     * The core reflects only on subclasses of this class, so the reflection guard
     * (`if NoSettings skip`) is replaced by a compile-time sealed match.
     *
     * Example: `class MyPlugin : Plugin<MyPluginSettings>`
     */
    abstract class Configurable : PluginSettings()
}

/**
 * The main entry point for a QTranslate plugin, defining its complete lifecycle.
 *
 * Implement this interface to create a plugin, specifying your settings class as
 * the generic type `S`:
 * - **With settings**: `class MyPlugin : Plugin<MyPluginSettings>` where
 *   `MyPluginSettings : PluginSettings.Configurable()`
 * - **Without settings**: `class MyPlugin : Plugin<PluginSettings.None>`
 *
 * Register your implementation via Java's ServiceLoader by creating:
 * `resources/META-INF/services/com.github.ahatem.qtranslate.api.plugin.Plugin`
 * containing the fully qualified class name of your implementation.
 *
 * ### Threading Model
 * All lifecycle methods are called sequentially from a single dedicated background
 * thread managed by the core. Implementations **do not** need to be internally
 * thread-safe with respect to these calls.
 *
 * For any background work a plugin needs to initiate itself (polling, keep-alive pings,
 * background sync), use the [PluginContext.scope] coroutine scope provided during
 * [initialize]. It is automatically cancelled by the core when [onDisable] is called,
 * preventing coroutine leaks.
 *
 * ### Plugin Lifecycle
 *
 * ```
 * [App Start]
 *     │
 *     ▼
 * initialize(context)  ──── Err ──► [Plugin rejected, not loaded]
 *     │ Ok
 *     ▼
 * onEnable()  ──────────── Err ──► [Plugin disabled, user notified]
 *     │ Ok
 *     ▼
 * getServices()   ◄──── called immediately, must be fast & synchronous
 *     │
 *     ▼
 * [Plugin active — user can interact]
 *     │
 *     ├─ onSettingsChanged(settings) ◄── called on each user save
 *     │
 *     ▼
 * onDisable()   ◄── on user disable or before shutdown
 *     │
 *     ▼
 * shutdown()    ◄── once, on app close (final cleanup)
 * ```
 *
 * @param S The settings type. Must be either [PluginSettings.None] or a subclass
 *          of [PluginSettings.Configurable].
 * @see PluginContext
 * @see Service
 * @see PluginSettings
 */
interface Plugin<S : PluginSettings> {

    // -------------------------------------------------------------------------
    // Core Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Called **once** when the plugin is first loaded at application startup.
     *
     * Use this for heavyweight, one-time setup — creating HTTP clients, loading
     * native libraries, or reading initial configuration. Prefer lazy initialization
     * inside this method over static initializers.
     *
     * The provided [context] is your plugin's only sanctioned channel to the host
     * application. Store it as a property for use in subsequent lifecycle methods.
     *
     * @param context A sandboxed context providing logging, storage, notifications,
     *                and a managed [kotlinx.coroutines.CoroutineScope].
     * @return `Ok(Unit)` on success. An `Err` permanently prevents this plugin from
     *         loading — the user will be notified and [onEnable] will never be called.
     */
    suspend fun initialize(context: PluginContext): Result<Unit, ServiceError>

    /**
     * Called after [initialize] succeeds, and again whenever the user re-enables
     * the plugin from the settings panel.
     *
     * Use this to prepare or re-initialize your services. After a successful return,
     * [getServices] will be called immediately.
     *
     * @return `Ok(Unit)` if services are ready. An `Err` keeps the plugin disabled
     *         and is shown to the user as the reason.
     */
    suspend fun onEnable(): Result<Unit, ServiceError> = Ok(Unit)

    /**
     * Called when the user disables the plugin, or just before [shutdown] if the
     * plugin was still active.
     *
     * Release all active resources here (close connections, flush caches). The
     * [PluginContext.scope] is cancelled by the core immediately after this returns,
     * so any coroutines you launched in that scope will be cleaned up automatically.
     */
    suspend fun onDisable() {}

    /**
     * Called **once**, just before the application closes, for final irreversible cleanup.
     *
     * If the plugin was active, [onDisable] is guaranteed to have been called first.
     * Any exceptions thrown here are caught and logged by the core.
     */
    suspend fun shutdown() {}

    // -------------------------------------------------------------------------
    // Service Management
    // -------------------------------------------------------------------------

    /**
     * Returns the list of active [Service] instances this plugin provides.
     *
     * Called immediately after a successful [onEnable]. **This must be a fast,
     * synchronous operation** — simply return the service instances that were
     * prepared in [onEnable]. Do not perform I/O here.
     *
     * @return A list of ready [Service] instances. Return an empty list if the
     *         plugin is temporarily unable to provide services (the plugin will
     *         appear enabled but inactive in the UI).
     */
    fun getServices(): List<Service>

    // -------------------------------------------------------------------------
    // Settings Management
    // -------------------------------------------------------------------------

    /**
     * Returns the settings descriptor for this plugin.
     *
     * The core uses a `when` match on [PluginSettings]:
     * - [PluginSettings.None] → no settings panel is shown.
     * - [PluginSettings.Configurable] → the core reflects on the subclass properties
     *   annotated with [@Setting][com.github.ahatem.qtranslate.api.settings.Setting]
     *   and generates the settings UI automatically.
     *
     * For a plugin without settings, simply return `PluginSettings.None`:
     * ```kotlin
     * override fun getSettings(): PluginSettings.None = PluginSettings.None
     * ```
     *
     * For a plugin with settings, return your current settings instance:
     * ```kotlin
     * override fun getSettings(): MyPluginSettings = currentSettings
     * ```
     */
    fun getSettings(): S

    /**
     * Called by the core when the user saves changes to this plugin's settings panel.
     * Validate the new settings and apply them if valid.
     *
     * This method is **never called** for plugins where [getSettings] returns
     * [PluginSettings.None].
     *
     * @param settings The new, fully-populated settings instance.
     * @return `Ok(Unit)` if valid and applied. An `Err` rejects the save — the
     *         settings panel stays open and the error message is shown to the user.
     */
    suspend fun onSettingsChanged(settings: S): Result<Unit, ServiceError> = Ok(Unit)
}