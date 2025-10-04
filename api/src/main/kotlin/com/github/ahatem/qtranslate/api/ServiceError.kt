package com.github.ahatem.qtranslate.api


/**
 * Standard errors that services can return.
 * Using sealed class allows the core to handle each type appropriately.
 */
sealed class ServiceError {
    abstract val message: String
    abstract val cause: Throwable?

    /**
     * Network connectivity issue.
     */
    data class NetworkError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError()

    /**
     * Authentication failed (invalid API key, etc.)
     */
    data class AuthenticationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError()

    /**
     * Rate limit exceeded.
     */
    data class RateLimitError(
        override val message: String,
        val retryAfterSeconds: Int? = null,
        override val cause: Throwable? = null
    ) : ServiceError()

    /**
     * Requested language is not supported by this service.
     */
    data class UnsupportedLanguageError(
        val language: LanguageCode,
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError()

    /**
     * Input is invalid (empty text, corrupted image, etc.)
     */
    data class InvalidInputError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError()

    /**
     * Service is temporarily unavailable.
     */
    data class ServiceUnavailableError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError()

    /**
     * Unknown/unexpected error.
     */
    data class UnknownError(
        override val message: String,
        override val cause: Throwable?
    ) : ServiceError()
}