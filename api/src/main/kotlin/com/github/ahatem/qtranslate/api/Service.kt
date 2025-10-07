package com.github.ahatem.qtranslate.api

/**
 * The base interface for a distinct, functional capability provided by a plugin.
 *
 * A single plugin (the container, e.g., "Google Services Plugin") can provide multiple
 * services (the functions, e.g., a Translator, an OCR, a TextToSpeech). Each service
 * needs its own unique identity so the user can select it in the UI.
 *
 * For example, a user doesn't choose the "Google Plugin"; they choose the "Google Translate"
 * service from a list of all available translators.
 */
interface Service {
    /**
     * A unique, machine-readable identifier for this specific service.
     * This ID should be stable across versions and is used internally for mapping
     * and persisting user preferences (e.g., the user's default translator).
     *
     * Convention: `pluginId-serviceName` (e.g., "google-services-translate").
     */
    val id: String

    /**
     * The human-readable name for this service, displayed in the application's UI.
     * This is the primary text users will see in dropdowns and selection lists.
     *
     * Example: "Google Translate", "Pro Translator (High Quality)".
     */
    val name: String

    /**
     * The version of this specific service implementation (e.g., "1.1.0").
     * This is useful for debugging and logging, allowing you to know exactly which
     * version of a service is running. It is distinct from the plugin's version or
     * the application's version.
     */
    val version: String

    /**
     * An optional path to an icon file within the plugin's resources.
     * This provides a specific icon for this service (e.g., the Google Translate logo).
     *
     * If this is `null`, the core application will fall back to using the icon defined
     * in the parent plugin's `plugin.json` manifest. If that is also null, the core
     * will generate a placeholder icon.
     *
     * The path should be relative to the plugin's resource root (e.g., "assets/translate.svg").
     * The recommended format is SVG.
     *
     * @return A String representing the resource path, or null.
     */
    val iconPath: String?
        get() = null


    /**
     * Checks if this service supports a specific capability interface.
     * This default implementation uses reflection and is sufficient for most cases.
     *
     * @param capability The Class of the capability interface (e.g., VoiceSupport::class.java).
     * @return `true` if the service implements the capability, `false` otherwise.
     */
    fun <T : Any> supports(capability: Class<T>): Boolean {
        return capability.isAssignableFrom(this::class.java)
    }

    /**
     * Returns an instance of the capability interface if supported.
     * This allows the core to interact with extended features in a type-safe way
     * without resorting to 'is' checks.
     *
     * @param capability The Class of the capability interface.
     * @return The capability instance, or `null` if not supported.
     */
    fun <T : Any> getCapability(capability: Class<T>): T? {
        return if (supports(capability)) {
            capability.cast(this)
        } else {
            null
        }
    }
}