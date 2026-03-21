package com.github.ahatem.qtranslate.api.plugin

import com.github.ahatem.qtranslate.api.language.LanguageCode

/**
 * The base interface for a distinct, functional capability provided by a plugin.
 *
 * A single plugin (the container, e.g., "Google Services Plugin") can provide multiple
 * services (the functions, e.g., a Translator, an OCR, a TextToSpeech). Each service
 * needs its own unique identity so the user can select it in the UI.
 *
 * ### Language Support
 * Rather than mixing in a `LanguageSupport` interface with a suspend function,
 * each service declares its language support through the [supportedLanguages] property.
 * This is a fast, synchronous read that the core can call freely for UI rendering
 * (populating dropdowns, filtering the service list) without worrying about network
 * calls or caching. If a service's supported languages can only be known at runtime
 * (e.g. fetched from an API), it should return [SupportedLanguages.Dynamic] and the
 * core will invoke [fetchSupportedLanguages] once, cache the result, and use that.
 *
 * ### Capability Discovery
 * Extended features (e.g. [com.github.ahatem.qtranslate.api.tts.VoiceSupport]) are
 * discovered via [getCapability], avoiding `is` checks scattered through the core.
 */
interface Service {

    /**
     * A unique, machine-readable identifier for this specific service.
     * This ID must be stable across versions — it is used to persist user preferences
     * (e.g. the user's default translator).
     *
     * Convention: `pluginId-serviceName` (e.g. `"google-services-translate"`).
     */
    val id: String

    /**
     * The human-readable name for this service, displayed in the application's UI.
     *
     * Example: `"Google Translate"`, `"DeepL Pro"`.
     */
    val name: String

    /**
     * The version of this specific service implementation (e.g. `"1.1.0"`).
     * Distinct from the plugin version or the application version.
     */
    val version: String

    /**
     * An optional path to an icon resource within the plugin's JAR.
     * The path should be relative to the plugin's resource root (e.g. `"assets/translate.svg"`).
     * SVG format is strongly recommended.
     *
     * If `null`, the core falls back to the parent plugin's icon, then to a generated placeholder.
     */
    val iconPath: String?
        get() = null

    /**
     * Declares which languages this service supports, used by the core for UI rendering
     * and request validation without requiring a suspend call.
     *
     * - Return [SupportedLanguages.All] if the service supports every language (e.g. a cloud
     *   API with broad coverage).
     * - Return [SupportedLanguages.Specific] for a fixed, known set of languages.
     * - Return [SupportedLanguages.Dynamic] if the set can only be determined at runtime
     *   (e.g. fetched from the API). The core will call [fetchSupportedLanguages] once,
     *   cache the result, and display a loading indicator in the UI in the meantime.
     */
    val supportedLanguages: SupportedLanguages

    /**
     * Called by the core only when [supportedLanguages] is [SupportedLanguages.Dynamic].
     * The result is cached by the core keyed by [id] — this will not be called on every
     * render cycle.
     *
     * The default implementation throws [UnsupportedOperationException] and must be
     * overridden if [supportedLanguages] returns [SupportedLanguages.Dynamic].
     *
     * @return `Ok` with the resolved set of [LanguageCode]s, or an `Err` with a [ServiceError].
     */
    suspend fun fetchSupportedLanguages(): com.github.michaelbull.result.Result<Set<LanguageCode>, ServiceError> {
        throw UnsupportedOperationException(
            "Service '$id' declared SupportedLanguages.Dynamic but did not override fetchSupportedLanguages()."
        )
    }

    /**
     * Checks if this service implements a specific capability interface.
     *
     * @param capability The `Class` of the capability (e.g. `VoiceSupport::class.java`).
     * @return `true` if this service implements the capability.
     */
    fun <T : Any> supports(capability: Class<T>): Boolean =
        capability.isAssignableFrom(this::class.java)

    /**
     * Returns a type-safe instance of the capability if supported, or `null` otherwise.
     * Prefer this over direct `is` checks in the core.
     *
     * Example: `service.getCapability(VoiceSupport::class.java)?.voices`
     *
     * @param capability The `Class` of the capability.
     * @return The capability instance cast to [T], or `null`.
     */
    fun <T : Any> getCapability(capability: Class<T>): T? =
        if (supports(capability)) capability.cast(this) else null
}

/**
 * Declares which languages a [Service] supports, enabling the core to render language
 * selection UI and validate requests without suspend calls.
 */
sealed interface SupportedLanguages {

    /**
     * The service supports all languages, including auto-detection.
     * Use this for broad cloud APIs (e.g. Google Translate, DeepL).
     */
    data object All : SupportedLanguages

    /**
     * The service supports a fixed, known set of languages.
     * The presence of [LanguageCode.AUTO] in [languages] indicates auto-detection support.
     *
     * @param languages The complete set of supported [LanguageCode]s.
     */
    data class Specific(val languages: Set<LanguageCode>) : SupportedLanguages

    /**
     * The supported languages can only be determined at runtime (e.g. fetched from the API).
     * The core will call [Service.fetchSupportedLanguages] once and cache the result.
     * A loading state will be shown in the UI until the result is available.
     */
    data object Dynamic : SupportedLanguages
}