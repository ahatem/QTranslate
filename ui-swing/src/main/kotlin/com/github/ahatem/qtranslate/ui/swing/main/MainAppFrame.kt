package com.github.ahatem.qtranslate.ui.swing.main

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.components.FlatButton
import com.formdev.flatlaf.util.FontUtils
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.mvi.MainEvent
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.ui.swing.about.InfoDialog
import com.github.ahatem.qtranslate.ui.swing.about.InfoDialogState
import com.github.ahatem.qtranslate.ui.swing.main.layout.LayoutManager
import com.github.ahatem.qtranslate.ui.swing.main.menus.*
import com.github.ahatem.qtranslate.ui.swing.main.statusbar.StatusBar
import com.github.ahatem.qtranslate.ui.swing.main.statusbar.StatusBarState
import com.github.ahatem.qtranslate.ui.swing.quciktranslate.*
import com.github.ahatem.qtranslate.ui.swing.settings.SettingsDialog
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.copyToClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledEditorFallbackFont
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledEditorFont
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledUiFont
import com.github.ahatem.qtranslate.ui.swing.snippingtool.SnippingToolDialog
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.tulskiy.keymaster.common.Provider
import kotlinx.coroutines.*
import java.awt.ComponentOrientation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.system.exitProcess

class MainAppFrame(
    private val mainStore: MainStore,
    private val settingsStore: SettingsStore,
    private val iconManager: IconManager,
    private val themeManager: ThemeManager,
    private val pluginManager: PluginManager,
    private val localizer: LocalizationManager
) : JFrame("QTranslate") {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("MainAppFrame"))

    private var trayIcon: TrayIcon? = null

    private val aboutDialog by lazy { InfoDialog(this) }
    private val loadingIndicator by lazy { LoadingIndicator(this) }

    private val quickTranslateDialog by lazy {
        QuickTranslateDialog(
            owner = this,
            iconManager = iconManager,
            onDismiss = { mainStore.dispatch(MainIntent.HideQuickTranslate) },
            onTranslatorSelected = { serviceId ->
                settingsStore.dispatch(
                    SettingsIntent.UpdateServiceInActivePreset(ServiceType.TRANSLATOR, serviceId)
                )
                mainStore.dispatch(MainIntent.Translate())
            },
            onListen = { mainStore.dispatch(MainIntent.ListenToText(TextSource.Output)) },
            onCopy = { mainStore.state.value.translatedText.copyToClipboard() },
            onSavePosition = { pos ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(popupLastKnownPosition = pos) }
                )
            },
            onSaveSize = { size ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(popupLastKnownSize = size) }
                )
            },
            onPinToggled = { mainStore.dispatch(MainIntent.ToggleQuickTranslateDialogPin) }
        )
    }

    // Do NOT cache as a lazy — SettingsDialog cancels its own scope on dispose().
    // Reusing a disposed dialog gives a dead scope where Apply never enables
    // and Cancel/Close buttons stop working. Always create a fresh instance.
    private fun createSettingsDialog() = SettingsDialog(
        owner = this,
        settingsStore = settingsStore,
        pluginManager = pluginManager,
        iconManager = iconManager,
        themeManager = themeManager,
        localizationManager = localizer
    )

    private val mainContentView: MainContentView = MainContentView(
        iconManager = iconManager,
        localizer = localizer,
        dispatch = { mainStore.dispatch(it) },
        dispatchSettings = { settingsStore.dispatch(it) },
        onOpenSnippingTool = { openSnippingTool() }
    )

    private val globalKeyListener = MainGlobalKeyListener(
        scope = appScope,
        onShowApp = { text ->
            mainStore.dispatch(MainIntent.UpdateInputText(text))
            mainStore.dispatch(MainIntent.Translate(text))
            runOnUi { showAndFocus() }
        },
        onShowQuickTranslate = { text ->
            appScope.launch { mainStore.dispatch(MainIntent.ShowQuickTranslate(text)) }
        },
        onListenToText = { text -> mainStore.dispatch(MainIntent.ListenToText(TextSource.Input, text)) },
        onOpenSnippingTool = { openSnippingTool() }
    )

    private val statusBarController = StatusBarController(
        statusBar = mainContentView.statusBar,
        scope = appScope,
        defaultMessage = localizer.getString("main_window_status_bar.ready_message")
    )

    init {
        SwingUtilities.invokeLater {
            contentPane.add(mainContentView, BorderLayout.CENTER)
            defaultCloseOperation = DO_NOTHING_ON_CLOSE

            val config = settingsStore.state.value.workingConfiguration
            val scale = config.uiScale / 100f

            minimumSize = Dimension(
                (AppConstants.MIN_WINDOW_WIDTH * scale).toInt(),
                (AppConstants.MIN_WINDOW_HEIGHT * scale).toInt()
            )
            preferredSize = Dimension(
                (AppConstants.DEFAULT_WINDOW_WIDTH * scale).toInt(),
                (AppConstants.DEFAULT_WINDOW_HEIGHT * scale).toInt()
            )
            iconImages = loadIcons()

            mainContentView.render(mainStore.state.value, settingsStore.state.value)
            pack()
            setLocationRelativeTo(null)

            // Apply orientation based on the already-loaded language.
            // localizer.isRtl is accurate here because Main.kt calls
            // loadLanguage() before this window is constructed.
            applyOrientation(localizer.isRtl)

            setupWindowListeners()
            setupMenuBar()
            setupTrayMenu()
            setupGlobalHotkeys()

            observeStateAndEvents()
            isVisible = true
        }
    }

    private fun observeStateAndEvents() {
        val handler = CoroutineExceptionHandler { _, throwable ->
            System.err.println("Unhandled exception in MainAppFrame coroutine: ${throwable.message}")
            throwable.printStackTrace()
        }

        // Theme and font updates
        appScope.launch(handler) {
            settingsStore.state
                .map { it.workingConfiguration }
                .distinctUntilChanged { a, b ->
                    a.themeId == b.themeId &&
                            a.useUnifiedTitleBar == b.useUnifiedTitleBar &&
                            a.uiFontConfig == b.uiFontConfig &&
                            a.uiScale == b.uiScale
                }
                .collect { config ->
                    withContext(Dispatchers.Swing) {
                        try {
                            val theme = themeManager.findThemeById(config.themeId)
                            themeManager.applyTheme(theme)

                            val scaledFont = config.scaledUiFont
                            val defaultFont = FontUtils.getCompositeFont(
                                scaledFont.name,
                                Font.PLAIN,
                                scaledFont.size
                            )
                            UIManager.put("defaultFont", defaultFont)
                            UIManager.put("TitlePane.unifiedBackground", config.useUnifiedTitleBar)

                            FlatLaf.updateUI()
                        } catch (e: Exception) {
                            System.err.println("Failed to apply theme: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
        }

        // Main content and QuickTranslate dialog rendering
        appScope.launch(handler) {
            mainStore.state.combine(settingsStore.state) { m, s -> m to s }
                .distinctUntilChanged()
                .collect { (mainState, settingsState) ->
                    withContext(Dispatchers.Swing) {
                        try {
                            mainContentView.render(mainState, settingsState)

                            if (mainState.isQuickTranslateDialogVisible || quickTranslateDialog.isVisible) {
                                val dialogState = mapToQuickTranslateState(
                                    mainState,
                                    settingsState.workingConfiguration
                                )
                                quickTranslateDialog.render(dialogState)
                            }
                        } catch (e: Exception) {
                            System.err.println("Failed to render UI: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
        }

        // Loading indicator for quick translate dialog
        appScope.launch(handler) {
            mainStore.state
                .map { it.isLoading to it.isQuickTranslateDialogVisible }
                .distinctUntilChanged()
                .collect { (isLoading, isDialogVisible) ->
                    withContext(Dispatchers.Swing) {
                        val shouldShow = isLoading && !isVisible && !isDialogVisible
                        loadingIndicator.render(LoadingIndicatorState(isVisible = shouldShow))
                    }
                }
        }

        // Status bar messages
        appScope.launch(handler) {
            mainStore.events
                .filterIsInstance<MainEvent.UpdateStatusBar>()
                .collect { event ->
                    withContext(Dispatchers.Swing) {
                        statusBarController.handleEvent(event)
                    }
                }
        }

        // Language / RTL changes
        // Observes localizer.activeLanguageFlow so that when the user picks a new
        // language in the settings panel, the whole window re-orients immediately.
        appScope.launch(handler) {
            localizer.activeLanguageFlow
                .collect { _ ->
                    withContext(Dispatchers.Swing) {
                        applyOrientation(localizer.isRtl)
                    }
                }
        }
    }

    /**
     * Applies LEFT_TO_RIGHT or RIGHT_TO_LEFT orientation to the entire window.
     *
     * ### Flicker prevention
     * `applyComponentOrientation` + `updateComponentTreeUI` causes multiple
     * intermediate repaints which produce a visible flicker. We suppress this by:
     * 1. Hiding the window briefly while the layout changes (if already visible)
     * 2. Using `revalidate()` + `repaint()` instead of the heavier `updateComponentTreeUI`
     *    which reinstalls the entire Look and Feel on every component unnecessarily.
     *
     * Called at construction (window not yet visible — no flicker risk) and
     * whenever [LocalizationManager.activeLanguageFlow] emits a new value.
     */
    private fun applyOrientation(isRtl: Boolean) {
        val orientation = if (isRtl)
            ComponentOrientation.RIGHT_TO_LEFT
        else
            ComponentOrientation.LEFT_TO_RIGHT

        // Suppress flicker by hiding during layout change if window is visible.
        // At construction time isVisible=false so this is a no-op.
        val wasVisible = isVisible
        if (wasVisible) isVisible = false

        applyComponentOrientation(orientation)
        rootPane.revalidate()
        rootPane.repaint()

        if (wasVisible) isVisible = true
    }

    private fun runOnUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    private fun showAndFocus() {
        isVisible = true
        state = NORMAL
        toFront()
        mainContentView.requestFocusOnInput()
    }

    private fun openSnippingTool() {
        runOnUi {
            isVisible = false
            state = ICONIFIED
            toBack()
        }

        appScope.launch {
            delay(200)
            withContext(Dispatchers.Swing) {
                SnippingToolDialog(this@MainAppFrame, mainStore)
            }
        }
    }

    // ... (window listeners stay the same) ...

    private fun createOptionsPopupMenu(): JPopupMenu {
        val currentConfig = settingsStore.state.value.workingConfiguration
        val layouts = LayoutManager.getAvailableLayouts().map {
            LayoutPresetInfo(it.id, localizer.getString("main_window_main_menu.${it.localizeId}"))
        }

        val actions = MenuActions(
            onToggleSpellCheck = { enabled ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(isSpellCheckingEnabled = enabled) }
                )
            },
            onToggleInstantTranslation = { enabled ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(isInstantTranslationEnabled = enabled) }
                )
            },
            onToggleExtraOutput = { enabled ->
                val newType = if (enabled) ExtraOutputType.BackwardTranslate else ExtraOutputType.None
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(extraOutputType = newType) }
                )
            },
            onShowDictionary = { /* TODO */ },
            onShowHistory = { /* TODO */ },
            onShowSettings = {
                val dialog = createSettingsDialog()
                // Apply current orientation to the dialog so it opens
                // in the correct direction without requiring a restart.
                dialog.applyComponentOrientation(
                    if (localizer.isRtl) ComponentOrientation.RIGHT_TO_LEFT
                    else ComponentOrientation.LEFT_TO_RIGHT
                )
                dialog.isVisible = true
            },
            onShowHowToUse = { /* TODO */ },
            onShowAboutQTranslate = { onShowAboutDialog() },
            onContactUs = { /* TODO */ },
            onToggleAutoCheckForUpdates = { enabled ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(autoCheckForUpdates = enabled) }
                )
            },
            onCheckForUpdates = { mainStore.dispatch(MainIntent.CheckForUpdates) },
            onExitApplication = { dispose() },
            onChangeLayoutPreset = { layoutId ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(layoutPresetId = layoutId) }
                )
            },
            onToggleHistoryControls = { enabled ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting {
                        it.copy(toolbarVisibility = it.toolbarVisibility.copy(isHistoryBarVisible = enabled))
                    }
                )
            },
            onToggleLanguageBar = { enabled ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting {
                        it.copy(toolbarVisibility = it.toolbarVisibility.copy(isLanguageBarVisible = enabled))
                    }
                )
            },
            onToggleServicesPanel = { enabled ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting {
                        it.copy(toolbarVisibility = it.toolbarVisibility.copy(isServicesPanelVisible = enabled))
                    }
                )
            },
            onToggleStatusBar = { enabled ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting {
                        it.copy(toolbarVisibility = it.toolbarVisibility.copy(isStatusBarVisible = enabled))
                    }
                )
            }
        )

        val strings = MenuStrings(
            spellCheck = localizer.getString("main_window_main_menu.spell_check"),
            instantTranslation = localizer.getString("main_window_main_menu.instant_translation"),
            extraOutput = localizer.getString("main_window_main_menu.show_extra_output"),
            viewOptions = localizer.getString("main_window_main_menu.options_submenu"),
            dictionary = localizer.getString("system_tray_menu.dictionary"),
            history = localizer.getString("system_tray_menu.history"),
            settings = localizer.getString("main_window_main_menu.settings"),
            help = localizer.getString("main_window_main_menu.help_submenu"),
            howToUse = localizer.getString("main_window_main_menu.how_to_use"),
            aboutQTranslate = localizer.getString("main_window_main_menu.about_qtranslate"),
            contactUs = localizer.getString("main_window_main_menu.contact_us"),
            autoCheckForUpdates = localizer.getString("main_window_main_menu.auto_check_for_updates"),
            checkForUpdates = localizer.getString("main_window_main_menu.check_for_updates"),
            exit = localizer.getString("main_window_main_menu.exit"),
            layoutPresets = localizer.getString("main_window_main_menu.layout_presets"),
            showHistoryControls = localizer.getString("main_window_main_menu.show_history_bar"),
            showLanguageBar = localizer.getString("main_window_main_menu.show_language_bar"),
            showServicesPanel = localizer.getString("main_window_main_menu.show_services_panel"),
            showStatusBar = localizer.getString("main_window_main_menu.show_status_bar")
        )

        return MainMenuPopup(currentConfig, actions, strings, layouts)
    }

    private fun setupTrayMenu() {
        if (!SystemTray.isSupported()) return

        val tray = SystemTray.getSystemTray()
        val image = try {
            ImageIO.read(javaClass.classLoader.getResourceAsStream("icons/app/32.png"))
                ?: throw IllegalStateException("Tray icon not found")
        } catch (e: Exception) {
            println("Failed to load tray icon: ${e.message}")
            return
        }

        trayIcon = TrayIcon(image, "QTranslate").apply {
            isImageAutoSize = true
            toolTip = "QTranslate"

            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        val menu = createTrayPopupMenu()
                        val dummy = JFrame().apply {
                            isUndecorated = true
                            isVisible = true
                            setLocation(e.xOnScreen, e.yOnScreen)
                        }
                        menu.show(dummy, 0, 0)
                        dummy.dispose()
                        menu.setLocation(e.xOnScreen, e.yOnScreen - menu.height)
                    }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                        runOnUi { showAndFocus() }
                    }
                }
            })
        }

        try {
            tray.add(trayIcon!!)
        } catch (e: AWTException) {
            println("Failed to add tray icon: ${e.message}")
            trayIcon = null
        }
    }

    private fun createTrayPopupMenu(): JPopupMenu {
        val currentConfig = settingsStore.state.value.workingConfiguration

        val strings = TrayMenuStrings(
            showApplication = localizer.getString("system_tray_menu.show_application"),
            dictionary = localizer.getString("system_tray_menu.dictionary"),
            textRecognition = localizer.getString("system_tray_menu.recognize_text"),
            history = localizer.getString("system_tray_menu.history"),
            settings = localizer.getString("system_tray_menu.settings"),
            toggleHotkeys = localizer.getString("system_tray_menu.enable_hotkeys"),
            exit = localizer.getString("system_tray_menu.exit")
        )

        val actions = TrayMenuActions(
            onShowApplication = { runOnUi { showAndFocus() } },
            onShowDictionary = { /* TODO */ },
            onRecognizeText = { openSnippingTool() },
            onShowHistory = { /* TODO */ },
            onShowSettings = { /* TODO */ },
            onToggleHotkeys = { enabled ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(isGlobalHotkeysEnabled = enabled) }
                )
            },
            onExitApplication = { dispose() }
        )

        return TrayMenuPopup(actions, strings, currentConfig.isGlobalHotkeysEnabled)
    }

    private fun setupWindowListeners() {
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) {
                mainContentView.requestFocusOnInput()
            }

            override fun windowClosing(e: WindowEvent?) {
                isVisible = false
            }

            override fun windowIconified(e: WindowEvent?) {
                isVisible = false
            }

            override fun windowDeiconified(e: WindowEvent?) {
                isVisible = true
                toFront()
            }

            override fun windowClosed(e: WindowEvent?) {
                appScope.cancel()
                trayIcon?.let { SystemTray.getSystemTray().remove(it) }
                trayIcon = null
                exitProcess(0)
            }
        })
    }

    private fun setupMenuBar() {
        val settingsButton = createButtonWithIcon(iconManager, "icons/lucide/settings.svg", 18).apply {
            buttonType = FlatButton.ButtonType.toolBarButton
            toolTipText = localizer.getString("main_window_main_menu.settings")
            addActionListener {
                val popupMenu = createOptionsPopupMenu()
                popupMenu.show(this, 0, height)
            }
        }

        jMenuBar = JMenuBar().apply {
            add(Box.createHorizontalGlue())
            add(settingsButton)
        }
    }

    private fun loadIcons(): List<Image> {
        return listOf(16, 32, 64, 128).mapNotNull { size ->
            try {
                ImageIO.read(javaClass.classLoader.getResourceAsStream("icons/app/$size.png"))
            } catch (e: Exception) {
                println("Failed to load icon ($size): ${e.message}")
                null
            }
        }
    }

    // REMOVED: updateSettings() helper method - now using proper intents directly

    private fun mapToQuickTranslateState(mainState: MainState, config: Configuration): QuickTranslateDialogState {
        val displaySourceLanguage = mainState.detectedSourceLanguage ?: mainState.sourceLanguage

        val activePreset = config.getActivePreset()
        val selectedTranslatorId = activePreset?.selectedServices?.get(ServiceType.TRANSLATOR)
        val selectedTranslator = mainState.availableServices.find { it.id == selectedTranslatorId }

        return QuickTranslateDialogState(
            isVisible = mainState.isQuickTranslateDialogVisible,
            isLoading = mainState.isLoading,
            translatedText = mainState.translatedText,
            isPinned = mainState.isQuickTranslateDialogPinned,

            sourceLanguage = displaySourceLanguage,
            targetLanguage = mainState.targetLanguage,

            translatorSelectorState = QuickTranslateSelectorState(
                availableTranslators = mainState.getAvailableServicesFor(ServiceType.TRANSLATOR),
                selectedTranslatorId = selectedTranslator?.id
            ),
            actionsState = QuickTranslateActionsState(
                canCopy = mainState.translatedText.isNotBlank(),
                canListen = mainState.translatedText.isNotBlank()
            ),
            config = DialogConfig(
                font = config.scaledEditorFont,
                fallbackFont = config.scaledEditorFallbackFont,
                autoSizeEnabled = config.isPopupAutoSizeEnabled,
                autoPositionEnabled = config.isPopupAutoPositionEnabled,
                transparencyPercentage = config.popupTransparencyPercentage,
                lastKnownSize = config.popupLastKnownSize,
                lastKnownPosition = config.popupLastKnownPosition
            ),
            strings = DialogStrings(
                copyTooltip = localizer.getString("common.copy"),
                listenTooltip = localizer.getString("common.listen"),
                pinTooltip = localizer.getString("common.pin"),
                unpinTooltip = localizer.getString("common.unpin"),
                loadingText = localizer.getString("common.loading")
            )
        )
    }

    private fun setupGlobalHotkeys() {
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) {
                globalKeyListener.initialize()
                val config = settingsStore.state.value.workingConfiguration
                globalKeyListener.setHotkeysEnabled(config.isGlobalHotkeysEnabled)
            }

            override fun windowClosed(e: WindowEvent?) {
                globalKeyListener.shutdown()
                System.runFinalization()
                exitProcess(0)
            }
        })
    }

    private fun onShowAboutDialog() {
        val state = InfoDialogState(
            isVisible = true,
            title = localizer.getString("about_dialog.title"),
            appName = "QTranslate",
            versionText = localizer.getString("common.version", "2.0.0"),
            descriptionHtml = localizer.getString("about_dialog.description"),
            websiteUrl = "https://github.com/ahatem/qtranslate",
            icon = iconManager.getIcon("icons/app/128.png", 32, 32),
            closeButtonText = localizer.getString("common.close")
        )

        runOnUi { aboutDialog.showDialog(state) }
    }

    inner class StatusBarController(
        private val statusBar: StatusBar,
        private val scope: CoroutineScope,
        private val defaultMessage: String,
    ) {
        private var clearMessageJob: Job? = null

        init {
            renderMessage(defaultMessage, NotificationType.INFO)
        }

        fun handleEvent(event: MainEvent.UpdateStatusBar) {
            clearMessageJob?.cancel()

            renderMessage(event.message, event.type)

            if (event.isTemporary) {
                clearMessageJob = scope.launch {
                    delay(AppConstants.STATUS_MESSAGE_DURATION_MS)
                    if (statusBar.text() == event.message) {
                        renderMessage(defaultMessage, NotificationType.INFO)
                    }
                }
            }
        }

        private fun renderMessage(message: String, type: NotificationType) {
            statusBar.render(
                StatusBarState(
                    message = message,
                    type = type,
                    notificationTooltip = localizer.getString("main_window_status_bar.notifications_tooltip"),
                    isNotificationButtonEnabled = true
                )
            )
        }
    }
}

// Global hotkey listener remains the same
class MainGlobalKeyListener(
    private val scope: CoroutineScope,
    private val onShowApp: (String) -> Unit,
    private val onShowQuickTranslate: (String) -> Unit,
    private val onListenToText: (String) -> Unit,
    private val onOpenSnippingTool: () -> Unit
) {

    private var provider: Provider? = null
    private var nativeHookRegistered = false
    private val sequenceListener = CustomSequenceListener()
    private val clipboardLock = AtomicBoolean(false)
    private val hotkeysEnabled = AtomicBoolean(true)
    private var initialized = false

    fun initialize() {
        if (initialized) return
        try {
            initJKeyMaster()
            initJNativeHook()
            initialized = true
        } catch (e: Exception) {
            System.err.println("Hotkey initialization failed: ${e.message}")
            e.printStackTrace()
        }
    }

    fun setHotkeysEnabled(enabled: Boolean) {
        if (!initialized) return
        if (hotkeysEnabled.getAndSet(enabled) == enabled) return

        if (enabled) {
            enableHotkeys()
        } else {
            disableHotkeys()
        }
    }

    fun areHotkeysEnabled(): Boolean = hotkeysEnabled.get()

    fun shutdown() {
        if (!initialized) return
        try {
            provider?.reset()
            provider?.stop()
            provider = null

            if (nativeHookRegistered) {
                GlobalScreen.removeNativeKeyListener(sequenceListener)
                GlobalScreen.unregisterNativeHook()
                nativeHookRegistered = false
            }
        } catch (e: Exception) {
            System.err.println("Hotkey manager shutdown error: ${e.message}")
        } finally {
            initialized = false
        }
    }

    private fun initJKeyMaster() {
        provider = Provider.getCurrentProvider(false)
            ?: throw Exception("Hotkey provider unavailable")

        registerHotkeys()
    }

    private fun registerHotkeys() {
        val p = provider ?: return

        p.register(KeyStroke.getKeyStroke("control Q")) {
            if (hotkeysEnabled.get()) scope.launch { handleSelectedText(onShowQuickTranslate) }
        }

        p.register(KeyStroke.getKeyStroke("control E")) {
            if (hotkeysEnabled.get()) scope.launch { handleSelectedText(onListenToText) }
        }

        p.register(KeyStroke.getKeyStroke("control I")) {
            if (hotkeysEnabled.get()) onOpenSnippingTool()
        }
    }

    private fun enableHotkeys() {
        try {
            provider?.reset()
            registerHotkeys()
        } catch (e: Exception) {
            System.err.println("Enable hotkeys failed: ${e.message}")
        }
    }

    private fun disableHotkeys() {
        try {
            provider?.reset()
        } catch (e: Exception) {
            System.err.println("Disable hotkeys failed: ${e.message}")
        }
    }

    private fun initJNativeHook() {
        try {
            if (!nativeHookRegistered) {
                GlobalScreen.registerNativeHook()
                nativeHookRegistered = true
            }
            GlobalScreen.addNativeKeyListener(sequenceListener)
        } catch (ex: NativeHookException) {
            throw Exception("Native hook registration failed", ex)
        }
    }

    private inner class CustomSequenceListener : NativeKeyListener {
        private var lastCtrlTime = 0L
        private val threshold = 400

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            if (!hotkeysEnabled.get()) return
            if (e.keyCode != NativeKeyEvent.VC_CONTROL) return

            val now = System.currentTimeMillis()
            if (now - lastCtrlTime < threshold) {
                scope.launch { handleSelectedText(onShowApp) }
            }
            lastCtrlTime = now
        }
    }

    private suspend fun handleSelectedText(callback: (String) -> Unit) {
        if (!clipboardLock.compareAndSet(false, true)) return

        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val original = runCatching { clipboard.getContents(null) }.getOrNull()
            val originalText = original?.let {
                runCatching { it.getTransferData(DataFlavor.stringFlavor).toString() }.getOrNull()
            }

            var text: String? = null

            repeat(2) {
                simulateCopy()
                delay(50)
                text = runCatching {
                    if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                        clipboard.getData(DataFlavor.stringFlavor).toString().trim()
                    } else null
                }.getOrNull()

                if (!text.isNullOrEmpty()) return@repeat
            }

            if (text.isNullOrEmpty()) text = originalText ?: ""

            callback(text)

            original?.let { runCatching { clipboard.setContents(it, null) } }
        } finally {
            clipboardLock.set(false)
        }
    }

    private fun simulateCopy() {
        runCatching {
            val robot = Robot()
            robot.autoDelay = 20

            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_CONTROL)
        }.onFailure {
            System.err.println("Copy simulation failed: ${it.message}")
        }
    }
}