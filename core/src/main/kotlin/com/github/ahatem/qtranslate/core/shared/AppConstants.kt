package com.github.ahatem.qtranslate.core.shared

/**
 * Central location for application-wide constants.
 */
object AppConstants {

    // ============================================================
    // Application
    // ============================================================

    /**
     * Kept in step with the release tag by a check in the Release workflow, which fails the build
     * if the two disagree.
     *
     * They are separate values: artifacts take their version from the tag, this one is compiled
     * in, and nothing connected them. So this sat at 1.3.0 through the whole 1.4.0 cycle — which
     * would have shipped an app that compares its own 1.3.0 against the 1.4.0 release it came
     * from and tells every new user an update is waiting.
     */
    const val APP_VERSION = "1.4.0"

    // ============================================================
    // Timing
    // ============================================================

    /**
     * Debounce delay for instant translation.
     *
     * Kept short enough that typing still feels connected to the result. Beyond roughly
     * half a second the translation reads as a separate event rather than a response to
     * what was just typed, which is what "instant translation" is meant to convey.
     */
    const val INSTANT_TRANSLATION_DEBOUNCE_MS = 350L

    /** Minimum input length before instant translation fires. */
    const val INSTANT_TRANSLATE_MIN_CHARS = 2

    /** Debounce delay for spell checking. */
    const val SPELL_CHECK_DEBOUNCE_MS = 750L

    /** Timeout for loading initial configuration on app startup. */
    const val CONFIG_LOAD_TIMEOUT_MS = 5000L

    /** Timeout for translation operations. */
    const val TRANSLATION_TIMEOUT_MS = 30_000L

    /** Timeout for TTS operations. */
    const val TTS_TIMEOUT_MS = 30_000L

    /** Delay before clearing temporary status bar messages. */
    const val STATUS_MESSAGE_DURATION_MS = 5_000L

    // ============================================================
    // UI
    // ============================================================

    /** Default main window dimensions on first launch. */
    const val DEFAULT_WINDOW_WIDTH = 500
    const val DEFAULT_WINDOW_HEIGHT = 380

    /** Minimum allowed window dimensions. */
    const val MIN_WINDOW_WIDTH = 450
    const val MIN_WINDOW_HEIGHT = 260

    // ============================================================
    // Plugins
    // ============================================================

    /** Plugin directory name within the app data folder. */
    const val PLUGIN_DIRECTORY = "plugins"

    // ============================================================
    // Storage
    // ============================================================

    /** DataStore preferences file name. */
    const val DATASTORE_FILE = "app_settings.preferences_pb"

    /** History DataStore file name. */
    const val DATASTORE_HISTORY_FILE = "history.preferences_pb"

    /** Maximum history entries to keep. */
    const val MAX_HISTORY_ENTRIES = 1000
}