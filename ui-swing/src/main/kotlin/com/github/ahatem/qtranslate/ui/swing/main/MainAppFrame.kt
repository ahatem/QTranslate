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
import com.github.ahatem.qtranslate.core.settings.data.CloseButtonBehavior
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
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
import com.github.ahatem.qtranslate.ui.swing.shared.util.*
import com.github.ahatem.qtranslate.ui.swing.snippingtool.SnippingToolDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.util.*
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.system.exitProcess

class MainAppFrame(
    private val mainStore: MainStore,
    private val settingsStore: SettingsStore,
    private val iconManager: IconManager,
    private val themeManager: ThemeManager,
    private val pluginManager: PluginManager,
    private val localizer: LocalizationManager,
    private val notificationBus: com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
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
        onListenToText = { text ->
            mainStore.dispatch(MainIntent.ListenToText(TextSource.Input, text))
        },
        onOpenSnippingTool = { openSnippingTool() },
        onReplaceWithTranslation = { text ->
            mainStore.dispatch(MainIntent.ReplaceWithTranslation(text))
        },
        onCycleTargetLanguage = {
            mainStore.dispatch(MainIntent.CycleTargetLanguage)
        }
    )

    private val statusBarController = StatusBarController(
        statusBar = mainContentView.statusBar,
        scope = appScope,
        defaultMessage = localizer.getString("main_window_status_bar.ready_message")
    )

    init {
        globalKeyListener.updateBindings(
            settingsStore.state.value.originalConfiguration.hotkeys
        )

        SwingUtilities.invokeLater {
            contentPane.add(mainContentView, BorderLayout.CENTER)
            defaultCloseOperation = DO_NOTHING_ON_CLOSE

            val config = settingsStore.state.value.workingConfiguration
            val scale = config.uiScale / 100f

            minimumSize = Dimension(
                (AppConstants.MIN_WINDOW_WIDTH * scale).toInt(),
                (AppConstants.MIN_WINDOW_HEIGHT * scale).toInt()
            )
            val savedSize = config.mainWindowSize
            preferredSize = if (savedSize != null) {
                Dimension(savedSize.width, savedSize.height)
            } else {
                Dimension(
                    (AppConstants.DEFAULT_WINDOW_WIDTH * scale).toInt(),
                    (AppConstants.DEFAULT_WINDOW_HEIGHT * scale).toInt()
                )
            }

            val savedPosition = config.mainWindowPosition
            if (savedPosition != null) {
                setLocation(savedPosition.x, savedPosition.y)
            }
            iconImages = loadIcons()

            mainContentView.render(mainStore.state.value, settingsStore.state.value)
            pack()
            if (config.mainWindowPosition == null) setLocationRelativeTo(null)

            setupWindowListeners()
            setupMenuBar()
            setupTrayMenu()
            setupGlobalHotkeys()

            observeStateAndEvents()
            isVisible = true

            // applyOrientation must run AFTER switchLayout's invokeLater has fired.
            // switchLayout() queues an invokeLater internally — if we call
            // applyOrientation directly here it runs before the layout tree exists.
            // Queuing a second invokeLater guarantees it executes after the first.
            SwingUtilities.invokeLater {
                applyOrientation(localizer.isRtl)
            }
        }
    }

    private fun observeStateAndEvents() {
        val handler = CoroutineExceptionHandler { _, throwable ->
            System.err.println("Unhandled exception in MainAppFrame coroutine: ${throwable.message}")
            throwable.printStackTrace()
        }

        // Theme and font updates — observe originalConfiguration (saved state only).
        appScope.launch(handler) {
            settingsStore.state
                .map { it.originalConfiguration }
                .distinctUntilChanged { a, b ->
                    a.themeId == b.themeId &&
                            a.useUnifiedTitleBar == b.useUnifiedTitleBar &&
                            a.uiFontConfig == b.uiFontConfig &&
                            a.uiScale == b.uiScale
                }
                .drop(1)
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
                            // Filter available languages by pinned list (Yan's request).
                            // If pinnedLanguages is empty, show all languages.
                            val filteredState = run {
                                val pinned = settingsState.workingConfiguration.pinnedLanguages
                                if (pinned.isEmpty()) mainState
                                else mainState.copy(
                                    availableLanguages = mainState.availableLanguages.filter {
                                        it.tag == "auto" || it.tag in pinned
                                    }
                                )
                            }
                            mainContentView.render(filteredState, settingsState)

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

        // Loading indicator — show when:
        // a) quick translate is loading (main window hidden, no dialog visible), OR
        // b) replace-with-translation is running (main window may be visible, but
        //    LoadingIndicator.focusableWindowState=false so it never steals focus)
        appScope.launch(handler) {
            mainStore.state
                .map { Triple(it.isLoading, it.isQuickTranslateDialogVisible, it.isReplacingSelection) }
                .distinctUntilChanged()
                .collect { (isLoading, isDialogVisible, isReplacing) ->
                    withContext(Dispatchers.Swing) {
                        val shouldShow = isLoading && (!isVisible && !isDialogVisible || isReplacing)
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

        // Paste translation back — replaces selected text with the translation result
        appScope.launch(handler) {
            mainStore.events
                .filterIsInstance<MainEvent.PasteTranslation>()
                .collect { event ->
                    if (event.translatedText.isNotBlank()) {
                        pasteTextToActiveApp(event.translatedText)
                    }
                }
        }

        // Plugin and system notifications — routed to the status bar
        appScope.launch(handler) {
            notificationBus.notifications.collect { notification ->
                withContext(Dispatchers.Swing) {
                    statusBarController.handleNotification(notification)
                }
            }
        }

        // Hotkey binding changes — re-register whenever saved config changes
        appScope.launch(handler) {
            settingsStore.state
                .map { it.originalConfiguration.hotkeys }
                .distinctUntilChanged()
                .drop(1)
                .collect { bindings ->
                    globalKeyListener.updateBindings(bindings)
                    globalKeyListener.setHotkeysEnabled(
                        settingsStore.state.value.originalConfiguration.isGlobalHotkeysEnabled
                    )
                    withContext(Dispatchers.Swing) { registerLocalHotkeys() }
                }
        }

        // Language / RTL changes — observe originalConfiguration (saved state only).
        appScope.launch(handler) {
            settingsStore.state
                .map { it.originalConfiguration.interfaceLanguage }
                .distinctUntilChanged()
                .drop(1)
                .collect { languageCode ->
                    withContext(Dispatchers.IO) {
                        localizer.loadLanguage(
                            com.github.ahatem.qtranslate.api.language.LanguageCode(languageCode)
                        )
                    }
                    withContext(Dispatchers.Swing) {
                        applyOrientation(localizer.isRtl)
                    }
                }
        }
    }

    /**
     * Registers LOCAL-scope hotkeys via Swing InputMap/ActionMap.
     * These only fire when QTranslate has focus — they never intercept keys
     * from other applications. Called after globalKeyListener.initialize() and
     * whenever bindings change (Dinar's per-action scope request).
     */
    private fun registerLocalHotkeys() {
        rootPane.inputMap.clear()
        rootPane.actionMap.clear()

        globalKeyListener.getLocalBindings().forEach { binding ->
            val keyStroke = binding.toKeyStroke() ?: return@forEach
            val actionKey = "localHotkey_${binding.action.name}"
            rootPane.inputMap.put(keyStroke, actionKey)
            rootPane.actionMap.put(actionKey, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    globalKeyListener.dispatchAction(binding.action)
                }
            })
        }
    }


    private fun pasteTextToActiveApp(text: String) {
        appScope.launch {
            runCatching {
                delay(150) // let any in-flight UI work settle

                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(text), null)

                val robot = Robot()
                robot.autoDelay = 20
                robot.keyPress(KeyEvent.VK_CONTROL)
                robot.keyPress(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_CONTROL)
            }.onFailure {
                System.err.println("Failed to paste translation: ${it.message}")
            }
        }
    }

    private fun applyOrientation(isRtl: Boolean) {
        val orientation = if (isRtl)
            ComponentOrientation.RIGHT_TO_LEFT
        else
            ComponentOrientation.LEFT_TO_RIGHT

        val locale = Locale.forLanguageTag(localizer.activeLanguage.tag)
        Locale.setDefault(locale)
        JComponent.setDefaultLocale(locale)

        applyComponentOrientation(orientation)
        revalidate()
        repaint()
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
                saveWindowBounds()
                handleCloseButton()
            }

            override fun windowIconified(e: WindowEvent?) {
                saveWindowBounds()
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

    private fun saveWindowBounds() {
        val s = size
        val p = location
        settingsStore.dispatch(
            SettingsIntent.ToggleSetting {
                it.copy(
                    mainWindowSize     = Size(s.width, s.height),
                    mainWindowPosition = Position(p.x.coerceAtLeast(0), p.y.coerceAtLeast(0))
                )
            }
        )
    }

    /**
     * Handles the window close (X) button according to [Configuration.closeButtonBehavior].
     *
     * - [CloseButtonBehavior.MINIMIZE_TO_TRAY] — hides the window silently.
     * - [CloseButtonBehavior.EXIT]             — disposes the window and exits.
     * - [CloseButtonBehavior.ASK]              — shows a dialog with both options.
     *   If the user checks "Remember my choice", saves it to configuration so
     *   the dialog never appears again.
     */
    private fun handleCloseButton() {
        when (settingsStore.state.value.originalConfiguration.closeButtonBehavior) {
            CloseButtonBehavior.MINIMIZE_TO_TRAY -> isVisible = false
            CloseButtonBehavior.EXIT             -> dispose()
            CloseButtonBehavior.ASK              -> showCloseDialog()
        }
    }

    private fun showCloseDialog() {
        val rememberCheck = JCheckBox(localizer.getString("close_dialog.remember_choice"))

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel(localizer.getString("close_dialog.message")))
            add(Box.createVerticalStrut(12))
            add(rememberCheck)
        }

        val minimizeOption = localizer.getString("close_dialog.minimize_to_tray")
        val exitOption     = localizer.getString("close_dialog.exit")
        val cancelOption   = localizer.getString("common.cancel")

        val result = JOptionPane.showOptionDialog(
            this,
            panel,
            localizer.getString("close_dialog.title"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            arrayOf(minimizeOption, exitOption, cancelOption),
            minimizeOption  // default button
        )

        when (result) {
            0 -> { // Minimize to tray
                if (rememberCheck.isSelected) saveClosePreference(CloseButtonBehavior.MINIMIZE_TO_TRAY)
                isVisible = false
            }
            1 -> { // Exit
                if (rememberCheck.isSelected) saveClosePreference(CloseButtonBehavior.EXIT)
                dispose()
            }
            // 2 = Cancel, -1 = dialog dismissed — do nothing
        }
    }

    private fun saveClosePreference(behavior: CloseButtonBehavior) {
        settingsStore.dispatch(
            SettingsIntent.ToggleSetting { it.copy(closeButtonBehavior = behavior) }
        )
        // SaveChanges so the preference persists immediately without requiring
        // the user to open Settings and click Apply.
        settingsStore.dispatch(SettingsIntent.SaveChanges)
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
                registerLocalHotkeys()
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
            versionText = localizer.getString("common.version", AppConstants.APP_VERSION),
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

        fun handleNotification(notification: com.github.ahatem.qtranslate.core.shared.notification.AppNotification) {
            clearMessageJob?.cancel()
            renderMessage(notification.body, notification.type)
            if (notification.type == NotificationType.INFO) {
                clearMessageJob = scope.launch {
                    delay(AppConstants.STATUS_MESSAGE_DURATION_MS)
                    if (statusBar.text() == notification.body) {
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