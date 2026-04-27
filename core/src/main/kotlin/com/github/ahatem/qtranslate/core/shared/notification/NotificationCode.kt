package com.github.ahatem.qtranslate.core.shared.notification

/**
 * Structured identifier for a notification.
 *
 * The core layer emits a code and any needed parameters; the UI layer resolves
 * the user-visible, localized text from the code — keeping the core free of
 * hardcoded strings and allowing the UI to take code-specific actions
 * (e.g. showing the [UpdateAvailable] dialog instead of a plain status-bar message).
 */
sealed class NotificationCode {

    /** A newer version of the application is available for download. */
    data class UpdateAvailable(
        val newVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String?
    ) : NotificationCode()

    /** A service does not support the requested language. */
    data class LanguageNotSupported(val lang: String, val serviceId: String) : NotificationCode()

    /** A TTS service does not support the requested language. */
    data class TtsNotSupported(val serviceId: String) : NotificationCode()

    /** An unspecified error occurred. */
    object UnknownError : NotificationCode()

    /**
     * Free-form notification — used by the plugin system and plugin-originated messages.
     * The UI renders [title] and [body] as-is (no localization lookup).
     */
    data class Custom(val title: String, val body: String) : NotificationCode()
}
