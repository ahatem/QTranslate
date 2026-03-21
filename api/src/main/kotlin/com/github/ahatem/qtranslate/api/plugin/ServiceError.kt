package com.github.ahatem.qtranslate.api.plugin

import com.github.ahatem.qtranslate.api.language.LanguageCode

/**
 * Standard errors that services can return, using a sealed class so the core
 * can exhaustively handle each failure type via a `when` expression.
 *
 * ### Retry Behaviour
 * Each subclass declares [isRetryable] to signal whether the core should attempt
 * an automatic retry. The core uses this flag — plugin authors do not need to
 * implement retry logic themselves.
 *
 * ### Usage
 * Always return errors via `Err(ServiceError.NetworkError(...))` from the
 * `kotlin-result` library. Never throw raw exceptions for expected failures.
 */
sealed class ServiceError {
    /** A human-readable description of what went wrong. */
    abstract val message: String

    /** The underlying exception, if one caused this error. Used for logging. */
    abstract val cause: Throwable?

    /**
     * Whether the core should consider automatically retrying the operation.
     * Transient failures (network, timeout, rate limit, unavailability) are
     * retryable. Permanent failures (auth, bad input, unsupported language) are not.
     */
    abstract val isRetryable: Boolean

    // -------------------------------------------------------------------------
    // Transient errors — isRetryable = true
    // -------------------------------------------------------------------------

    /**
     * A network connectivity issue, such as no internet or a DNS failure.
     * The operation may succeed if retried once connectivity is restored.
     */
    data class NetworkError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable = true
    }

    /**
     * The request did not complete within the expected time window.
     * May succeed on retry if the service was temporarily slow.
     */
    data class TimeoutError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable = true
    }

    /**
     * The service is temporarily unavailable (e.g. maintenance, overload).
     * The operation may succeed after a short delay.
     */
    data class ServiceUnavailableError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable = true
    }

    /**
     * The API rate limit has been exceeded.
     *
     * @param retryAfterSeconds A hint from the service for how long to wait before
     *                          retrying, or `null` if not provided.
     */
    data class RateLimitError(
        override val message: String,
        val retryAfterSeconds: Int? = null,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable = true
    }

    // -------------------------------------------------------------------------
    // Permanent errors — isRetryable = false
    // -------------------------------------------------------------------------

    /**
     * Authentication failed. The API key or credentials are invalid or expired.
     * Retrying with the same credentials will always fail.
     */
    data class AuthenticationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable = false
    }

    /**
     * The requested language is not supported by this service.
     * @param language The [LanguageCode] that was rejected.
     */
    data class UnsupportedLanguageError(
        val language: LanguageCode,
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable = false
    }

    /**
     * The input was invalid (e.g. empty text, a corrupted image, out-of-range values).
     * The caller must fix the input before retrying.
     */
    data class InvalidInputError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable = false
    }

    /**
     * The service returned a response that could not be parsed or understood.
     * This typically indicates an API contract change on the service's side.
     */
    data class InvalidResponseError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable = false
    }

    /**
     * Validation failed for a specific field or parameter.
     * The caller must correct the input before retrying.
     */
    data class ValidationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable = false
    }

    /**
     * An unexpected or unknown error occurred.
     * Use this as a last resort when no other subclass fits.
     */
    data class UnknownError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable = false
    }
}