package com.github.ahatem.qtranslate.api.plugin

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * A distinct, user-selectable function provided by a plugin.
 *
 * One plugin — the container, e.g. "Google Services" — provides several services: a translator,
 * an OCR, a text-to-speech. Each is selectable independently.
 *
 * ### Identity
 * A service declares a [key] that is unique **within its plugin**. The host combines that with
 * the plugin id and the instance the user created to form the identifier it persists.
 *
 * Plugins therefore never construct, assume, or store service identifiers. This is what allows
 * a user to install one plugin several times with different credentials: two instances declare
 * the same [key] and the host keeps them apart. It also removes the burden of inventing a
 * globally unique string from plugin authors.
 *
 * ### Roles
 * What a service is for follows from the interfaces it implements. Implement [Translator] and it
 * is offered as a translator; implement [Dictionary] too and it is offered as both. There is
 * nothing to declare, and therefore nothing that can contradict the code. See [ServiceRole].
 *
 * ### Language support
 * [supportedLanguages] is a synchronous read the host may call freely while rendering. When the
 * set is only knowable at runtime, return [SupportedLanguages.Dynamic] and the host will call
 * [fetchSupportedLanguages] once and cache the result.
 *
 * ### Optional behaviour
 * Behaviour a service may or may not have, such as voice selection or batching, is a separate
 * interface it may also implement, and the host asks for it with an ordinary cast:
 *
 * ```kotlin
 * val voices = (service as? VoiceSupport)?.voices.orEmpty()
 * ```
 *
 * This is deliberately not a [ServiceRole]. A role is what the user selects a service *for*;
 * optional behaviour only changes how it does that job. See
 * [com.github.ahatem.qtranslate.api.tts.VoiceSupport].
 */
interface Service {

    /**
     * Identifies this service within its plugin, e.g. `"translator"`, `"dictionary"`.
     *
     * Must be stable across versions — it forms part of the identifier the host persists, so
     * changing it silently resets the user's selection. It does **not** need to be globally
     * unique; the host qualifies it with the plugin and instance.
     */
    val key: String

    /** Human-readable name shown in the UI, e.g. `"Google Translate"`. */
    val name: String

    /** Version of this service implementation, distinct from the plugin's own version. */
    val version: String

    /** Descriptive facts the host may show the user. Purely informational. */
    val metadata: ServiceMetadata
        get() = ServiceMetadata()

    /**
     * User-selectable modifiers this service accepts, such as summary length or rewrite style.
     *
     * The host renders controls from this list and passes the chosen values back in the request.
     * Declaring nothing means the service takes no modifiers and the host shows no controls —
     * which is itself useful information it previously had no way to obtain.
     */
    val options: List<ServiceOption>
        get() = emptyList()

    /**
     * Path to an icon inside the plugin JAR, relative to its resource root. SVG preferred.
     * Falls back to the plugin's icon, then to a generated placeholder.
     */
    val iconPath: String?
        get() = null

    /**
     * Which languages this service handles.
     *
     * Return [SupportedLanguages.All] for broad coverage, [SupportedLanguages.Specific] for a
     * fixed set, or [SupportedLanguages.Dynamic] when it must be fetched.
     */
    val supportedLanguages: SupportedLanguages

    /**
     * Called once, and only when [supportedLanguages] is [SupportedLanguages.Dynamic]. The host
     * caches the result, so this is not invoked per render.
     */
    suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
        throw UnsupportedOperationException(
            "Service '$key' declared SupportedLanguages.Dynamic but did not override fetchSupportedLanguages()."
        )
    }

    /**
     * Checks that the service is configured and reachable, without performing real work.
     *
     * Powers a "test connection" affordance in settings, so a user can find out that a key is
     * wrong at the moment they enter it rather than the next time they translate something.
     *
     * Return [ServiceError.ConfigurationError] when required settings are absent, and
     * [ServiceError.AuthenticationError] when they are present but rejected — the host words
     * these differently because they need different actions from the user.
     *
     * Defaults to success, which is correct for services needing no configuration.
     */
    suspend fun validate(): Result<Unit, ServiceError> = Ok(Unit)
}

/**
 * Which languages a [Service] handles, in a form the host can read synchronously while
 * rendering language pickers and validating requests.
 */
sealed interface SupportedLanguages {

    /** Every language, including auto-detection. Typical of broad cloud APIs. */
    data object All : SupportedLanguages

    /**
     * A fixed, known set. Including [LanguageCode.AUTO] signals auto-detection support.
     */
    data class Specific(val languages: Set<LanguageCode>) : SupportedLanguages

    /**
     * Only knowable at runtime. The host calls [Service.fetchSupportedLanguages] once, caches
     * the result, and shows a loading state until it arrives.
     */
    data object Dynamic : SupportedLanguages
}
