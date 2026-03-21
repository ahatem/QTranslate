package com.github.ahatem.qtranslate.ui.swing

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.util.FontUtils
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.domain.usecase.*
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
import com.github.ahatem.qtranslate.ui.swing.main.MainAppFrame
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledUiFont
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import java.awt.Font
import java.awt.Insets
import java.awt.RenderingHints
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.UIManager

///**
// * Console logger implementation for development.
// * Provides colored output for different log levels.
// */
//class ConsoleLoggerFactory(
//    private val minLogLevel: LogLevel = LogLevel.INFO
//) : LoggerFactory {
//    enum class LogLevel(val priority: Int, val colorCode: String) {
//        DEBUG(0, "\u001B[34m"),
//        INFO(1, "\u001B[32m"),
//        WARN(2, "\u001B[33m"),
//        ERROR(3, "\u001B[31m")
//    }
//
//    companion object {
//        private const val RESET = "\u001B[0m"
//    }
//
//    override fun getLogger(name: String): Logger = ConsoleLogger(name, minLogLevel)
//
//    private class ConsoleLogger(
//        private val name: String,
//        private val minLogLevel: LogLevel
//    ) : Logger {
//        @Volatile
//        private var isEnabled = true
//
//        private fun log(level: LogLevel, message: String, error: Throwable? = null) {
//            if (!isEnabled || level.priority < minLogLevel.priority) return
//
//            synchronized(this) {
//                val timestamp = java.time.LocalDateTime.now()
//                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
//                val threadName = Thread.currentThread().name
//                val coloredLevel = "${level.colorCode}[${level.name}]$RESET"
//                val logMessage = "[$timestamp] [$threadName] $coloredLevel [$name] $message"
//
//                println(logMessage)
//                error?.printStackTrace(System.out)
//            }
//        }
//
//        override fun debug(message: String) = log(LogLevel.DEBUG, message)
//        override fun info(message: String) = log(LogLevel.INFO, message)
//        override fun warn(message: String) = log(LogLevel.WARN, message)
//        override fun error(message: String, error: Throwable?) = log(LogLevel.ERROR, message, error)
//    }
//}
//
///**
// * Main application entry point.
// *
// * Responsibilities:
// * 1. Initialize core dependencies (logging, persistence, plugins)
// * 2. Load initial configuration
// * 3. Apply theme and fonts before UI appears
// * 4. Create stores and use cases
// * 5. Launch main window
// */
//fun main() = runBlocking {
//    setSystemProperties()
//    setupRenderingHints()
//
//    // ============================================================
//    // Core Dependencies
//    // ============================================================
//
//    val appData = File("C:\\Users\\cw\\Downloads\\QTranslate")
//    val json = Json { ignoreUnknownKeys = true; isLenient = true }
//    val loggerFactory = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.DEBUG)
//    val mainLogger = loggerFactory.getLogger("Main")
//
//    mainLogger.info("QTranslate starting...")
//
//    val notificationBus = NotificationBus()
//    val appEventBus = AppEventBus()
//
//    // ============================================================
//    // Settings & Plugin Management
//    // ============================================================
//
//    val settingsRepo = SettingsRepository(appData, json, loggerFactory.getLogger("SettingsRepo"))
//    val pluginManager = PluginManager(
//        appData,
//        settingsRepo,
//        PluginFingerprintRepository(appData, json),
//        PluginKeyValueStore(appData),
//        loggerFactory,
//        notificationBus
//    )
//
//    // ============================================================
//    // Load Initial Configuration (Blocking)
//    // ============================================================
//
//    mainLogger.info("Loading initial configuration...")
//
//    val initialConfig = withTimeoutOrNull(AppConstants.CONFIG_LOAD_TIMEOUT_MS) {
//        settingsRepo.loadInitialConfiguration()
//    } ?: run {
//        mainLogger.warn(
//            "Failed to load configuration within ${AppConstants.CONFIG_LOAD_TIMEOUT_MS}ms, using defaults"
//        )
//        Configuration.DEFAULT
//    }
//
//    mainLogger.info("Configuration loaded: theme=${initialConfig.themeId}, scale=${initialConfig.uiScale}")
//
//    // ============================================================
//    // Application Scope & Stores
//    // ============================================================
//
//    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("AppScope"))
//
//    val settingsStore = SettingsStore(
//        settingsRepository = settingsRepo,
//        eventBus = appEventBus,
//        logger = loggerFactory.getLogger("SettingsStore"),
//        scope = appScope,
//        initialConfiguration = initialConfig
//    )
//
//    // Reactive configuration state for use cases
//    // This reads from workingConfiguration (draft) to allow real-time preview
//    val configurationState = settingsStore.state
//        .map { it.workingConfiguration }
//        .stateIn(appScope, SharingStarted.Eagerly, initialConfig)
//
//    // ============================================================
//    // Additional Dependencies
//    // ============================================================
//
//    val updater = Updater("ahatem", "qtranslate", loggerFactory.getLogger("Updater"))
//    val historyRepo = HistoryRepository(appData, loggerFactory.getLogger("HistoryRepo"), json)
//    val audioPlayer = JLayerAudioPlayer(appScope, loggerFactory.getLogger("AudioPlayer"))
//    val localizationManager = LocalizationManager(
//        appData,
//        LanguageTomlParser(),
//        loggerFactory.getLogger("LocalizationManager")
//    )
//    val iconManager = IconManager(pluginManager)
//    val themeManager = ThemeManager(appData, loggerFactory.getLogger("ThemeManager"))
//
//    // ============================================================
//    // Apply Initial UI Configuration
//    // ============================================================
//
//    mainLogger.info("Applying initial UI configuration...")
//    applyUiConfiguration(initialConfig, themeManager)
//
//    // ============================================================
//    // Use Cases & Main Store
//    // ============================================================
//
//    val activeServiceManager = ActiveServiceManager(
//        pluginManager.activeServices,
//        configurationState
//    )
//
//    val mainStore = MainStore(
//        scope = appScope,
//        settingsState = configurationState,
//        historyRepository = historyRepo,
//        checkForUpdatesUseCase = CheckForUpdatesUseCase(configurationState, updater, notificationBus, loggerFactory),
//        handleTextToSpeechUseCase = HandleTextToSpeechUseCase(
//            activeServiceManager,
//            configurationState,
//            audioPlayer,
//            loggerFactory
//        ),
//        performSpellCheckUseCase = PerformSpellCheckUseCase(activeServiceManager, loggerFactory),
//        selectActiveServiceUseCase = SelectActiveServiceUseCase(pluginManager.activeServices, configurationState),
//        translateTextUseCase = TranslateTextUseCase(
//            appScope,
//            configurationState,
//            activeServiceManager,
//            historyRepo,
//            loggerFactory
//        ),
//        swapLanguagesUseCase = SwapLanguagesUseCase(),
//        ocrAndTranslateUseCase = OcrAndTranslateUseCase(activeServiceManager, loggerFactory)
//    )
//
//    // ============================================================
//    // Load Plugins & Launch UI
//    // ============================================================
//
//    mainLogger.info("Loading plugins...")
//    appScope.launch {
//        try {
//            pluginManager.loadAndProcessPlugins()
//            mainLogger.info("Plugins loaded successfully")
//        } catch (e: Exception) {
//            mainLogger.error("Failed to load plugins", e)
//        }
//    }
//
//    mainLogger.info("Launching main window...")
//    SwingUtilities.invokeLater {
//        MainAppFrame(
//            mainStore = mainStore,
//            settingsStore = settingsStore,
//            iconManager = iconManager,
//            themeManager = themeManager,
//            localizer = localizationManager,
//            pluginManager = pluginManager
//        )
//        mainLogger.info("Main window launched")
//    }
//
//    // Keep application alive
//    Thread.currentThread().join()
//}
//
///**
// * Applies UI configuration (theme, fonts) before the window appears.
// * This prevents flash of unstyled content.
// */
//fun applyUiConfiguration(config: Configuration, themeManager: ThemeManager) {
//    // Install custom fonts
//    RubikSansFont.installLazy()
//
//    FlatLaf.setPreferredFontFamily(RubikSansFont.FAMILY)
//    FlatLaf.setPreferredLightFontFamily(RubikSansFont.FAMILY_LIGHT)
//    FlatLaf.setPreferredSemiboldFontFamily(RubikSansFont.FAMILY_SEMI_BOLD)
//
//    // Apply FlatLaf theme
//    val theme = themeManager.findThemeById(config.themeId)
//    themeManager.applyTheme(theme)
//
//    // Apply UI font with scaling (using extension function)
//    val scaledFont = config.scaledUiFont
//    UIManager.put(
//        "defaultFont",
//        FontUtils.getCompositeFont(scaledFont.name, Font.PLAIN, scaledFont.size)
//    )
//
//    // UI tweaks
//    UIManager.put("ScrollBar.trackInsets", Insets(2, 4, 2, 4))
//    UIManager.put("ScrollBar.thumbInsets", Insets(2, 2, 2, 2))
//    UIManager.put("TitlePane.showIcon", false)
//    UIManager.put("ScrollBar.showButtons", false)
//    UIManager.put("TitlePane.unifiedBackground", config.useUnifiedTitleBar)
//}
//
///**
// * Sets system properties for better rendering on various platforms.
// */
//fun setSystemProperties() {
//    System.setProperty("sun.awt.xembedserver", "true")
//    System.setProperty("awt.useSystemAAFontSettings", "lcd")
//    System.setProperty("swing.aatext", "true")
//    System.setProperty("sun.java2d.opengl", "true")
//    System.setProperty("sun.java2d.d3d", "false")
//    System.setProperty("sun.java2d.noddraw", "true")
//    System.setProperty("sun.java2d.xrender", "true")
//}
//
///**
// * Configures rendering hints for better text quality.
// */
//fun setupRenderingHints() {
//    UIManager.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
//    UIManager.put(RenderingHints.KEY_TEXT_LCD_CONTRAST, 140)
//    UIManager.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
//}