package com.github.ahatem.qtranslate.api

/**
 * Services provided by the core application to plugins.
 * This is the ONLY way plugins should interact with the core.
 */
interface CoreServices {
    /**
     * Show a notification to the user.
     *
     * @param message The message to display
     * @param type Notification severity
     */
    fun notify(message: String, type: NotificationType = NotificationType.INFO)

    /**
     * Log an error. The core will handle displaying it appropriately.
     */
    fun logError(error: Throwable, context: String? = null)

    /**
     * Log informational message (for debugging)
     */
    fun logInfo(message: String)

    /**
     * Get a stored secret (like API key) for this plugin.
     * Returns null if not configured.
     *
     * @param key The setting key (matches @Setting field name)
     */
    fun getSecret(pluginId: String, key: String): String?

    /**
     * Store a secret securely. Used by settings panel.
     */
    fun storeSecret(pluginId: String, key: String, value: String)

    /**
     * Get user's preferred input/output languages.
     * Useful for plugins that auto-configure based on app state.
     */
    val currentInputLanguage: LanguageCode
    val currentOutputLanguage: LanguageCode
}

enum class NotificationType {
    INFO,
    WARNING,
    ERROR,
    SUCCESS
}