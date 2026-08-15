package com.github.ahatem.qtranslate.api.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Provides a plugin with a sandboxed, secure context to interact with the core application.
 *
 * An instance is passed to the plugin during [Plugin.initialize] and should be stored
 * as a property for use throughout the plugin's lifetime. This is the **only** sanctioned
 * channel through which a plugin communicates with the host — plugins must not access
 * application internals through any other means.
 *
 * Each plugin receives its own unique, isolated instance. Actions taken through this
 * context (storage, notifications, file I/O) are scoped to the plugin and cannot
 * affect other plugins.
 *
 * @see Plugin
 */
interface PluginContext {

    // -------------------------------------------------------------------------
    // Core Resources
    // -------------------------------------------------------------------------

    /**
     * A pre-configured [Logger] scoped to this plugin.
     *
     * All messages emitted through this logger are automatically tagged with the
     * plugin's ID, making them easy to filter in the application's log output.
     * Thread-safe; may be called from any thread or coroutine.
     */
    val logger: Logger

    /**
     * A [CoroutineScope] managed by the core application, tied to this plugin's lifecycle.
     *
     * Use this scope to launch any background coroutines your plugin needs (polling,
     * keep-alive pings, background sync, etc.). The core **automatically cancels** this
     * scope when [Plugin.onDisable] returns, ensuring all launched coroutines are
     * cleaned up without the plugin needing to manage cancellation manually.
     *
     * ### Do not use `GlobalScope`
     * Launching coroutines on `GlobalScope` from a plugin creates unmanaged coroutine
     * leaks that survive plugin disable/enable cycles and cannot be tracked by the core.
     * Always use this scope.
     *
     * ### Dispatcher
     * The scope uses [kotlinx.coroutines.Dispatchers.IO] by default. Switch dispatchers
     * inside your coroutines as needed (e.g. `withContext(Dispatchers.Default)` for CPU work).
     *
     * ### Example
     * ```kotlin
     * override suspend fun onEnable(): Result<Unit, ServiceError> {
     *     context.scope.launch {
     *         while (isActive) {
     *             refreshTokenIfNeeded()
     *             delay(30.minutes)
     *         }
     *     }
     *     return Ok(Unit)
     * }
     * ```
     */
    val scope: CoroutineScope

    // -------------------------------------------------------------------------
    // User Notifications
    // -------------------------------------------------------------------------

    /**
     * Displays a brief, non-intrusive notification to the user.
     *
     * This is the preferred channel for communicating background operation outcomes
     * (e.g. "Cache refreshed", "API key expired"). For critical errors that require
     * user action, prefer returning an `Err` from the relevant lifecycle method instead.
     *
     * **Rate limiting**: The core applies best-effort debouncing per plugin. Avoid
     * calling this in tight loops — repeated notifications will be coalesced or dropped.
     *
     * Both strings are [DisplayText], so a plugin shipping a `localization/` bundle has its
     * notifications appear in the user's language. Passing raw strings would have made these
     * the one piece of plugin text that could never be translated.
     *
     * @param title Short title for the notification (aim for ≤ 5 words).
     * @param body  The main message, providing context or a suggested action.
     * @param type  Severity, which affects appearance. Defaults to [NotificationType.INFO].
     */
    suspend fun notify(title: DisplayText, body: DisplayText, type: NotificationType = NotificationType.INFO)

    // -------------------------------------------------------------------------
    // Storage
    // -------------------------------------------------------------------------

    /**
     * Typed persistence, scoped to this plugin and to the instance the user created.
     *
     * Covers everything from user-facing settings to operational state such as cache
     * timestamps and usage counters. An earlier untyped `storeValue`/`getValue` pair existed
     * alongside this and has been removed: it was the same storage with the same scoping, only
     * stringly-typed, which left two ways to do one thing and every plugin re-parsing values.
     *
     * For credentials use [secrets] instead.
     */
    val settings: SettingsStore

    /**
     * Credentials — API keys, tokens, anything damaging to leak.
     *
     * Separate from [settings] so the host can protect them using the platform keychain where
     * one exists, and so sensitive values are visible as such in plugin code rather than
     * blending into ordinary configuration.
     */
    val secrets: SecretStore

    // -------------------------------------------------------------------------
    // File System
    // -------------------------------------------------------------------------

    /**
     * Returns a dedicated, private directory on the file system for this plugin's
     * exclusive use (caching, local databases, downloaded assets, etc.).
     *
     * The directory is guaranteed to exist and be writable when this method is called.
     * Its location is managed entirely by the core and is guaranteed to be unique
     * to this plugin.
     *
     * **Sandbox contract**: Plugins **must not** read from or write to any path
     * outside the directory returned by this method. Violations may result in the
     * plugin being disabled by the core.
     *
     * @return A [File] pointing to the root of this plugin's sandboxed data directory.
     */
    fun getPluginDataDirectory(): File
}