package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.ui.swing.main.MainAppFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.swing.SwingUtilities

/**
 * Application entry point.
 *
 * ### Startup sequence
 * 1. Set system properties — must happen before any AWT class is touched.
 * 2. Set rendering hints.
 * 3. Resolve the app data directory (JAR-relative, OS fallback).
 * 4. Create [SettingsRepository] — must be a singleton (DataStore forbids
 *    multiple instances pointing at the same file).
 * 5. Load initial configuration with a timeout fallback to defaults.
 * 6. Apply theme and fonts — before any window opens to prevent unstyled flash.
 * 7. Wire all dependencies (receives the already-created repo).
 * 8. Load plugins in the background on the app scope.
 * 9. Open the main window on the EDT.
 * 10. Block the main thread to keep the process alive.
 */
fun main() = runBlocking {

    // Step 1 & 2 — must run before any AWT/Swing class is loaded
    AppUiSetup.setSystemProperties()
    AppUiSetup.setRenderingHints()

    // Step 3 — resolve data directory
    val appData    = AppDataDirectory.resolve()
    val logFactory = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.DEBUG)
    val logger     = logFactory.getLogger("Main")

    logger.info("QTranslate ${AppConstants.APP_VERSION} starting...")
    logger.info("App data directory: ${appData.absolutePath}")

    // Step 4 — create SettingsRepository exactly once.
    // DataStore throws if two instances share the same file path, so we create
    // the repo here and pass it into buildDependencies rather than letting
    // buildDependencies create its own copy.
    val json         = Json { ignoreUnknownKeys = true; isLenient = true }
    val settingsRepo = SettingsRepository(appData, json, logFactory.getLogger("SettingsRepository"))

    // Step 5 — load initial configuration with timeout
    val initialConfig = withTimeoutOrNull(AppConstants.CONFIG_LOAD_TIMEOUT_MS) {
        settingsRepo.loadInitialConfiguration()
    } ?: run {
        logger.warn("Configuration load timed out after ${AppConstants.CONFIG_LOAD_TIMEOUT_MS}ms — using defaults")
        Configuration.DEFAULT
    }

    logger.info("Configuration loaded: theme=${initialConfig.themeId}, scale=${initialConfig.uiScale}")

    // Step 6 & 7 — wire dependencies, then apply UI config
    val deps = buildDependencies(
        appData       = appData,
        loggerFactory = logFactory,
        settingsRepo  = settingsRepo,
        initialConfig = initialConfig
    )
    AppUiSetup.apply(initialConfig, deps.themeManager)

    // Step 8 — load plugins in the background
    deps.appScope.launch {
        logger.info("Loading plugins...")
        runCatching { deps.pluginManager.loadAndProcessPlugins() }
            .onSuccess { logger.info("Plugins loaded successfully") }
            .onFailure { e -> logger.error("Failed to load plugins", e) }
    }

    // Step 9 — open main window on the EDT
    SwingUtilities.invokeLater {
        MainAppFrame(
            mainStore     = deps.mainStore,
            settingsStore = deps.settingsStore,
            iconManager   = deps.iconManager,
            themeManager  = deps.themeManager,
            localizer     = deps.localizationManager,
            pluginManager = deps.pluginManager
        )
        logger.info("Main window launched")
    }

    // Step 10 — keep the process alive until the window closes
    Thread.currentThread().join()
}