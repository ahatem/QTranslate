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
}