package com.github.ahatem.qtranslate.api

import java.io.File

/**
 * Provides a plugin with a sandboxed, secure context to interact with the core application.
 *
 * An instance of this interface is passed to a plugin during its `initialize` phase.
 * It is the **only** channel through which a plugin should communicate with the host application.
 * Each plugin receives its own unique, scoped instance, ensuring its actions and data
 * are isolated from other plugins.
 *
 * @see Plugin
 */
interface PluginContext {

    /**
     * Displays a non-intrusive notification to the user. This is the preferred way
     * to provide feedback for background operations or minor events.
     *
     * @param message The text content of the notification.
     * @param type The severity level, which may affect the notification's appearance (e.g., color or icon).
     */
    fun notify(message: String, type: NotificationType = NotificationType.INFO)

    /**
     * Logs an error for debugging and reporting. The core application will handle
     * routing this error to a log file and/or displaying it in a dedicated error console.
     *
     * Use this for unexpected exceptions during plugin operation. For user-facing errors
     * that result from a service call (e.g., invalid API key), it is better to return a
     * specific `ServiceError` from the service method.
     *
     * @param error The `Throwable` that occurred.
     * @param context An optional string providing more information about the operation that failed.
     */
    fun logError(error: Throwable, context: String? = null)

    /**
     * Logs an informational message, typically for debugging purposes.
     * These messages may be hidden in a production environment but visible in a debug mode.
     *
     * @param message The informational message to log.
     */
    fun logInfo(message: String)

    /**
     * Retrieves a secret (e.g., an API key) from the application's secure storage,
     * scoped exclusively to this plugin. A plugin cannot access secrets belonging to another plugin.
     *
     * @param key The unique key for the secret. It is strongly recommended that this key
     *            matches the property name in the plugin's `@Setting`-annotated settings class.
     * @return The stored secret value as a `String`, or `null` if no value is found for the given key.
     */
    fun getSecret(key: String): String?

    /**
     * Securely stores a secret for this plugin. The core application is responsible for
     * using the appropriate platform-specific secure storage mechanism
     * (e.g., Windows Credential Vault, macOS Keychain, Linux Secret Service).
     *
     * @param key The unique key under which to store the secret.
     * @param value The secret value to store.
     */
    fun storeSecret(key: String, value: String)

    /**
     * Returns a dedicated, private directory on the file system that this plugin
     * can safely use for caching, configuration, or other data storage needs.
     *
     * The core application guarantees that this directory is unique to this plugin.
     * For security and stability, plugins **MUST NOT** attempt to write files
     * outside the directory provided by this method.
     *
     * @return A `File` object representing the root of the plugin's sandboxed data directory.
     */
    fun getPluginDataDirectory(): File

    /**
     * The user's currently selected source language in the main application UI.
     * This is a read-only snapshot of the application's state at the time of access.
     *
     * Useful for services that can adapt their behavior, such as an OCR plugin
     * using this as a hint for language detection.
     */
    val currentInputLanguage: LanguageCode

    /**
     * The user's currently selected target language in the main application UI.
     * This is a read-only snapshot of the application's state at the time of access.
     */
    val currentOutputLanguage: LanguageCode
}

/**
 * Defines the severity level of a notification shown to the user.
 */
enum class NotificationType {
    /** General information. */
    INFO,

    /** A potential issue that does not prevent functionality. */
    WARNING,

    /** An error that has occurred. */
    ERROR,

    /** Confirmation of a successful operation. */
    SUCCESS
}