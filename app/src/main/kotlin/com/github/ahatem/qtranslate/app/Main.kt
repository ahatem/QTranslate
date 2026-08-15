package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationCode
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.ui.swing.main.MainAppFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.swing.SwingUtilities

fun main() = runBlocking {

    var frame: MainAppFrame? = null

    if (!SingleInstanceGuard.tryLock(onFocusRequested = {
            SwingUtilities.invokeLater {
                frame?.apply {
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            }
        })) {
        return@runBlocking
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        SingleInstanceGuard.release()
    })

    AppUiSetup.setSystemProperties()
    AppUiSetup.setRenderingHints()

    val appData    = AppDataDirectory.resolve()
    val logFactory = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.DEBUG)
    val logger     = logFactory.getLogger("Main")

    logger.info("QTranslate ${AppConstants.APP_VERSION} starting...")
    logger.info("App data directory: ${appData.absolutePath}")

    val json         = Json { ignoreUnknownKeys = true; isLenient = true }
    val settingsRepo = SettingsRepository(appData, json, logFactory.getLogger("SettingsRepository"))

    var configLoadTimedOut = false
    val initialConfig = withTimeoutOrNull(AppConstants.CONFIG_LOAD_TIMEOUT_MS) {
        settingsRepo.loadInitialConfiguration()
    } ?: run {
        logger.warn("Configuration load timed out after ${AppConstants.CONFIG_LOAD_TIMEOUT_MS}ms — using defaults")
        configLoadTimedOut = true
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

    // Starting with defaults when settings existed is not a detail to leave in a log file. From
    // the user's side the app looks freshly installed, and the natural response — setting
    // everything up again — overwrites the file that still holds the original.
    settingsRepo.lastRecovery?.let { recovery ->
        logger.error("Configuration recovery: ${recovery.reason}")
        deps.notificationBus.post(
            AppNotification(
                type = NotificationType.ERROR,
                code = NotificationCode.Custom(
                    title = "Settings could not be loaded",
                    body = recovery.reason
                )
            )
        )
    }
    if (configLoadTimedOut) {
        deps.notificationBus.post(
            AppNotification(
                type = NotificationType.WARNING,
                code = NotificationCode.Custom(
                    title = "Settings took too long to load",
                    body = "QTranslate started with default settings so it would not hang. " +
                        "Your saved settings are still on disk — restarting may load them."
                )
            )
        )
    }

    val savedLanguage = if (initialConfig.interfaceLanguage == LanguageCode.ENGLISH.tag) {
        OsLanguageDetector.detect(deps.localizationManager.availableLanguages)
    } else {
        LanguageCode(initialConfig.interfaceLanguage)
    }
    runCatching {
        deps.localizationManager.loadLanguage(savedLanguage)
        logger.info("Interface language loaded: ${initialConfig.interfaceLanguage}")
    }.onFailure { e ->
        logger.warn("Failed to load interface language '${initialConfig.interfaceLanguage}': ${e.message}")
    }

    // The window is shown before plugins are loaded. Everything it needs to paint — theme,
    // scale and interface language — is already resolved, and services reach the UI through
    // SelectActiveServiceUseCase.observe(), so the selector and Plugins panel fill in on
    // their own as plugins finish initialising. Waiting here instead would leave the screen
    // empty for as long as the slowest plugin takes, and several do network work at startup.
    SwingUtilities.invokeLater {
        frame = MainAppFrame(
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

    logger.info("Loading plugins...")
    deps.appScope.launch {
        runCatching { deps.pluginManager.loadAndProcessPlugins() }
            .onSuccess { logger.info("Plugins loaded successfully") }
            .onFailure { e -> logger.error("Failed to load plugins", e) }
    }

    Thread.currentThread().join()
}