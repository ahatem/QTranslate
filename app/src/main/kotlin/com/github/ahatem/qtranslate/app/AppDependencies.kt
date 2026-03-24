package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.domain.usecase.CheckForUpdatesUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.HandleTextToSpeechUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.OcrAndTranslateUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.PerformSpellCheckUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SelectActiveServiceUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SwapLanguagesUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.RewriteUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SummarizeUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslateTextUseCase
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.events.AppEventBus
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.updater.Updater
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import java.io.File

/**
 * All wired-up application dependencies, produced by [buildDependencies]
 * and consumed by the UI layer.
 *
 * Everything in this class is a singleton for the lifetime of the process.
 * The [appScope] is the root coroutine scope — cancelling it shuts everything down.
 */
class AppDependencies(
    val appScope: CoroutineScope,
    val mainStore: MainStore,
    val settingsStore: SettingsStore,
    val pluginManager: PluginManager,
    val iconManager: IconManager,
    val themeManager: ThemeManager,
    val localizationManager: LocalizationManager,
    val notificationBus: NotificationBus
)

/**
 * Constructs and wires all application dependencies in the correct order.
 *
 * [settingsRepo] is accepted as a parameter rather than created here because
 * DataStore throws if two instances point at the same file. The repo is created
 * once in [main] before the initial config is loaded, then passed in here so
 * the same instance is reused throughout the application.
 *
 * ### Dependency order
 * 1. Coroutine scope
 * 2. Shared HTTP client
 * 3. Infrastructure (buses, plugin manager)
 * 4. Settings store + reactive config state
 * 5. Domain services (history, audio, updater, localisation, themes)
 * 6. Active service resolution
 * 7. Use cases
 * 8. Stores
 *
 * @param appData       App data directory (JAR-relative or OS fallback).
 * @param loggerFactory Logger factory used throughout the application.
 * @param settingsRepo  The single [SettingsRepository] instance for the process.
 * @param initialConfig Configuration already loaded from [settingsRepo] at startup.
 */
suspend fun buildDependencies(
    appData: File,
    loggerFactory: LoggerFactory,
    settingsRepo: SettingsRepository,
    initialConfig: Configuration
): AppDependencies {

    // ---- 1. Coroutine scope ----

    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("AppScope")
    )

    // ---- 2. Shared HTTP client ----
    // One HttpClient = one connection pool for the host app.
    // Plugins use their own KtorHttpClient wrapper (sandboxed, separate pool).

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    // ---- 3. Infrastructure ----

    val notificationBus = NotificationBus()
    val appEventBus     = AppEventBus()

    val pluginManager = PluginManager(
        appDataDirectory            = appData,
        settingsRepository          = settingsRepo,
        pluginFingerprintRepository = PluginFingerprintRepository(appData, Json { ignoreUnknownKeys = true; isLenient = true }),
        pluginKeyValueStore         = PluginKeyValueStore(appData),
        loggerFactory               = loggerFactory,
        notificationBus             = notificationBus
    )

    // ---- 4. Settings store + reactive config ----

    val settingsStore = SettingsStore(
        settingsRepository   = settingsRepo,
        eventBus             = appEventBus,
        logger               = loggerFactory.getLogger("SettingsStore"),
        scope                = appScope,
        initialConfiguration = initialConfig
    )

    // Use workingConfiguration so in-progress edits are reflected in real time
    // (e.g. instant translation picks up a newly toggled setting before save).
    val configState: StateFlow<Configuration> = settingsStore.state
        .map { it.workingConfiguration }
        .stateIn(appScope, SharingStarted.Eagerly, initialConfig)

    // ---- 5. Domain services ----

    val historyRepo = HistoryRepository(
        appDataDirectory = appData,
        logger           = loggerFactory.getLogger("HistoryRepository"),
        json             = Json { ignoreUnknownKeys = true; isLenient = true }
    )

    val audioPlayer = JLayerAudioPlayer(
        scope  = appScope,
        logger = loggerFactory.getLogger("AudioPlayer")
    )

    val updater = Updater(
        repoOwner  = "ahatem",
        repoName   = "qtranslate",
        httpClient = httpClient,
        logger     = loggerFactory.getLogger("Updater")
    )

    val localizationManager = LocalizationManager(
        appDataDirectory = appData,
        parser           = LanguageTomlParser(),
        logger           = loggerFactory.getLogger("LocalizationManager")
    )

    val themeManager = ThemeManager(
        appDataDirectory = appData,
        logger           = loggerFactory.getLogger("ThemeManager")
    )

    val iconManager = IconManager(pluginManager)

    // ---- 6. Active service resolution ----

    val activeServiceManager = ActiveServiceManager(
        activeServices = pluginManager.activeServices,
        configuration  = configState
    )

    // ---- 7. Use cases ----

    val checkForUpdatesUseCase = CheckForUpdatesUseCase(
        currentVersion  = AppConstants.APP_VERSION,
        settingsState   = configState,
        updater         = updater,
        notificationBus = notificationBus,
        loggerFactory   = loggerFactory
    )

    val handleTtsUseCase = HandleTextToSpeechUseCase(
        activeServiceManager = activeServiceManager,
        settingsState        = configState,
        audioPlayer          = audioPlayer,
        loggerFactory        = loggerFactory
    )

    val summarizeUseCase = SummarizeUseCase(
        activeServiceManager = activeServiceManager,
        loggerFactory        = loggerFactory
    )

    val rewriteUseCase = RewriteUseCase(
        activeServiceManager = activeServiceManager,
        loggerFactory        = loggerFactory
    )

    val translateUseCase = TranslateTextUseCase(
        scope                = appScope,
        settingsState        = configState,
        activeServiceManager = activeServiceManager,
        historyRepository    = historyRepo,
        summarizeUseCase     = summarizeUseCase,
        rewriteUseCase       = rewriteUseCase,
        loggerFactory        = loggerFactory
    )

    // ---- 8. Stores ----

    val mainStore = MainStore(
        scope                      = appScope,
        settingsState              = configState,
        historyRepository          = historyRepo,
        checkForUpdatesUseCase     = checkForUpdatesUseCase,
        handleTextToSpeechUseCase  = handleTtsUseCase,
        performSpellCheckUseCase   = PerformSpellCheckUseCase(activeServiceManager, loggerFactory),
        selectActiveServiceUseCase = SelectActiveServiceUseCase(
            activeServices = pluginManager.activeServices,
            settingsState  = configState,
            scope          = appScope,
            loggerFactory  = loggerFactory
        ),
        translateTextUseCase       = translateUseCase,
        swapLanguagesUseCase       = SwapLanguagesUseCase(),
        ocrAndTranslateUseCase     = OcrAndTranslateUseCase(activeServiceManager, loggerFactory),
        summarizeUseCase           = summarizeUseCase,
        rewriteUseCase             = rewriteUseCase
    )

    return AppDependencies(
        appScope            = appScope,
        mainStore           = mainStore,
        settingsStore       = settingsStore,
        pluginManager       = pluginManager,
        iconManager         = iconManager,
        themeManager        = themeManager,
        localizationManager = localizationManager,
        notificationBus     = notificationBus
    )
}