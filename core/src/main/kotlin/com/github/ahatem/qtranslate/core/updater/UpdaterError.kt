package com.github.ahatem.qtranslate.core.updater

/**
 * Typed errors that can occur during an update check.
 * Consistent with [com.github.ahatem.qtranslate.api.plugin.ServiceError]
 * and [com.github.ahatem.qtranslate.core.settings.data.SettingsError].
 */
sealed interface UpdaterError {
    val message: String
    val cause: Throwable?

    /** A network connectivity or HTTP error occurred while contacting the release API. */
    data class NetworkError(
        override val message: String,
        override val cause: Throwable? = null
    ) : UpdaterError

    /** The API response could not be parsed. May indicate an API contract change. */
    data class ParseError(
        override val message: String,
        override val cause: Throwable? = null
    ) : UpdaterError

    /** An unexpected error occurred. */
    data class UnknownError(
        override val message: String,
        override val cause: Throwable? = null
    ) : UpdaterError
}