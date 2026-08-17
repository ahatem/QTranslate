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
public sealed class ServiceError {
    /** A human-readable description of what went wrong. */
    public abstract val message: String

    /** The underlying exception, if one caused this error. Used for logging. */
    public abstract val cause: Throwable?

    /**
     * Whether the core should consider automatically retrying the operation.
     * Transient failures (network, timeout, rate limit, unavailability) are
     * retryable. Permanent failures (auth, bad input, unsupported language) are not.
     */
    public abstract val isRetryable: Boolean

    // -------------------------------------------------------------------------
    // Transient errors — isRetryable = true
    // -------------------------------------------------------------------------

    /**
     * A network connectivity issue, such as no internet or a DNS failure.
     * The operation may succeed if retried once connectivity is restored.
     */
    public data class NetworkError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = true
    }

    /**
     * The request did not complete within the expected time window.
     * May succeed on retry if the service was temporarily slow.
     */
    public data class TimeoutError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = true
    }

    /**
     * The service is temporarily unavailable (e.g. maintenance, overload).
     * The operation may succeed after a short delay.
     */
    public data class ServiceUnavailableError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = true
    }

    /**
     * The API rate limit has been exceeded.
     *
     * @param retryAfterSeconds A hint from the service for how long to wait before
     *                          retrying, or `null` if not provided.
     */
    public data class RateLimitError(
        override val message: String,
        val retryAfterSeconds: Int? = null,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = true
    }

    // -------------------------------------------------------------------------
    // Permanent errors — isRetryable = false
    // -------------------------------------------------------------------------

    /**
     * The service has not been configured yet — a required setting, usually an API key, is
     * missing entirely.
     *
     * Distinct from [AuthenticationError], which means credentials were supplied and rejected.
     * The host words them differently because they call for different actions: one sends the
     * user to settings to fill something in, the other tells them what they entered is wrong.
     */
    public data class ConfigurationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = false
    }

    /**
     * Authentication failed. The API key or credentials are invalid or expired.
     * Retrying with the same credentials will always fail.
     */
    public data class AuthenticationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = false
    }

    /**
     * The requested language is not supported by this service.
     * @param language The [LanguageCode] that was rejected.
     */
    public data class UnsupportedLanguageError(
        val language: LanguageCode,
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = false
    }

    /**
     * The input was invalid (e.g. empty text, a corrupted image, out-of-range values).
     * The caller must fix the input before retrying.
     */
    public data class InvalidInputError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = false
    }

    /**
     * The service returned a response that could not be parsed or understood.
     * This typically indicates an API contract change on the service's side.
     */
    public data class InvalidResponseError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = false
    }

    /**
     * Validation failed for a specific field or parameter.
     * The caller must correct the input before retrying.
     */
    public data class ValidationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = false
    }

    /**
     * An unexpected or unknown error occurred.
     * Use this as a last resort when no other subclass fits.
     */
    public data class UnknownError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ServiceError() {
        override val isRetryable: Boolean = false
    }
}