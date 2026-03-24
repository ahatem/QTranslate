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
import javax.swing.SwingUtilities

fun main() = runBlocking {

    AppUiSetup.setSystemProperties()
    AppUiSetup.setRenderingHints()

    val appData    = AppDataDirectory.resolve()
    val logFactory = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.DEBUG)
    val logger     = logFactory.getLogger("Main")

    logger.info("QTranslate ${AppConstants.APP_VERSION} starting...")
    logger.info("App data directory: ${appData.absolutePath}")

    val json         = Json { ignoreUnknownKeys = true; isLenient = true }
    val settingsRepo = SettingsRepository(appData, json, logFactory.getLogger("SettingsRepository"))

    val initialConfig = withTimeoutOrNull(AppConstants.CONFIG_LOAD_TIMEOUT_MS) {
        settingsRepo.loadInitialConfiguration()
    } ?: run {
        logger.warn("Configuration load timed out after ${AppConstants.CONFIG_LOAD_TIMEOUT_MS}ms — using defaults")
        Configuration.DEFAULT
    }

    logger.info("Configuration loaded: theme=${initialConfig.themeId}, scale=${initialConfig.uiScale}")

    val deps = buildDependencies(
        appData       = appData,
        loggerFactory = logFactory,
        settingsRepo  = settingsRepo,
        initialConfig = initialConfig
    )
    AppUiSetup.apply(initialConfig, deps.themeManager)

    deps.appScope.launch {
        logger.info("Loading plugins...")
        runCatching { deps.pluginManager.loadAndProcessPlugins() }
            .onSuccess { logger.info("Plugins loaded successfully") }
            .onFailure { e -> logger.error("Failed to load plugins", e) }
    }

    val savedLanguage = LanguageCode(initialConfig.interfaceLanguage)
    runCatching {
        deps.localizationManager.loadLanguage(savedLanguage)
        logger.info("Interface language loaded: ${initialConfig.interfaceLanguage}")
    }.onFailure { e ->
        logger.warn("Failed to load interface language '${initialConfig.interfaceLanguage}': ${e.message}")
    }

    SwingUtilities.invokeLater {
        MainAppFrame(
            mainStore        = deps.mainStore,
            settingsStore    = deps.settingsStore,
            iconManager      = deps.iconManager,
            themeManager     = deps.themeManager,
            localizer        = deps.localizationManager,
            pluginManager    = deps.pluginManager,
            notificationBus  = deps.notificationBus
        )
        logger.info("Main window launched")
    }

    Thread.currentThread().join()
}