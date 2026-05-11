package com.github.ahatem.qtranslate.ui.swing.main

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.components.FlatButton
import com.formdev.flatlaf.util.FontUtils
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.mvi.MainEvent
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.data.*
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.notification.NotificationCode
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.ui.swing.about.InfoDialog
import com.github.ahatem.qtranslate.ui.swing.about.InfoDialogState
import com.github.ahatem.qtranslate.ui.swing.dictionary.DictionaryDialog
import com.github.ahatem.qtranslate.ui.swing.dictionary.DictionaryDialogState
import com.github.ahatem.qtranslate.ui.swing.dictionary.QuickDictionaryDialog
import com.github.ahatem.qtranslate.ui.swing.dictionary.QuickDictionaryConfig
import com.github.ahatem.qtranslate.ui.swing.dictionary.QuickDictionaryDialogState
import com.github.ahatem.qtranslate.ui.swing.dictionary.QuickDictionaryStrings
import com.github.ahatem.qtranslate.ui.swing.history.HistoryDialog
import com.github.ahatem.qtranslate.ui.swing.history.HistoryDialogState
import com.github.ahatem.qtranslate.ui.swing.history.HistoryEntryState
import com.github.ahatem.qtranslate.ui.swing.main.statusbar.ErrorDetailPopup
import com.github.ahatem.qtranslate.ui.swing.main.statusbar.NotificationPopover
import com.github.ahatem.qtranslate.ui.swing.update.UpdateDialog
import com.github.ahatem.qtranslate.ui.swing.update.UpdateDialogState
import java.text.SimpleDateFormat
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
import java.net.URI
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
    private val updateDialog by lazy { UpdateDialog(this) }
    private val historyDialog by lazy { HistoryDialog(this) }
    private val dictionaryDialog by lazy { DictionaryDialog(this) }
    private val loadingIndicator by lazy { LoadingIndicator(this) }

    private val notificationPopover by lazy {
        NotificationPopover(
            emptyLabel = localizer.getString("main_window_status_bar.notifications_empty_tooltip"),
            clearAllLabel = localizer.getString("common.clear_all"),
            onCleared = { statusBarController.onPopoverCleared() },
        )
    }

    private val quickDictionaryDialog by lazy {
        QuickDictionaryDialog(owner = this, iconManager = iconManager)
    }

    /**
     * Controls where the floating dictionary popup positions itself on first open.
     * - `true`  → near the mouse cursor   (global hotkey trigger)
     * - `false` → adjacent to the owner window (auto-lookup from translation)
     * Set before dispatching [MainIntent.ShowQuickDictionary]; read in [buildQuickDictionaryDialogState].
     */
    @Volatile
    private var quickDictionaryPositionNearMouse = true

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
        localizationManager = localizer,
        availableLanguages = { mainStore.state.value.availableLanguages },
        pauseGlobalHotkeys  = { globalKeyListener.setHotkeysEnabled(false) },
        resumeGlobalHotkeys = {
            globalKeyListener.setHotkeysEnabled(
                settingsStore.state.value.workingConfiguration.isGlobalHotkeysEnabled
            )
        },
    )

    private val mainContentView: MainContentView = MainContentView(
        iconManager = iconManager,
        localizer = localizer,
        dispatch = { mainStore.dispatch(it) },
        dispatchSettings = { settingsStore.dispatch(it) },
        onOpenSnippingTool = { openSnippingTool() },
        onNotificationsClicked = { notificationPopover.show(mainContentView.statusBar) }
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
        },
        onShowDictionary = { selectedText ->
            appScope.launch {
                // Toggle: Ctrl+D while popup is open → close it.
                if (mainStore.state.value.isQuickDictionaryVisible) {
                    mainStore.dispatch(MainIntent.HideQuickDictionary)
                    return@launch
                }
                val s = mainStore.state.value
                val lang = when {
                    s.sourceLanguage != LanguageCode.AUTO -> s.sourceLanguage
                    s.detectedSourceLanguage != null      -> s.detectedSourceLanguage!!
                    else                                  -> LanguageCode("en")
                }
                quickDictionaryPositionNearMouse = true   // hotkey — position near cursor
                mainStore.dispatch(MainIntent.ShowQuickDictionary(selectedText, lang))
            }
        },
        onTranslate = { mainStore.dispatch(MainIntent.Translate()) }
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

            // Enforce Input → Output → Extra (→ Input) Tab cycle across all layouts.
            // In Compact layout the policy also switches tabs so hidden panes become
            // visible before Swing calls requestFocusInWindow() on them.
            focusTraversalPolicy = TextPaneCycleFocusPolicy(mainContentView)

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

        // OS dark/light mode watcher — polls every 10 s; re-applies theme when "os_default" is active
        appScope.launch(handler) {
            var lastDarkMode = ThemeManager.isSystemInDarkMode()
            while (true) {
                delay(10_000L)
                val isDark = ThemeManager.isSystemInDarkMode()
                if (isDark != lastDarkMode) {
                    lastDarkMode = isDark
                    val savedThemeId = settingsStore.state.value.originalConfiguration.themeId
                    if (savedThemeId == ThemeManager.OS_DEFAULT_THEME_ID) {
                        withContext(Dispatchers.Swing) {
                            themeManager.applySystemTheme()
                        }
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

                            if (mainState.isQuickDictionaryVisible || quickDictionaryDialog.isVisible) {
                                quickDictionaryDialog.render(
                                    buildQuickDictionaryDialogState(mainState, settingsState.workingConfiguration)
                                )
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

        // Status bar loading spinner
        appScope.launch(handler) {
            mainStore.state
                .map { it.isLoading }
                .distinctUntilChanged()
                .collect { loading ->
                    withContext(Dispatchers.Swing) {
                        statusBarController.setLoading(loading)
                    }
                }
        }

        // Single collector for all MainEvents — avoids channel-race where two separate
        // filterIsInstance collectors compete and one silently drops events it doesn't match.
        appScope.launch(handler) {
            mainStore.events.collect { event ->
                when (event) {
                    is MainEvent.UpdateStatusBar -> withContext(Dispatchers.Swing) {
                        statusBarController.handleEvent(event)
                    }
                    is MainEvent.PasteTranslation -> if (event.translatedText.isNotBlank()) {
                        pasteTextToActiveApp(event.translatedText)
                    }
                    is MainEvent.ShowUpdateDialog -> withContext(Dispatchers.Swing) {
                        showUpdateDialog(NotificationCode.UpdateAvailable(
                            newVersion = event.newVersion,
                            currentVersion = event.currentVersion,
                            releaseNotes = event.releaseNotes,
                            downloadUrl = event.downloadUrl,
                            releaseUrl = event.releaseUrl
                        ))
                    }
                    is MainEvent.CopyToClipboard -> {
                        runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(StringSelection(event.text), null)
                        }
                    }
                }
            }
        }

        // Donation nudge — track successful translations in-memory; show once at 500.
        // Uses scan() to detect isLoading true→false transitions that produced output.
        appScope.launch(handler) {
            var translationCount = 0
            mainStore.state
                .scan(Pair<MainState?, MainState?>(null, null)) { (_, prev), curr -> prev to curr }
                .filter { (prev, curr) ->
                    prev != null && curr != null &&
                    prev.isLoading && !curr.isLoading && curr.translatedText.isNotBlank()
                }
                .collect {
                    translationCount++
                    if (translationCount >= 500 &&
                        !settingsStore.state.value.workingConfiguration.donationNudgeShown
                    ) {
                        settingsStore.dispatch(
                            SettingsIntent.ToggleSetting { it.copy(donationNudgeShown = true) }
                        )
                        withContext(Dispatchers.Swing) { showDonationNudge() }
                    }
                }
        }

        // Background/system notifications — UpdateAvailable → dialog; everything else → popover
        appScope.launch(handler) {
            notificationBus.notifications.collect { notification ->
                withContext(Dispatchers.Swing) {
                    when (val code = notification.code) {
                        is NotificationCode.UpdateAvailable -> showUpdateDialog(code)
                        else -> statusBarController.addToPopover(notification)
                    }
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

        // Auto-lookup single words after a translation completes.
        //
        // Uses scan() to observe (previous, current) pairs so we can detect the exact
        // moment isLoading transitions true→false (= translation finished).  This is the
        // ONLY moment a new auto-lookup is allowed to fire, which prevents the dictionary
        // from reacting to every keystroke.
        //
        // Additional trigger: a relevant *setting* changed (autoSource cycling, panel
        // opening) while we are already idle and a translated result is on screen.
        //
        // isLoading=true is also passed through so the collect block can dismiss a stale
        // popup the instant a new translation starts.
        appScope.launch(handler) {
            mainStore.state
                .combine(settingsStore.state) { m, s -> m to s }
                .map { (m, s) ->
                    AutoLookupKey(
                        panelVisible   = m.isDictionaryPanelVisible,
                        isLoading      = m.isLoading,
                        inputText      = m.inputText.trim(),
                        translatedText = m.translatedText.trim(),
                        targetLang     = m.targetLanguage,
                        resolvedSourceLang = when {
                            m.sourceLanguage != LanguageCode.AUTO -> m.sourceLanguage
                            m.detectedSourceLanguage != null      -> m.detectedSourceLanguage!!
                            else                                  -> LanguageCode("en")
                        },
                        autoSource     = s.workingConfiguration.dictionaryAutoSource,
                        mainVisible    = isVisible,
                        isQuickDictionaryVisible = m.isQuickDictionaryVisible,
                        isQuickDictionaryPinned  = m.isQuickDictionaryPinned,
                        isDictionaryAutoPopupEnabled = s.workingConfiguration.isDictionaryAutoPopupEnabled,
                    )
                }
                .scan(Pair<AutoLookupKey?, AutoLookupKey?>(null, null)) { (_, prev), curr -> prev to curr }
                .filter { (prev, curr) ->
                    when {
                        curr == null -> false
                        // Always pass isLoading=true so the collect block can dismiss stale popups.
                        curr.isLoading -> true
                        // Need a previous snapshot to detect transitions.
                        prev == null -> false
                        // Auto-lookup is disabled — nothing to do.
                        curr.autoSource == com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource.OFF -> false
                        else -> {
                            // Primary trigger: translation just finished.
                            val justFinishedLoading = prev.isLoading && !curr.isLoading
                            // Secondary trigger: a setting changed while already idle and a
                            // translation result is already on screen.
                            val settingChangedIdle = curr.translatedText.isNotBlank() && (
                                prev.autoSource != curr.autoSource ||
                                prev.isDictionaryAutoPopupEnabled != curr.isDictionaryAutoPopupEnabled ||
                                (!prev.panelVisible && curr.panelVisible)
                            )
                            justFinishedLoading || settingChangedIdle
                        }
                    }
                }
                .mapNotNull { it.second }
                .collect { key ->
                    // Translation started — dismiss any unpinned auto-triggered popup.
                    if (key.isLoading) {
                        if (key.isQuickDictionaryVisible && !key.isQuickDictionaryPinned) {
                            mainStore.dispatch(MainIntent.HideQuickDictionary)
                        }
                        return@collect
                    }
                    val autoSource = key.autoSource
                    if (autoSource == com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource.OFF) return@collect

                    val (word, lang) = when (autoSource) {
                        com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource.TRANSLATED ->
                            key.translatedText to key.targetLang
                        com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource.SOURCE ->
                            key.inputText to key.resolvedSourceLang
                        else -> return@collect
                    }

                    // Word is not a single valid word — dismiss any unpinned auto popup.
                    if (word.isBlank() || word.contains(Regex("\\s")) || word.length < 2) {
                        if (key.isQuickDictionaryVisible && !key.isQuickDictionaryPinned) {
                            mainStore.dispatch(MainIntent.HideQuickDictionary)
                        }
                        return@collect
                    }
                    val current = mainStore.state.value.dictionaryWord
                    if (word.equals(current, ignoreCase = true)) return@collect

                    if (key.panelVisible) {
                        // Panel is open — update it directly.
                        withContext(Dispatchers.Swing) {
                            mainContentView.setDictionarySearchWord(word)
                        }
                        mainStore.dispatch(MainIntent.LookupWord(word, lang))
                    } else if (key.mainVisible && key.isDictionaryAutoPopupEnabled) {
                        // Panel closed but main window visible — show floating popup.
                        // Position near the owner window, not the mouse cursor.
                        quickDictionaryPositionNearMouse = false
                        mainStore.dispatch(MainIntent.ShowQuickDictionary(word, lang))
                    }
                }
        }

        // Persist dictionary panel visibility whenever it changes.
        appScope.launch(handler) {
            mainStore.state
                .map { it.isDictionaryPanelVisible }
                .distinctUntilChanged()
                .drop(1)
                .collect { visible ->
                    settingsStore.dispatch(
                        SettingsIntent.ToggleSetting { it.copy(showDictionaryPanel = visible) }
                    )
                    settingsStore.dispatch(SettingsIntent.SaveChanges)
                }
        }

        // Persist quick dictionary pin state when it changes.
        appScope.launch(handler) {
            mainStore.state
                .map { it.isQuickDictionaryPinned }
                .distinctUntilChanged()
                .drop(1)
                .collect { pinned ->
                    settingsStore.dispatch(
                        SettingsIntent.ToggleSetting { it.copy(isQuickDictionaryPinned = pinned) }
                    )
                    settingsStore.dispatch(SettingsIntent.SaveChanges)
                }
        }

        // Re-render history dialog whenever history list changes (if dialog is open).
        appScope.launch(handler) {
            mainStore.state
                .map { it.history }
                .distinctUntilChanged()
                .collect {
                    withContext(Dispatchers.Swing) {
                        if (historyDialog.isVisible) {
                            historyDialog.render(buildHistoryDialogState())
                        }
                    }
                }
        }

        // Re-render dictionary dialog when lookup state changes (if dialog is open).
        appScope.launch(handler) {
            mainStore.state
                .map { Triple(it.dictionaryEntries, it.isDictionaryLoading, it.dictionaryFailed) }
                .distinctUntilChanged()
                .collect {
                    withContext(Dispatchers.Swing) {
                        if (dictionaryDialog.isVisible) {
                            dictionaryDialog.render(buildDictionaryDialogState())
                        }
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
        // WHEN_ANCESTOR_OF_FOCUSED_COMPONENT fires whenever any descendant has focus,
        // which is always the case (text pane, buttons, etc.).
        // WHEN_FOCUSED would only fire if rootPane itself held focus — which never happens.
        val inputMap = rootPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        inputMap.clear()
        rootPane.actionMap.clear()

        globalKeyListener.getLocalBindings().forEach { binding ->
            val keyStroke = binding.toKeyStroke() ?: return@forEach
            val actionKey = "localHotkey_${binding.action.name}"
            inputMap.put(keyStroke, actionKey)
            rootPane.actionMap.put(actionKey, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    // FOCUS_* are LOCAL-only and require layout-aware handling (Compact layout must
                    // switch tabs before focusing). Route them directly to MainContentView rather
                    // than through globalKeyListener.dispatchAction(), which would do nothing.
                    when (binding.action) {
                        HotkeyAction.FOCUS_INPUT        -> mainContentView.switchToAndFocusInput()
                        HotkeyAction.FOCUS_OUTPUT       -> mainContentView.switchToAndFocusOutput()
                        HotkeyAction.FOCUS_EXTRA_OUTPUT -> mainContentView.switchToAndFocusExtraOutput()
                        else -> globalKeyListener.dispatchAction(binding.action)
                    }
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
            onShowDictionary = { showDictionaryDialog() },
            onShowHistory = { showHistoryDialog() },
            onShowSettings = {
                val dialog = createSettingsDialog()
                dialog.applyComponentOrientation(
                    if (localizer.isRtl) ComponentOrientation.RIGHT_TO_LEFT
                    else ComponentOrientation.LEFT_TO_RIGHT
                )
                dialog.isVisible = true
            },
            onShowHowToUse = { openUrl("https://github.com/ahatem/QTranslate/wiki") },
            onShowAboutQTranslate = { onShowAboutDialog() },
            onContactUs = { openUrl("https://github.com/ahatem/QTranslate/issues/new") },
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
            isDictionaryPanelOpen = mainStore.state.value.isDictionaryPanelVisible,
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
            onShowDictionary = { showDictionaryDialog() },
            onRecognizeText = { openSnippingTool() },
            onShowHistory = { showHistoryDialog() },
            onShowSettings = {
                runOnUi {
                    val dialog = createSettingsDialog()
                    dialog.applyComponentOrientation(
                        if (localizer.isRtl) ComponentOrientation.RIGHT_TO_LEFT
                        else ComponentOrientation.LEFT_TO_RIGHT
                    )
                    dialog.isVisible = true
                }
            },
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
                    mainWindowSize = Size(s.width, s.height),
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
            CloseButtonBehavior.EXIT -> dispose()
            CloseButtonBehavior.ASK -> showCloseDialog()
        }
    }

    private fun showCloseDialog() {
        val dialog = JDialog(this, localizer.getString("close_dialog.title"), true)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        val iconLabel = JLabel(UIManager.getIcon("OptionPane.questionIcon"))
        val messageLabel = JLabel(localizer.getString("close_dialog.message")).apply {
            font = font.deriveFont(Font.BOLD, font.size + 1f)
        }

        val rememberCheck = JCheckBox(localizer.getString("close_dialog.remember_choice")).apply {
            isOpaque = false
            font = font.deriveFont(font.size - 1f)
            foreground = UIManager.getColor("Label.disabledForeground")
        }

        var result: CloseButtonBehavior? = null

        val minimizeBtn = JButton(localizer.getString("close_dialog.minimize_to_tray")).apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addActionListener {
                result = CloseButtonBehavior.MINIMIZE_TO_TRAY
                dialog.dispose()
            }
        }
        val exitBtn = JButton(localizer.getString("close_dialog.exit")).apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addActionListener {
                result = CloseButtonBehavior.EXIT
                dialog.dispose()
            }
        }
        val cancelBtn = JButton(localizer.getString("common.cancel")).apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addActionListener { dialog.dispose() }
        }

        val topPanel = JPanel(BorderLayout(16, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(20, 20, 12, 20)
            add(iconLabel, BorderLayout.LINE_START)
            add(messageLabel, BorderLayout.CENTER)
        }

        val checkPanel = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 20, 12, 20)
            add(rememberCheck)
        }

        val btnPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 20, 20, 20)
            add(minimizeBtn)
            add(Box.createVerticalStrut(8))
            add(exitBtn)
            add(Box.createVerticalStrut(8))
            add(cancelBtn)
        }

        dialog.contentPane.apply {
            layout = BorderLayout()
            add(topPanel, BorderLayout.NORTH)
            add(checkPanel, BorderLayout.CENTER)
            add(btnPanel, BorderLayout.SOUTH)
        }

        dialog.rootPane.defaultButton = minimizeBtn

        dialog.rootPane.registerKeyboardAction(
            { dialog.dispose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        dialog.pack()
        dialog.minimumSize = Dimension(320, dialog.height)
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true

        when (result) {
            CloseButtonBehavior.MINIMIZE_TO_TRAY -> {
                if (rememberCheck.isSelected) saveClosePreference(CloseButtonBehavior.MINIMIZE_TO_TRAY)
                isVisible = false
            }

            CloseButtonBehavior.EXIT -> {
                if (rememberCheck.isSelected) saveClosePreference(CloseButtonBehavior.EXIT)
                dispose()
            }

            CloseButtonBehavior.ASK -> {}
            null -> {}
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
                idleTimeoutSeconds = config.popupIdleTimeoutSeconds,
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

    private fun openUrl(url: String) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return
        runCatching { Desktop.getDesktop().browse(URI(url)) }
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
            closeButtonText = localizer.getString("common.close"),
            supportUrl = "https://buymeacoffee.com/ahmedhatem",
            supportButtonText = localizer.getString("about_dialog.support_button")
        )

        runOnUi { aboutDialog.showDialog(state) }
    }

    /** Shows a one-time donation nudge in the notification popover. */
    private fun showDonationNudge() {
        val message = localizer.getString("about_dialog.donation_nudge")
        statusBarController.addToPopover(
            com.github.ahatem.qtranslate.core.shared.notification.AppNotification(
                type = com.github.ahatem.qtranslate.api.plugin.NotificationType.INFO,
                code = com.github.ahatem.qtranslate.core.shared.notification.NotificationCode.Custom(
                    title = "",
                    body = message
                )
            )
        )
    }

    private fun showUpdateDialog(code: NotificationCode.UpdateAvailable) {
        val state = UpdateDialogState(
            title = localizer.getString("update_dialog.title"),
            header = localizer.getString("update_dialog.header"),
            details = localizer.getString("update_dialog.details_format", code.newVersion, code.currentVersion),
            releaseNotes = code.releaseNotes,
            skipButton = localizer.getString("update_dialog.skip_button"),
            remindLaterButton = localizer.getString("update_dialog.remind_later_button"),
            downloadButton = localizer.getString("update_dialog.download_button"),
            viewOnGitHubButton = localizer.getString("update_dialog.view_on_github_button"),
            downloadUrl = code.downloadUrl,
            releaseUrl = code.releaseUrl,
            onSkip = {},
            onRemindLater = {}
        )
        runOnUi { updateDialog.show(state) }
    }

    private fun showDictionaryDialog() {
        val initialWord = mainStore.state.value.inputText.trim()
            .takeIf { it.isNotBlank() && !it.contains(' ') } ?: ""

        // Main window visible → toggle the inline panel.
        if (isVisible) {
            val wasVisible = mainStore.state.value.isDictionaryPanelVisible
            mainStore.dispatch(MainIntent.ToggleDictionaryPanel)
            if (!wasVisible) {
                mainContentView.setDictionarySearchWord(initialWord)
                if (initialWord.isNotBlank()) {
                    mainStore.dispatch(MainIntent.LookupWord(initialWord))
                }
            }
        } else {
            dictionaryDialog.setSearchWord(initialWord)
            dictionaryDialog.render(buildDictionaryDialogState())
            if (initialWord.isNotBlank()) {
                mainStore.dispatch(MainIntent.LookupWord(initialWord))
            }
            dictionaryDialog.isVisible = true
            dictionaryDialog.toFront()
        }
    }

    private fun buildDictionaryDialogState(): DictionaryDialogState {
        val s = mainStore.state.value
        val config = settingsStore.state.value.workingConfiguration
        val availableDicts = s.getAvailableServicesFor(com.github.ahatem.qtranslate.core.shared.arch.ServiceType.DICTIONARY)
        val selectedDictId = config.getActivePreset()
            ?.selectedServices?.get(com.github.ahatem.qtranslate.core.shared.arch.ServiceType.DICTIONARY)

        val resolvedLang = when {
            s.sourceLanguage != LanguageCode.AUTO -> s.sourceLanguage
            s.detectedSourceLanguage != null      -> s.detectedSourceLanguage!!
            else                                  -> LanguageCode("en")
        }

        return DictionaryDialogState(
            title                 = localizer.getString("dictionary_dialog.title"),
            lookupButtonLabel     = localizer.getString("dictionary_dialog.lookup_button"),
            closeLabel            = localizer.getString("common.close"),
            hintMessage           = localizer.getString("dictionary_dialog.hint_message"),
            notFoundMessage       = localizer.getString("dictionary_dialog.not_found_message", s.dictionaryWord),
            loadingMessage        = localizer.getString("dictionary_dialog.loading_message"),
            errorMessage          = localizer.getString("dictionary_dialog.error_message"),
            synonymsLabel         = localizer.getString("dictionary_dialog.synonyms_label"),
            isLoading             = s.isDictionaryLoading,
            entries               = s.dictionaryEntries,
            lookedUpWord          = s.dictionaryWord,
            hasFailed             = s.dictionaryFailed,
            availableDictionaries = availableDicts,
            selectedDictionaryId  = selectedDictId,
            onLookup = { word -> mainStore.dispatch(MainIntent.LookupWord(word, resolvedLang)) },
            onDictionarySelected = { serviceId ->
                settingsStore.dispatch(
                    SettingsIntent.UpdateServiceInActivePreset(
                        com.github.ahatem.qtranslate.core.shared.arch.ServiceType.DICTIONARY, serviceId
                    )
                )
                val currentWord = mainStore.state.value.dictionaryWord
                if (currentWord.isNotBlank()) mainStore.dispatch(MainIntent.LookupWord(currentWord, resolvedLang))
            }
        )
    }

    private fun buildQuickDictionaryDialogState(
        mainState: MainState,
        config: Configuration
    ): QuickDictionaryDialogState {
        val availableDicts = mainState.getAvailableServicesFor(
            com.github.ahatem.qtranslate.core.shared.arch.ServiceType.DICTIONARY
        )
        val selectedDictId = config.getActivePreset()
            ?.selectedServices?.get(com.github.ahatem.qtranslate.core.shared.arch.ServiceType.DICTIONARY)

        val resolvedLang = when {
            mainState.sourceLanguage != LanguageCode.AUTO -> mainState.sourceLanguage
            mainState.detectedSourceLanguage != null      -> mainState.detectedSourceLanguage!!
            else                                          -> LanguageCode("en")
        }

        return QuickDictionaryDialogState(
            isVisible            = mainState.isQuickDictionaryVisible,
            isLoading            = mainState.isDictionaryLoading,
            entries              = mainState.dictionaryEntries,
            lookedUpWord         = mainState.dictionaryWord,
            hasFailed            = mainState.dictionaryFailed,
            isPinned             = mainState.isQuickDictionaryPinned,
            availableDictionaries = availableDicts,
            selectedDictionaryId  = selectedDictId,
            autoSource               = config.dictionaryAutoSource,
            autoSourceOffLabel       = localizer.getString("dictionary_dialog.auto_source_off"),
            autoSourceTranslatedLabel = localizer.getString("dictionary_dialog.auto_source_translated"),
            autoSourceSourceLabel    = localizer.getString("dictionary_dialog.auto_source_source"),
            config = QuickDictionaryConfig(
                autoPositionEnabled  = config.isQuickDictionaryAutoPositionEnabled,
                lastKnownSize        = config.quickDictionaryLastKnownSize,
                lastKnownPosition    = config.quickDictionaryLastKnownPosition,
                positionNearMouse    = quickDictionaryPositionNearMouse,
                idleTimeoutSeconds   = config.quickDictionaryIdleTimeoutSeconds,
                transparencyPercentage = config.quickDictionaryTransparencyPercentage
            ),
            strings = QuickDictionaryStrings(
                title            = localizer.getString("dictionary_dialog.title"),
                hintMessage      = localizer.getString("dictionary_dialog.hint_message"),
                loadingMessage   = localizer.getString("dictionary_dialog.loading_message"),
                notFoundMessage  = localizer.getString("dictionary_dialog.not_found_message", mainState.dictionaryWord),
                errorMessage     = localizer.getString("dictionary_dialog.error_message"),
                lookupButtonLabel = localizer.getString("dictionary_dialog.lookup_button"),
                synonymsLabel    = localizer.getString("dictionary_dialog.synonyms_label"),
                pinTooltip       = localizer.getString("common.pin"),
                unpinTooltip     = localizer.getString("common.unpin"),
                closeTooltip     = localizer.getString("common.close")
            ),
            onLookup = { word -> mainStore.dispatch(MainIntent.LookupWord(word, resolvedLang)) },
            onDictionarySelected = { serviceId ->
                settingsStore.dispatch(
                    SettingsIntent.UpdateServiceInActivePreset(
                        com.github.ahatem.qtranslate.core.shared.arch.ServiceType.DICTIONARY, serviceId
                    )
                )
                val currentWord = mainStore.state.value.dictionaryWord
                if (currentWord.isNotBlank()) mainStore.dispatch(MainIntent.LookupWord(currentWord, resolvedLang))
            },
            onAutoSourceChanged = { newSource ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(dictionaryAutoSource = newSource) }
                )
                settingsStore.dispatch(SettingsIntent.SaveChanges)
            },
            onPinToggled = { mainStore.dispatch(MainIntent.ToggleQuickDictionaryPin) },
            onClose = { mainStore.dispatch(MainIntent.HideQuickDictionary) },
            onSavePosition = { pos ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(quickDictionaryLastKnownPosition = pos) }
                )
                settingsStore.dispatch(SettingsIntent.SaveChanges)
            },
            onSaveSize = { size ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(quickDictionaryLastKnownSize = size) }
                )
                settingsStore.dispatch(SettingsIntent.SaveChanges)
            }
        )
    }

    private fun showHistoryDialog() {
        historyDialog.render(buildHistoryDialogState())
        historyDialog.isVisible = true
        historyDialog.toFront()
    }

    private fun buildHistoryDialogState(): HistoryDialogState {
        val fmt = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        val services = mainStore.state.value.availableServices
        val entries = mainStore.state.value.history.reversed().map { snap ->
            val serviceName = services.find { it.id == snap.translatorId }?.name ?: snap.translatorId

            val sourceLanguage = LanguageCode(snap.sourceLanguage).getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect"))
            val targetLanguage = LanguageCode(snap.targetLanguage).getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect"))

            HistoryEntryState(
                date = fmt.format(Date(snap.timestamp)),
                sourceText = snap.inputText.take(80).let { if (snap.inputText.length > 80) "$it…" else it },
                translatedText = snap.translatedText.take(80).let { if (snap.translatedText.length > 80) "$it…" else it },
                languages = "$sourceLanguage → $targetLanguage",
                service = serviceName,
                snapshot = snap
            )
        }
        return HistoryDialogState(
            title = localizer.getString("history_dialog.title"),
            columnDate = localizer.getString("history_dialog.column_date"),
            columnSource = localizer.getString("history_dialog.column_source"),
            columnTranslation = localizer.getString("history_dialog.column_translation"),
            columnLanguages = localizer.getString("history_dialog.column_languages"),
            columnService = localizer.getString("history_dialog.column_service"),
            emptyMessage = localizer.getString("history_dialog.empty_message"),
            clearAllLabel = localizer.getString("common.clear_all"),
            closeLabel = localizer.getString("common.close"),
            restoreTooltip = localizer.getString("history_dialog.restore_tooltip"),
            entries = entries,
            onEntrySelected = { snapshot ->
                mainStore.dispatch(MainIntent.RestoreHistoryEntry(snapshot))
                historyDialog.isVisible = false
            },
            onClearAll = { mainStore.dispatch(MainIntent.ClearHistory) }
        )
    }

    inner class StatusBarController(
        private val statusBar: StatusBar,
        private val scope: CoroutineScope,
        private val defaultMessage: String,
    ) {
        private var clearMessageJob: Job? = null
        private var currentMessage: String = defaultMessage
        private var currentType: NotificationType = NotificationType.INFO
        private var isLoading: Boolean = false
        private var unreadCount: Int = 0

        // -----------------------------------------------------------------------
        // Error-detail popup
        // -----------------------------------------------------------------------

        private val errorDetailPopup = ErrorDetailPopup(iconManager)

        /** Message currently shown in the popup, null when popup is hidden. */
        private var shownDetailMessage: String? = null

        init {
            errorDetailPopup.errorLabel   = localizer.getString("main_window_status_bar.error_detail_error")
            errorDetailPopup.warningLabel = localizer.getString("main_window_status_bar.error_detail_warning")
            errorDetailPopup.copyLabel    = localizer.getString("main_window_status_bar.error_detail_copy")
            errorDetailPopup.copiedLabel  = localizer.getString("main_window_status_bar.error_detail_copied")

            statusBar.onErrorClicked = { message ->
                shownDetailMessage = message
                errorDetailPopup.show(message, currentType, statusBar)
            }

            render()
        }

        /** Called for transient action feedback ("Translating…", "Playing audio…"). */
        fun handleEvent(event: MainEvent.UpdateStatusBar) {
            clearMessageJob?.cancel()
            currentMessage = resolveStatusMessage(event.code)
            currentType = event.type
            render()
            if (event.isTemporary) {
                val snapshot = currentMessage
                clearMessageJob = scope.launch {
                    delay(AppConstants.STATUS_MESSAGE_DURATION_MS)
                    if (currentMessage == snapshot) {
                        currentMessage = defaultMessage
                        currentType = NotificationType.INFO
                        render()
                    }
                }
            }
        }

        /** Called for background/system events — adds to popover, updates bell badge. */
        fun addToPopover(notification: com.github.ahatem.qtranslate.core.shared.notification.AppNotification) {
            val message = resolveNotificationMessage(notification.code)
            notificationPopover.addNotification(NotificationPopover.NotificationEntry(message, notification.type))
            unreadCount++
            render()
        }

        /** Reflects the main loading state (translation / OCR in progress). */
        fun setLoading(loading: Boolean) {
            if (isLoading == loading) return
            isLoading = loading
            render()
        }

        /** Called when the user clears all notifications. */
        fun onPopoverCleared() {
            unreadCount = 0
            render()
        }

        private fun bellTooltip(): String {
            val base = localizer.getString("main_window_status_bar.notifications_tooltip")
            return if (unreadCount > 0) "$base ($unreadCount)" else base
        }

        private fun render() {
            // Auto-dismiss the detail popup when the displayed message changes or
            // when the type is no longer an error/warning.
            val isErrorOrWarning = currentType == NotificationType.ERROR || currentType == NotificationType.WARNING
            if (errorDetailPopup.isVisible && (!isErrorOrWarning || shownDetailMessage != currentMessage)) {
                errorDetailPopup.dismiss()
                shownDetailMessage = null
            }

            statusBar.render(
                StatusBarState(
                    message = currentMessage,
                    type = currentType,
                    isLoading = isLoading,
                    notificationTooltip = bellTooltip(),
                    isNotificationButtonEnabled = true
                )
            )
        }

        private fun resolveStatusMessage(code: StatusCode): String = when (code) {
            StatusCode.Translating                  -> localizer.getString("status_bar.translating")
            StatusCode.TranslationComplete          -> localizer.getString("status_bar.translation_complete")
            StatusCode.TranslationCancelled         -> localizer.getString("status_bar.translation_cancelled")
            StatusCode.TranslationTimeout           -> localizer.getString("status_bar.translation_timeout")
            is StatusCode.TranslationFailed         -> localizer.getString("status_bar.translation_failed", code.summary)
            StatusCode.NoTranslatorActive           -> localizer.getString("status_bar.no_translator_active")
            StatusCode.PerformingBackwardTranslation -> localizer.getString("status_bar.performing_backward_translation")
            is StatusCode.UnexpectedError           -> localizer.getString("status_bar.unexpected_error", code.summary)
            StatusCode.NoTextToSpeak                -> localizer.getString("status_bar.no_text_to_speak")
            StatusCode.CannotDetermineLanguage      -> localizer.getString("status_bar.cannot_determine_language")
            StatusCode.NoTtsServiceActive           -> localizer.getString("status_bar.no_tts_active")
            is StatusCode.TtsLanguageNotSupported   -> localizer.getString("status_bar.tts_language_not_supported", code.serviceName)
            StatusCode.ConvertingToSpeech           -> localizer.getString("status_bar.converting_to_speech")
            StatusCode.TtsTimeout                   -> localizer.getString("status_bar.tts_timeout")
            StatusCode.PlayingAudio                 -> localizer.getString("status_bar.playing_audio")
            StatusCode.AudioPlaybackComplete        -> localizer.getString("status_bar.audio_playback_complete")
            StatusCode.TtsStopped                   -> localizer.getString("status_bar.tts_stopped")
            StatusCode.DownloadingAudio             -> localizer.getString("status_bar.downloading_audio")
            StatusCode.AudioDownloadFailed          -> localizer.getString("status_bar.audio_download_failed")
            is StatusCode.TtsFailed                 -> localizer.getString("status_bar.tts_failed", code.summary)
            StatusCode.NoOcrServiceActive           -> localizer.getString("status_bar.no_ocr_active")
            StatusCode.RecognizingText              -> localizer.getString("status_bar.recognizing_text")
            StatusCode.OcrTimeout                   -> localizer.getString("status_bar.ocr_timeout")
            StatusCode.NoTextInImage                -> localizer.getString("status_bar.no_text_in_image")
            StatusCode.OcrComplete                  -> localizer.getString("status_bar.ocr_complete")
            StatusCode.OcrTextCopied               -> localizer.getString("status_bar.ocr_text_copied")
            is StatusCode.OcrFailed                 -> localizer.getString("status_bar.ocr_failed", code.summary)
            StatusCode.NoSummarizerActive           -> localizer.getString("status_bar.no_summarizer_active")
            StatusCode.Summarizing                  -> localizer.getString("status_bar.summarizing")
            StatusCode.SummarizeTimeout             -> localizer.getString("status_bar.summarize_timeout")
            StatusCode.SummaryReady                 -> localizer.getString("status_bar.summary_ready")
            is StatusCode.SummarizeFailed           -> localizer.getString("status_bar.summarize_failed", code.summary)
            StatusCode.NoRewriterActive             -> localizer.getString("status_bar.no_rewriter_active")
            StatusCode.Rewriting                    -> localizer.getString("status_bar.rewriting")
            StatusCode.RewriteTimeout               -> localizer.getString("status_bar.rewrite_timeout")
            StatusCode.RewriteReady                 -> localizer.getString("status_bar.rewrite_ready")
            is StatusCode.RewriteFailed             -> localizer.getString("status_bar.rewrite_failed", code.summary)
            StatusCode.SpellCheckTimeout            -> localizer.getString("status_bar.spell_check_timeout")
            is StatusCode.SpellCheckFailed          -> localizer.getString("status_bar.spell_check_failed", code.summary)
            StatusCode.NoWordToLookup               -> localizer.getString("status_bar.no_word_to_lookup")
            StatusCode.NoDictionaryServiceActive    -> localizer.getString("status_bar.no_dictionary_active")
            StatusCode.LookingUpWord                -> localizer.getString("status_bar.looking_up_word")
            StatusCode.DictionaryReady              -> localizer.getString("status_bar.dictionary_ready")
            is StatusCode.DictionaryNotFound        -> localizer.getString("status_bar.dictionary_not_found", code.word)
            StatusCode.DictionaryTimeout            -> localizer.getString("status_bar.dictionary_timeout")
            is StatusCode.DictionaryFailed          -> localizer.getString("status_bar.dictionary_failed", code.summary)
            is StatusCode.AlreadyUpToDate           -> localizer.getString("status_bar.already_up_to_date", code.version)
            StatusCode.UpdateCheckNetworkError      -> localizer.getString("status_bar.update_check_network_error")
            StatusCode.UpdateCheckParseError        -> localizer.getString("status_bar.update_check_parse_error")
            StatusCode.UpdateCheckUnknownError      -> localizer.getString("status_bar.update_check_unknown_error")
        }

        private fun resolveNotificationMessage(code: NotificationCode): String = when (code) {
            is NotificationCode.LanguageNotSupported ->
                localizer.getString("notifications.language_not_supported_format", code.lang, code.serviceId)
            is NotificationCode.TtsNotSupported ->
                localizer.getString("notifications.tts_not_supported_format", code.serviceId)
            is NotificationCode.UnknownError ->
                localizer.getString("notifications.unknown_error")
            is NotificationCode.Custom -> if (code.title.isNotBlank()) "${code.title}: ${code.body}" else code.body
            is NotificationCode.UpdateAvailable -> ""
        }

    }
}

/**
 * Custom focus-traversal policy for the main application window.
 *
 * When Tab/Shift+Tab is pressed inside any of the three text panes (input, output, extra),
 * focus moves directly to the next/previous pane in the cycle — skipping toolbar buttons,
 * scrollbars, and other intermediate components.  For Compact (tabbed) layout the policy
 * also selects the target tab so the pane is visible before Swing calls requestFocusInWindow().
 *
 * When Tab is pressed from any component that is NOT one of the managed text panes the
 * standard [LayoutFocusTraversalPolicy] takes over, preserving normal keyboard navigation
 * for dialogs, settings panels, and anything else displayed in the same window.
 */
private class TextPaneCycleFocusPolicy(
    private val contentView: MainContentView,
    private val fallback: FocusTraversalPolicy = LayoutFocusTraversalPolicy()
) : FocusTraversalPolicy() {

    /** All visible text panes in traversal order. Re-evaluated on every Tab press. */
    private fun panes(): List<JComponent> = contentView.orderedTextPanes()

    override fun getComponentAfter(aContainer: Container, aComponent: Component): Component {
        val all = panes()
        val idx = all.indexOfFirst { it === aComponent }
        if (idx < 0) return fallback.getComponentAfter(aContainer, aComponent)
        val nextIdx = (idx + 1) % all.size
        contentView.ensureCompactTabVisible(nextIdx)
        return all[nextIdx]
    }

    override fun getComponentBefore(aContainer: Container, aComponent: Component): Component {
        val all = panes()
        val idx = all.indexOfFirst { it === aComponent }
        if (idx < 0) return fallback.getComponentBefore(aContainer, aComponent)
        val prevIdx = (idx - 1 + all.size) % all.size
        contentView.ensureCompactTabVisible(prevIdx)
        return all[prevIdx]
    }

    override fun getFirstComponent(aContainer: Container): Component =
        panes().firstOrNull() ?: fallback.getFirstComponent(aContainer)

    override fun getLastComponent(aContainer: Container): Component =
        panes().lastOrNull() ?: fallback.getLastComponent(aContainer)

    override fun getDefaultComponent(aContainer: Container): Component =
        panes().firstOrNull() ?: fallback.getDefaultComponent(aContainer)
}

/** Snapshot used to deduplicate auto-lookup triggers. */
private data class AutoLookupKey(
    val panelVisible: Boolean,
    val isLoading: Boolean,
    val inputText: String,
    val translatedText: String,
    val targetLang: LanguageCode,
    val resolvedSourceLang: LanguageCode,
    val autoSource: com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource,
    val mainVisible: Boolean,
    val isQuickDictionaryVisible: Boolean,
    val isQuickDictionaryPinned: Boolean,
    val isDictionaryAutoPopupEnabled: Boolean,
)