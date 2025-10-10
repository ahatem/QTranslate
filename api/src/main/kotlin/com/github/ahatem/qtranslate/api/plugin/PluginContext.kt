package com.github.ahatem.qtranslate.api.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
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
     * A pre-configured Logger instance for the plugin to use.
     *
     * The logger is automatically scoped to the plugin, so all messages
     * will be tagged with the plugin's ID in the application logs.
     * This property is guaranteed to be available throughout the plugin's lifecycle.
     */
    val logger: Logger

    /**
     * Displays a non-intrusive notification to the user. This is the preferred way
     * to provide feedback for background operations or minor events.
     *
     * @param title The title of the notification.
     * @param body The main text of the notification.
     * @param type The severity level, which may affect the notification's appearance (e.g., color or icon).
     */
    fun notify(title: String, body: String, type: NotificationType = NotificationType.INFO)


    /**
     * Stores a private key-value pair scoped exclusively to this plugin.
     *
     * This is the ideal place for a plugin to store **operational data** that isn't directly
     * part of its user-facing settings form, such as authentication tokens, API usage counters,
     * cache timestamps, or other internal state.
     *
     * **A plugin is responsible for its own data security.** The core provides simple persistence;
     * if a value is highly sensitive, the plugin should encrypt it before calling this method.
     *
     * @param key A unique key for the data. Using a prefix (e.g., "cache_item_") is recommended.
     * @param value The string value to store.
     */
    fun storeValue(key: String, value: String)

    /**
     * Retrieves a private value that was previously stored by this plugin.
     *
     * @param key The unique key for the data.
     * @return The stored string value, or `null` if no value is found for the given key.
     */
    fun getValue(key: String): String?

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
}

