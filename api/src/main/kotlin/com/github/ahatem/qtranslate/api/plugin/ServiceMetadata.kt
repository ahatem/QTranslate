package com.github.ahatem.qtranslate.api.plugin

/**
 * Descriptive facts about a service that the host may surface to the user.
 *
 * Every field is optional with a default, so adding one later is additive and needs no MAJOR
 * version bump. Nothing here affects behaviour — it exists so the host can explain a service
 * rather than presenting an opaque name.
 */
public data class ServiceMetadata(

    /**
     * Whether the service needs configuration — typically an API key — before it can be used.
     *
     * Lets the host say "needs setup" up front instead of letting the user discover it through
     * a failed request. Services that work out of the box leave this false.
     */
    val requiresConfiguration: Boolean = false,

    /** Whether the service is usable without payment, where the plugin can state this honestly. */
    val isFree: Boolean? = null,

    /** Where to read about the service or obtain credentials. */
    val homepage: String? = null,

    /**
     * Credit the provider requires to be displayed alongside results.
     *
     * Some sources — Creative Commons image libraries in particular — make this a licence
     * condition rather than a courtesy, so the host shows it wherever results appear.
     */
    val attribution: DisplayText? = null,

    /** Known usage limits, for the host to warn before the user hits them. */
    val rateLimit: RateLimit? = null,

    /** Anything else worth telling the user, such as reliance on an unofficial endpoint. */
    val notes: DisplayText? = null
)

/**
 * A declared usage limit.
 *
 * Advisory only — the host does not enforce it. Its purpose is to let the UI warn a user before
 * they run into [ServiceError.RateLimitError], which is a far worse way to learn.
 */
public data class RateLimit(
    val requests: Int,
    val perSeconds: Int,
    val note: DisplayText? = null
) {
    init {
        require(requests > 0) { "RateLimit.requests must be positive, was $requests." }
        require(perSeconds > 0) { "RateLimit.perSeconds must be positive, was $perSeconds." }
    }
}
