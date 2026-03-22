package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.ui.swing.main.MainAppFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import javax.swing.SwingUtilities

/**
 * Application entry point.
 *
 * ### Startup sequence
 * 1. Set system properties — must happen before any AWT class is touched.
 * 2. Set rendering hints.
 * 3. Resolve the app data directory.
 * 4. Set `logDir` system property so Logback writes next to the app data.
 * 5. Create [SettingsRepository] — singleton, DataStore forbids multiple instances.
 * 6. Load initial configuration with a timeout fallback to defaults.
 * 7. Wire all dependencies.
 * 8. Apply theme and fonts — before any window opens to prevent unstyled flash.
 * 9. Load the saved interface language — before any window opens so strings
 *    and component orientation are correct from the first frame.
 * 10. Load plugins in the background.
 * 11. Open the main window on the EDT.
 * 12. Block the main thread to keep the process alive.
 */
fun main() = runBlocking {

    // Step 1 & 2
    AppUiSetup.setSystemProperties()
    AppUiSetup.setRenderingHints()

    // Step 3
    val appData = AppDataDirectory.resolve()

    // Step 4
    val logsDir = File(appData, "logs").also { it.mkdirs() }
    System.setProperty("logDir", logsDir.absolutePath)

    val logFactory = LogbackLoggerFactory()
    val logger     = logFactory.getLogger("Main")

    logger.info("QTranslate ${AppConstants.APP_VERSION} starting...")
    logger.info("App data directory: ${appData.absolutePath}")
    logger.info("Logs directory:     ${logsDir.absolutePath}")

    // Step 5
    val json         = Json { ignoreUnknownKeys = true; isLenient = true }
    val settingsRepo = SettingsRepository(appData, json, logFactory.getLogger("SettingsRepository"))

    // Step 6
    val initialConfig = withTimeoutOrNull(AppConstants.CONFIG_LOAD_TIMEOUT_MS) {
        settingsRepo.loadInitialConfiguration()
    } ?: run {
        logger.warn("Configuration load timed out — using defaults")
        Configuration.DEFAULT
    }

    logger.info("Configuration loaded: theme=${initialConfig.themeId}, language=${initialConfig.interfaceLanguage}")

    // Step 7
    val deps = buildDependencies(
        appData       = appData,
        loggerFactory = logFactory,
        settingsRepo  = settingsRepo,
        initialConfig = initialConfig
    )

    // Step 8 — apply theme/fonts before any Swing component is created
    AppUiSetup.apply(initialConfig, deps.themeManager)

    // Step 9 — load the saved language before the window opens.
    // This ensures:
    //   a) All localised strings are correct from the first render.
    //   b) isRtl is already set so applyOrientation() in MainAppFrame
    //      reads the correct value when the window is constructed.
    val savedLanguage = LanguageCode(initialConfig.interfaceLanguage)
    if (savedLanguage != LanguageCode.ENGLISH) {
        logger.info("Loading interface language: ${savedLanguage.tag}")
        deps.localizationManager.loadLanguage(savedLanguage)
    }

    // Step 10 — load plugins in the background
    deps.appScope.launch {
        logger.info("Loading plugins...")
        runCatching { deps.pluginManager.loadAndProcessPlugins() }
            .onSuccess { logger.info("Plugins loaded") }
            .onFailure { e -> logger.error("Failed to load plugins", e) }
    }

    // Step 11 — open main window on the EDT
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

    // Step 12
    Thread.currentThread().join()
}