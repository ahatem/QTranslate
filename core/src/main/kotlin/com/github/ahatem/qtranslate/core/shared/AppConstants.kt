package com.github.ahatem.qtranslate.core.shared

/**
 * Central location for application-wide constants.
 */
object AppConstants {

    // ============================================================
    // Application
    // ============================================================

    /**
     * The current application version string.
     * Must match the tag format used in GitHub Releases (e.g. "1.0.0" or "v1.0.0").
     * Used by [com.github.ahatem.qtranslate.core.main.domain.usecase.CheckForUpdatesUseCase]
     * to compare against the latest release.
     */
    const val APP_VERSION = "1.1.0"

    // ============================================================
    // Timing
    // ============================================================

    /** Debounce delay for instant translation. */
    const val INSTANT_TRANSLATION_DEBOUNCE_MS = 500L

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

    /** Default quick translate popup dimensions. */
    const val DEFAULT_POPUP_WIDTH = 450
    const val DEFAULT_POPUP_HEIGHT = 250

    // ============================================================
    // Plugins
    // ============================================================

    /** Plugin directory name within the app data folder. */
    const val PLUGIN_DIRECTORY = "plugins"

    /** Maximum plugin load timeout. */
    const val PLUGIN_LOAD_TIMEOUT_MS = 10_000L

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