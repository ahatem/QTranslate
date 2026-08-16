package com.github.ahatem.qtranslate.api.plugin

/**
 * A user-selectable modifier that a service declares and the host renders UI for.
 *
 * ### Why options are data rather than enums
 * Summary length and rewrite style used to be enums owned by the host. That made the vocabulary
 * closed: a plugin could not offer "Academic" or "Bullet points", could not say it supported
 * only some of the values the host advertised, and the host could not tell whether a service
 * honoured the parameter at all. The host built its dropdown from the enum, so every service
 * appeared to support every value.
 *
 * Declaring options as data inverts that. The service says what it supports, the host renders
 * exactly those choices, and a new modifier — formality, tone, domain, anything unforeseen —
 * needs no API change.
 *
 * ### What stays typed
 * Only *modifiers* are data-driven. The subject of a request — the text, the languages — stays
 * strongly typed, because it is universal and stable and there is no benefit to weakening it.
 *
 * ### Persistence
 * The user's choice is stored as [ServiceOptionValue.id], a plain string, so a selection
 * survives restarts even when the value was invented by a plugin. Ids must therefore be stable
 * across versions of a service.
 */
data class ServiceOption(
    /** Identifies the option within its service. Used as the key in a request's option map. */
    val key: String,

    /** Label for the control, e.g. "Length". */
    val label: DisplayText,

    /** The choices offered. Must be non-empty. */
    val values: List<ServiceOptionValue>,

    /** Id of the value used when the user has not chosen one. Must exist in [values]. */
    val defaultValue: String,

    /**
     * The role this option applies to, or `null` when it applies to all of them.
     *
     * Options hang off the service, but a service can hold several roles, and an option rarely
     * means anything outside one of them. A service that both summarizes and rewrites declares
     * length and style in one list, and without this the host would offer summary length while
     * rewriting and rewrite style while summarizing.
     *
     * `null` is the default because most services hold a single role, where scoping an option
     * would be noise.
     */
    val role: ServiceRole? = null
) {
    init {
        require(key.isNotBlank()) { "ServiceOption.key must not be blank." }
        require(values.isNotEmpty()) { "ServiceOption '$key' must declare at least one value." }
        require(values.any { it.id == defaultValue }) {
            "ServiceOption '$key' has defaultValue '$defaultValue', which is not among its values."
        }
    }
}

/**
 * One choice within a [ServiceOption].
 *
 * @property id Stable identifier, persisted in the user's configuration. Never change it for an
 *   existing value — doing so silently resets everyone's saved choice.
 * @property label What the user sees.
 */
data class ServiceOptionValue(
    val id: String,
    val label: DisplayText
) {
    init { require(id.isNotBlank()) { "ServiceOptionValue.id must not be blank." } }
}

/**
 * The option vocabularies the host already translates.
 *
 * A service offering the conventional choices should use these, so its labels appear in every
 * language the application ships. A service with something else to offer builds its own
 * [ServiceOption] and supplies translations in its own `localization/` bundle — that is the
 * point of the design, and it costs only the labels it invented.
 *
 * The ids match the enum constants these replaced, so configurations written by earlier versions
 * keep resolving.
 *
 * The label keys are the host's own, not a namespace invented for this object: these exact strings
 * are already translated in every language the application ships, and pointing at them means a
 * service using the standard vocabulary inherits all of that rather than starting from English.
 */
object StandardOptions {

    const val KEY_SUMMARY_LENGTH = "length"
    const val KEY_REWRITE_STYLE = "style"

    val SUMMARY_LENGTH: ServiceOption = ServiceOption(
        key = KEY_SUMMARY_LENGTH,
        label = DisplayText("settings_translation.summary_length", "Length"),
        values = listOf(
            ServiceOptionValue("SHORT", DisplayText("settings_translation.summary_length_short", "Short")),
            ServiceOptionValue("MEDIUM", DisplayText("settings_translation.summary_length_medium", "Medium")),
            ServiceOptionValue("LONG", DisplayText("settings_translation.summary_length_long", "Long"))
        ),
        defaultValue = "MEDIUM",
        role = ServiceRole.SUMMARIZER
    )

    val REWRITE_STYLE: ServiceOption = ServiceOption(
        key = KEY_REWRITE_STYLE,
        label = DisplayText("settings_translation.rewrite_style", "Style"),
        values = listOf(
            ServiceOptionValue("FORMAL", DisplayText("settings_translation.rewrite_style_formal", "Formal")),
            ServiceOptionValue("CASUAL", DisplayText("settings_translation.rewrite_style_casual", "Casual")),
            ServiceOptionValue("CONCISE", DisplayText("settings_translation.rewrite_style_concise", "Concise")),
            ServiceOptionValue("DETAILED", DisplayText("settings_translation.rewrite_style_detailed", "Detailed")),
            ServiceOptionValue("SIMPLIFIED", DisplayText("settings_translation.rewrite_style_simplified", "Simplified"))
        ),
        defaultValue = "FORMAL",
        role = ServiceRole.REWRITER
    )
}

/**
 * Reads an option from a request's option map, falling back to the service's declared default
 * and then to [fallback] when the service declares no such option.
 *
 * Saves every implementation from repeating the same null-and-default dance.
 */
fun Map<String, String>.optionOrDefault(
    option: ServiceOption?,
    key: String,
    fallback: String
): String = this[key] ?: option?.defaultValue ?: fallback
