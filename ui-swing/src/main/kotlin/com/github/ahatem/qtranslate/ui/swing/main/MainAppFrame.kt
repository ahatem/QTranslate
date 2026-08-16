package com.github.ahatem.qtranslate.ui.swing.main

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.components.FlatButton
import com.formdev.flatlaf.util.FontUtils
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.mvi.MainEvent
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId
import com.github.ahatem.qtranslate.core.settings.data.*
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
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
import com.github.ahatem.qtranslate.ui.swing.imagesearch.ImageSearchConfig
import com.github.ahatem.qtranslate.ui.swing.imagesearch.ImageSearchDialog
import com.github.ahatem.qtranslate.ui.swing.imagesearch.ImageSearchDialogState
import com.github.ahatem.qtranslate.ui.swing.imagesearch.ImageSearchStrings
import com.github.ahatem.qtranslate.ui.swing.document.DocumentTranslationDialog
import com.github.ahatem.qtranslate.ui.swing.document.DocumentTranslationStrings
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
import com.github.ahatem.qtranslate.ui.swing.settings.panels.DynamicPluginSettingsDialog
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.*
import com.github.ahatem.qtranslate.ui.swing.snippingtool.SnippingToolDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import java.awt.*
import com.github.ahatem.qtranslate.core.document.DocumentFormat
import com.github.ahatem.qtranslate.ui.swing.shared.util.copyToClipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
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
    private val dictionaryDialog by lazy { DictionaryDialog(this, iconManager) }
    private val loadingIndicator by lazy { LoadingIndicator(this) }

    private val documentTranslationDialog by lazy {
        DocumentTranslationDialog(
            owner = this,
            iconManager = iconManager,
            strings = DocumentTranslationStrings(
                title = localizer.getString("document_translation.title"),
                inputFile = localizer.getString("document_translation.input_file"),
                outputFile = localizer.getString("document_translation.output_file"),
                browse = localizer.getString("common.browse"),
                translate = localizer.getString("document_translation.translate"),
                open = localizer.getString("document_translation.open"),
                openFailed = localizer.getString("document_translation.open_failed"),
                cancel = localizer.getString("common.cancel"),
                close = localizer.getString("common.close"),
                ready = localizer.getString("document_translation.ready"),
                pdfMode = localizer.getString("document_translation.pdf_mode"),
                layoutAware = localizer.getString("document_translation.layout_aware"),
                layoutAwareDescription = localizer.getString("document_translation.layout_aware_description"),
                textOnly = localizer.getString("document_translation.text_only"),
                textOnlyDescription = localizer.getString("document_translation.text_only_description"),
                chooseInput = localizer.getString("document_translation.choose_input"),
                chooseOutput = localizer.getString("document_translation.choose_output"),
                preparing = localizer.getString("document_translation.preparing"),
                translating = localizer.getString("document_translation.translating"),
                completed = localizer.getString("document_translation.completed"),
                cancelled = localizer.getString("document_translation.cancelled")
            ),
            onStart = { input, output, pdfMode ->
                mainStore.dispatch(MainIntent.TranslateDocument(input, output, pdfMode))
            },
            onCancel = { mainStore.dispatch(MainIntent.CancelDocumentTranslation) }
        )
    }

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

    private val imageSearchDialog by lazy {
        ImageSearchDialog(owner = this, iconManager = iconManager)
    }

    private val dragOverlay by lazy {
        DragOverlay(this) { localizer.getString("main_window.drop_hint") }
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
            localizationManager = localizer,
            onDismiss = { mainStore.dispatch(MainIntent.HideQuickTranslate) },
            onTranslatorSelected = { serviceId ->
                settingsStore.dispatch(
                    SettingsIntent.UpdateServiceInActivePreset(ServiceRole.TRANSLATOR, serviceId)
                )
                mainStore.dispatch(MainIntent.Translate())
            },
            // Reads the source text, not the translation — the popup is most often used
            // to check how the original word is pronounced.
            onListen = { mainStore.dispatch(MainIntent.ListenToText(TextSource.Input)) },
            onStopListening = { mainStore.dispatch(MainIntent.StopTTS) },
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
            onPinToggled = { mainStore.dispatch(MainIntent.ToggleQuickTranslateDialogPin) },
            // Changing either language re-runs the translation, which is the only reason anyone
            // changes it here. The same intents the main window's own picker dispatches, so the
            // two stay in step and the choice is remembered the same way.
            onSourceLanguageSelected = { language ->
                mainStore.dispatch(MainIntent.SelectSourceLanguage(language))
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(preferredSourceLanguage = language.tag) }
                )
                mainStore.dispatch(MainIntent.Translate())
            },
            onTargetLanguageSelected = { language ->
                mainStore.dispatch(MainIntent.SelectTargetLanguage(language))
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(preferredTargetLanguage = language.tag) }
                )
                mainStore.dispatch(MainIntent.Translate())
            },
            onSwapLanguages = {
                mainStore.dispatch(MainIntent.SwapLanguages)
                mainStore.dispatch(MainIntent.Translate())
            }
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
        onOpenDocumentTranslation = { file ->
            if (file != null) documentTranslationDialog.openWith(file) else documentTranslationDialog.open()
        },
        onNotificationsClicked = { notificationPopover.show(mainContentView.statusBar) },
        onConfigureService = { serviceId -> openPluginConfiguration(serviceId) },
        onOpenServiceSettings = {
            val dialog = createSettingsDialog()
            dialog.applyComponentOrientation(
                if (localizer.isRtl) ComponentOrientation.RIGHT_TO_LEFT
                else ComponentOrientation.LEFT_TO_RIGHT
            )
            dialog.isVisible = true
        }
    )

    private val selectionTranslateButton = SelectionTranslateButton(
        this,
        iconManager,
        localizer.getString("main_window_language_bar.translate_button")
    ) { text ->
        mainStore.dispatch(MainIntent.ShowQuickTranslate(text))
    }

    private fun openPluginConfiguration(serviceId: String) {
        // The plugin is named in the service id itself, so there is nothing to search for.
        val owningPluginId = ServiceId.pluginIdOf(serviceId) ?: return
        val plugin = pluginManager.plugins.value.find { it.id == owningPluginId } ?: return
        appScope.launch {
            val model = pluginManager.getPluginSettingsModel(plugin.id)
            val instance = pluginManager.getPluginSettingsInstance(plugin.id)
            withContext(Dispatchers.Swing) {
                if (model == null) {
                    JOptionPane.showMessageDialog(this@MainAppFrame, "This service has no configurable settings.", plugin.manifest.name, JOptionPane.INFORMATION_MESSAGE)
                    return@withContext
                }
                // Only offered for plugins that say they need setting up. For anything else the
                // check has nothing to report, and a button that always says "fine" teaches the
                // user to ignore it.
                val canTest = plugin.services.any { it.metadata.requiresConfiguration }

                DynamicPluginSettingsDialog(
                    owner = this@MainAppFrame,
                    pluginName = plugin.manifest.name,
                    localizationManager = localizer,
                    settingsModel = model,
                    settingsInstance = instance,
                    onSave = { values -> appScope.launch { pluginManager.applySettingsFromMap(plugin.id, values) } },
                    onTestConnection = if (!canTest) null else { values ->
                        // Applied first so the test uses what is on screen, not what was saved
                        // last time — testing a key you have just typed is the whole point.
                        pluginManager.applySettingsFromMap(plugin.id, values)
                        pluginManager.validateServices(plugin.id)
                    }
                ).isVisible = true
            }
        }
    }

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
                // No longer a toggle. Pressing the hotkey again with the popup open refreshes it
                // in place and restarts its countdown — hiding it meant the popup vanished when
                // the user was asking for more of it, and threw away a pin they had set.
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
        onShowImages = { selectedText ->
            // Refreshes in place when already open, for the same reason as the dictionary.
            appScope.launch {
                mainStore.dispatch(MainIntent.ShowImageSearch(selectedText, resolvedLookupLanguage()))
            }
        },
        onTranslate = { mainStore.dispatch(MainIntent.Translate()) },
        onSelectionDetected = { text, location ->
            runOnUi {
                val enabled = settingsStore.state.value.originalConfiguration.isSelectionIconEnabled
                // Suppress the button while QTranslate itself is focused — selecting text
                // inside the app already has the toolbar and hotkeys available.
                if (enabled && !isActive) selectionTranslateButton.showAt(location, text)
            }
        },
        onPointerPressed = { location ->
            runOnUi {
                selectionTranslateButton.dismissIfOutside(location)
                dismissPopupsPressedOutside(location)
            }
        }
    )

    /**
     * Closes any floating popup the user has just clicked away from.
     *
     * Driven by the native hook rather than by an AWT listener. The click that dismisses a popup
     * almost always lands in another application — the document being read — and AWT never sees
     * those: it only delivers events destined for this program's own windows. An AWT-based
     * version of this appeared to work when clicking on QTranslate itself and did nothing at all
     * in the case that matters.
     *
     * Pinned popups are left alone, which is the point of pinning.
     */
    private fun dismissPopupsPressedOutside(screenPoint: java.awt.Point) {
        if (!settingsStore.state.value.workingConfiguration.closePopupsOnClickOutside) return
        val state = mainStore.state.value

        fun pressedOutside(dialog: java.awt.Window) = dialog.isVisible && !dialog.bounds.contains(screenPoint)

        if (state.isQuickTranslateDialogVisible && !state.isQuickTranslateDialogPinned &&
            pressedOutside(quickTranslateDialog)
        ) {
            mainStore.dispatch(MainIntent.HideQuickTranslate)
        }
        if (state.isQuickDictionaryVisible && !state.isQuickDictionaryPinned &&
            pressedOutside(quickDictionaryDialog)
        ) {
            mainStore.dispatch(MainIntent.HideQuickDictionary)
        }
        if (state.isImageSearchVisible && !state.isImageSearchPinned &&
            pressedOutside(imageSearchDialog)
        ) {
            mainStore.dispatch(MainIntent.HideImageSearch)
        }
    }

    private val statusBarController = StatusBarController(
        statusBar = mainContentView.statusBar,
        scope = appScope,
        defaultMessage = localizer.getString("main_window_status_bar.ready_message")
    )

    init {
        globalKeyListener.updateBindings(
            settingsStore.state.value.originalConfiguration.hotkeys
        )
        globalKeyListener.setSelectionIconEnabled(
            settingsStore.state.value.originalConfiguration.isSelectionIconEnabled
        )

        SwingUtilities.invokeLater {
            contentPane.add(mainContentView, BorderLayout.CENTER)
            defaultCloseOperation = DO_NOTHING_ON_CLOSE

            val config = settingsStore.state.value.workingConfiguration
            val scale = config.uiScale / 100f

            // The constants are authored against a 100% display, while everything drawn inside the
            // window — fonts, icons, insets — is scaled by FlatLaf to the display's density. Without
            // UIScale the window opens at its 100% size on a 150% or 200% screen and clips its own
            // controls. A saved size is already in device pixels, so it is used as-is; scaling it
            // again would grow the window on every launch.
            minimumSize = Dimension(
                UIScale.scale((AppConstants.MIN_WINDOW_WIDTH * scale).toInt()),
                UIScale.scale((AppConstants.MIN_WINDOW_HEIGHT * scale).toInt())
            )
            val savedSize = config.mainWindowSize
            preferredSize = if (savedSize != null) {
                Dimension(savedSize.width, savedSize.height)
            } else {
                Dimension(
                    UIScale.scale((AppConstants.DEFAULT_WINDOW_WIDTH * scale).toInt()),
                    UIScale.scale((AppConstants.DEFAULT_WINDOW_HEIGHT * scale).toInt())
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
            setupDropTarget()

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

                            if (mainState.isImageSearchVisible || imageSearchDialog.isVisible) {
                                imageSearchDialog.render(
                                    buildImageSearchDialogState(mainState, settingsState.workingConfiguration)
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
                .collect { (isLoading, popupRequested, isReplacing) ->
                    withContext(Dispatchers.Swing) {
                        // Two cases want the marker, and neither depends on whether the main
                        // window happens to be open: a popup translation that has been asked for
                        // but has nothing to show yet, and an inline replace, which has no window
                        // of its own at all.
                        //
                        // It used to also require the main window to be hidden, on the reasoning
                        // that a visible main window shows its own progress. But Ctrl+Q opens the
                        // popup either way, and in that case the main window is not where the user
                        // is looking.
                        val popupPending = popupRequested && !quickTranslateDialog.isVisible
                        val shouldShow = isLoading && (isReplacing || popupPending)
                        loadingIndicator.render(LoadingIndicatorState(isVisible = shouldShow))
                    }
                }
        }

        appScope.launch(handler) {
            combine(
                mainStore.state.map { it.sourceLanguage to it.targetLanguage },
                settingsStore.state.map { settings ->
                    settings.workingConfiguration.getActivePreset()
                        ?.selectedServices
                        ?.get(ServiceRole.TRANSLATOR)
                }
            ) { languages, translatorId -> Triple(languages.first, languages.second, translatorId) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    withContext(Dispatchers.Swing) {
                        documentTranslationDialog.translationContextChanged()
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

        // Selection translate button — toggling the setting takes effect immediately,
        // and disabling it hides any button that is currently on screen.
        appScope.launch(handler) {
            settingsStore.state
                .map { it.originalConfiguration.isSelectionIconEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    globalKeyListener.setSelectionIconEnabled(enabled)
                    if (!enabled) withContext(Dispatchers.Swing) { selectionTranslateButton.dismiss() }
                }
        }

        appScope.launch(handler) {
            mainStore.state
                .map { it.documentTranslationProgress }
                .filterNotNull()
                .collect { progress ->
                    withContext(Dispatchers.Swing) {
                        documentTranslationDialog.updateProgress(progress)
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
                    is MainEvent.DocumentTranslationCompleted -> withContext(Dispatchers.Swing) {
                        documentTranslationDialog.complete(event.outputFile)
                    }
                    is MainEvent.DocumentTranslationFailed -> withContext(Dispatchers.Swing) {
                        documentTranslationDialog.fail(event.message)
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
                        // The definition strip is switched off entirely.
                        //
                        // Gated on its own setting rather than on dictionaryAutoSource. That
                        // setting used to mean "open the dictionary popup by itself", and anyone
                        // who found that intrusive turned it off — which would now also cost them
                        // the quiet one-line definition, a different thing they never refused.
                        !curr.isDictionaryAutoPopupEnabled -> false
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
                    // Both sides of the translation are offered, in preference order. SOURCE means
                    // the user asked for the word they typed; otherwise the translation comes
                    // first, since that is what they are looking at.
                    //
                    // Both matter because dictionaries are lopsided. Translating English into
                    // Arabic and defining only the Arabic would ask Google Dictionary for a
                    // language it barely holds, and produce nothing every single time.
                    val preferSource =
                        key.autoSource == com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource.SOURCE
                    val (word, lang) =
                        if (preferSource) key.inputText to key.resolvedSourceLang
                        else key.translatedText to key.targetLang
                    val (alternate, alternateLang) =
                        if (preferSource) key.translatedText to key.targetLang
                        else key.inputText to key.resolvedSourceLang

                    // Not a single word, so no definition belongs under the result.
                    if (word.isBlank() || word.contains(Regex("\\s")) || word.length < 2) {
                        mainStore.dispatch(MainIntent.UpdateInlineDefinition(""))
                        return@collect
                    }

                    // A single word: fetch the short definition that sits beneath the translation,
                    // in the popup and in the main window alike. Nothing is opened for it.
                    mainStore.dispatch(
                        MainIntent.UpdateInlineDefinition(
                            word = word,
                            language = lang,
                            alternateWord = alternate.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }.orEmpty(),
                            alternateLanguage = alternateLang
                        )
                    )
                    val current = mainStore.state.value.dictionaryWord
                    if (word.equals(current, ignoreCase = true)) return@collect

                    // Only ever fills in a dictionary the user already has open, and only in the
                    // main window. It never summons one.
                    //
                    // Translating a single word used to make the dictionary popup appear on its
                    // own. That was wrong twice over: it fired during Quick Translate with the
                    // main window hidden, so a hotkey translation produced two windows when one
                    // was asked for; and even in the main window it decided for the user that a
                    // short word meant they wanted a definition. Looking a word up is now an
                    // action they take — see the definition button on the output pane.
                    if (key.panelVisible && key.mainVisible) {
                        withContext(Dispatchers.Swing) {
                            mainContentView.setDictionarySearchWord(word)
                        }
                        mainStore.dispatch(MainIntent.LookupWord(word, lang))
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

                        // Also LOCAL-only, and every one of these needs something the frame
                        // owns — a dialog, the clipboard, or the content view — so they are
                        // handled here for the same reason FOCUS_* is.
                        HotkeyAction.COPY_TRANSLATION -> {
                            val text = mainStore.state.value.translatedText
                            if (text.isNotBlank()) {
                                text.copyToClipboard()
                                mainStore.dispatch(MainIntent.NotifyTextCopied)
                            }
                        }
                        HotkeyAction.CLEAR_INPUT -> {
                            mainStore.dispatch(MainIntent.UpdateInputText(""))
                            mainContentView.switchToAndFocusInput()
                        }
                        HotkeyAction.SWAP_LANGUAGES     -> mainStore.dispatch(MainIntent.SwapLanguages)
                        HotkeyAction.OPEN_SETTINGS      -> openSettingsDialog()
                        HotkeyAction.SHOW_HISTORY       -> showHistoryDialog()
                        HotkeyAction.TRANSLATE_DOCUMENT -> documentTranslationDialog.open()

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
                // Turning the panel on used to reveal an empty one, which stayed empty until the
                // next translation and read as broken. Filling it is the point of switching it on,
                // and costs only the extra request, not a second translation.
                mainStore.dispatch(MainIntent.RefreshExtraOutput)
            },
            onShowDictionary = { showDictionaryDialog() },
            onShowImageSearch = { showImageSearchDialog() },
            onShowHistory = { showHistoryDialog() },
            onTranslateDocument = { documentTranslationDialog.open() },
            onShowSettings = { openSettingsDialog() },
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
            imageSearch = localizer.getString("system_tray_menu.image_search"),
            history = localizer.getString("system_tray_menu.history"),
            translateDocument = localizer.getString("main_window_main_menu.translate_document"),
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
            imageSearch = localizer.getString("system_tray_menu.image_search"),
            textRecognition = localizer.getString("system_tray_menu.recognize_text"),
            history = localizer.getString("system_tray_menu.history"),
            settings = localizer.getString("system_tray_menu.settings"),
            toggleHotkeys = localizer.getString("system_tray_menu.enable_hotkeys"),
            exit = localizer.getString("system_tray_menu.exit")
        )

        val actions = TrayMenuActions(
            onShowApplication = { runOnUi { showAndFocus() } },
            onShowDictionary = { showDictionaryDialog() },
            onShowImageSearch = { showImageSearchDialog() },
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
        dialog.minimumSize = Dimension(UIScale.scale(320), dialog.height)
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
        val selectedTranslatorId = activePreset?.selectedServices?.get(ServiceRole.TRANSLATOR)
        val selectedTranslator = mainState.availableServices.find { it.id == selectedTranslatorId }

        return QuickTranslateDialogState(
            isVisible = mainState.isQuickTranslateDialogVisible,
            isLoading = mainState.isLoading,
            translatedText = mainState.translatedText,
            isPinned = mainState.isQuickTranslateDialogPinned,
            triggerCount = mainState.quickTranslateTriggerCount,
            isTtsPlaying = mainState.isTtsPlaying,
            definition = mainState.inlineDefinition,

            sourceLanguage = displaySourceLanguage,
            targetLanguage = mainState.targetLanguage,
            availableLanguages = mainState.availableLanguages,
            detectedSourceLanguage = mainState.detectedSourceLanguage,

            translatorSelectorState = QuickTranslateSelectorState(
                availableTranslators = mainState.getAvailableServicesFor(ServiceRole.TRANSLATOR),
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
                closeOnClickOutside = config.closePopupsOnClickOutside,
                lastKnownSize = config.popupLastKnownSize,
                lastKnownPosition = config.popupLastKnownPosition
            ),
            strings = DialogStrings(
                copyTooltip = localizer.getString("common.copy"),
                closeTooltip = localizer.getString("common.close"),
                listenTooltip = localizer.getString("common.listen"),
                stopListeningTooltip = localizer.getString("common.stop"),
                pinTooltip = localizer.getString("common.pin"),
                unpinTooltip = localizer.getString("common.unpin"),
                swapTooltip = localizer.getString("main_window_language_bar.swap_languages_tooltip"),
                loadingText = localizer.getString("common.loading")
            )
        )
    }

    /**
     * Opens the document translation dialog when a supported file is dropped on the window.
     *
     * Document translation was otherwise reachable only through a menu item and a toolbar
     * button, even though dropping a file on the window is the obvious gesture for it.
     * Unsupported files are ignored so dropping an image or an archive does nothing rather
     * than opening a dialog that cannot proceed.
     */
    /** Opens Settings with the correct orientation. Shared by the menu and the Ctrl+Comma binding. */
    private fun openSettingsDialog() {
        val dialog = createSettingsDialog()
        dialog.applyComponentOrientation(
            if (localizer.isRtl) ComponentOrientation.RIGHT_TO_LEFT
            else ComponentOrientation.LEFT_TO_RIGHT
        )
        dialog.isVisible = true
    }

    /**
     * The window's single drop target, for pictures and documents alike.
     *
     * It used to be two: the input pane took images and the frame took documents. Because the pane
     * claimed every file list — documents included — a `.docx` dropped on it was accepted and then
     * quietly discarded, so document drop worked only on the window chrome. Handling both here
     * means a drop behaves the same wherever in the window it lands, which is what anyone dropping
     * a file expects.
     *
     * Plain text is deliberately declined so a text drag still reaches the editor under the
     * pointer and inserts there.
     */
    private fun setupDropTarget() {
        val onContent: (DroppedContent) -> Unit = { content ->
            when (content) {
                is DroppedContent.Picture ->
                    mainStore.dispatch(MainIntent.OcrAndTranslateImage(content.image.toImageData("png")))
                is DroppedContent.Document ->
                    SwingUtilities.invokeLater { documentTranslationDialog.openWith(content.file) }
                DroppedContent.None -> Unit
            }
        }
        val onDragOver = { dragOverlay.keepShowing() }
        val onDropped = { dragOverlay.hide() }

        // The frame covers window chrome; the overlay covers itself once it is showing, since a
        // visible glass pane is what the pointer is over; the panes cover themselves because
        // Swing asks no one else once they own the pointer.
        rootPane.installContentDropHandler(onContent, onDragOver, onDropped)
        dragOverlay.component.installContentDropHandler(onContent, onDragOver, onDropped)
        mainContentView.installDropHandling(onContent, onDragOver, onDropped)
    }

    private fun setupGlobalHotkeys() {
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) {
                globalKeyListener.initialize()
                val config = settingsStore.state.value.workingConfiguration
                globalKeyListener.setHotkeysEnabled(config.isGlobalHotkeysEnabled)
                globalKeyListener.setSelectionIconEnabled(config.isSelectionIconEnabled)
                registerLocalHotkeys()
            }

            override fun windowClosed(e: WindowEvent?) {
                selectionTranslateButton.dispose()
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

    /**
     * Opens the image popup from a menu, seeded with the input text when it is a single word.
     *
     * The hotkey and the context menu both start from a selection; a menu click has none, so it
     * falls back to what is in the input pane and otherwise opens empty for the user to type in.
     */
    private fun showImageSearchDialog() {
        val term = mainStore.state.value.inputText.trim()
            .takeIf { it.isNotBlank() && !it.contains(' ') } ?: ""
        mainStore.dispatch(MainIntent.ShowImageSearch(term, resolvedLookupLanguage()))
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
        val availableDicts = s.getAvailableServicesFor(com.github.ahatem.qtranslate.api.plugin.ServiceRole.DICTIONARY)
        val selectedDictId = config.getActivePreset()
            ?.selectedServices?.get(com.github.ahatem.qtranslate.api.plugin.ServiceRole.DICTIONARY)

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
            listenTooltip         = localizer.getString("common.listen"),
            stopListeningTooltip  = localizer.getString("common.stop"),
            isLoading             = s.isDictionaryLoading,
            isTtsPlaying          = s.isTtsPlaying,
            entries               = s.dictionaryEntries,
            lookedUpWord          = s.dictionaryWord,
            hasFailed             = s.dictionaryFailed,
            availableDictionaries = availableDicts,
            selectedDictionaryId  = selectedDictId,
            onLookup = { word -> mainStore.dispatch(MainIntent.LookupWord(word, resolvedLang)) },
            onListen = { word -> listenToLookedUpWord(word) },
            onStopListening = { mainStore.dispatch(MainIntent.StopTTS) },
            onDictionarySelected = { serviceId ->
                settingsStore.dispatch(
                    SettingsIntent.UpdateServiceInActivePreset(
                        com.github.ahatem.qtranslate.api.plugin.ServiceRole.DICTIONARY, serviceId
                    )
                )
                val currentWord = mainStore.state.value.dictionaryWord
                if (currentWord.isNotBlank()) mainStore.dispatch(MainIntent.LookupWord(currentWord, resolvedLang))
            }
        )
    }

    /**
     * Speaks a dictionary headword in the language it was looked up in.
     *
     * A lookup can be triggered from either side of a translation, so the word does not reliably
     * belong to the input panel; [MainState.dictionaryLanguage] is what the lookup actually used.
     */
    private fun listenToLookedUpWord(word: String) {
        mainStore.dispatch(
            MainIntent.ListenToText(
                textSource = TextSource.Input,
                text = word,
                language = mainStore.state.value.dictionaryLanguage
            )
        )
    }

    /**
     * The language a looked-up term should be treated as.
     *
     * The chosen source language when there is one, otherwise whatever detection found, otherwise
     * English. "Auto" is not a language a dictionary or an image search can be asked about.
     */
    private fun resolvedLookupLanguage(state: MainState = mainStore.state.value): LanguageCode = when {
        state.sourceLanguage != LanguageCode.AUTO -> state.sourceLanguage
        state.detectedSourceLanguage != null      -> state.detectedSourceLanguage!!
        else                                      -> LanguageCode("en")
    }

    private fun buildImageSearchDialogState(
        mainState: MainState,
        config: Configuration
    ): ImageSearchDialogState {
        val serviceType = com.github.ahatem.qtranslate.api.plugin.ServiceRole.IMAGE_SEARCH
        val available = mainState.getAvailableServicesFor(serviceType)
        val selectedId = config.getActivePreset()?.selectedServices?.get(serviceType)
        val language = resolvedLookupLanguage(mainState)

        return ImageSearchDialogState(
            isVisible         = mainState.isImageSearchVisible,
            isLoading         = mainState.isImageSearchLoading,
            results           = mainState.imageResults,
            searchedTerm      = mainState.imageSearchTerm,
            hasFailed         = mainState.imageSearchFailed,
            isPinned          = mainState.isImageSearchPinned,
            triggerCount      = mainState.imageSearchTriggerCount,
            availableServices = available,
            selectedServiceId = selectedId,
            config = ImageSearchConfig(
                lastKnownSize     = config.imageSearchLastKnownSize,
                lastKnownPosition = config.imageSearchLastKnownPosition,
                positionNearMouse = config.isImageSearchAutoPositionEnabled,
                closeOnClickOutside = config.closePopupsOnClickOutside,
                transparencyPercentage = config.imageSearchTransparencyPercentage
            ),
            strings = ImageSearchStrings(
                title             = localizer.getString("image_search_dialog.title"),
                hintMessage       = localizer.getString("image_search_dialog.hint_message"),
                loadingMessage    = localizer.getString("image_search_dialog.loading_message"),
                notFoundMessage   = localizer.getString(
                    "image_search_dialog.not_found_message",
                    mainState.imageSearchTerm
                ),
                errorMessage      = localizer.getString("image_search_dialog.error_message"),
                searchButtonLabel = localizer.getString("image_search_dialog.search_button"),
                openTooltip       = localizer.getString("image_search_dialog.open_tooltip"),
                openSourceLabel   = localizer.getString("image_search_dialog.open_source"),
                backLabel         = localizer.getString("image_search_dialog.back"),
                pinTooltip        = localizer.getString("common.pin"),
                unpinTooltip      = localizer.getString("common.unpin"),
                closeTooltip      = localizer.getString("common.close")
            ),
            onSearch = { term -> mainStore.dispatch(MainIntent.SearchImages(term, language)) },
            onServiceSelected = { serviceId ->
                settingsStore.dispatch(SettingsIntent.UpdateServiceInActivePreset(serviceType, serviceId))
                val term = mainStore.state.value.imageSearchTerm
                if (term.isNotBlank()) mainStore.dispatch(MainIntent.SearchImages(term, language))
            },
            // The description page rather than the raw image: it carries the licence and the
            // caption, which is what someone looking a term up actually wants to read.
            onImageOpened = { result -> openUrl(result.sourceUrl ?: result.fullUrl) },
            onPinToggled = { mainStore.dispatch(MainIntent.ToggleImageSearchPin) },
            onClose = { mainStore.dispatch(MainIntent.HideImageSearch) },
            onSavePosition = { position ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(imageSearchLastKnownPosition = position) }
                )
                settingsStore.dispatch(SettingsIntent.SaveChanges)
            },
            onSaveSize = { size ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(imageSearchLastKnownSize = size) }
                )
                settingsStore.dispatch(SettingsIntent.SaveChanges)
            }
        )
    }

    private fun buildQuickDictionaryDialogState(
        mainState: MainState,
        config: Configuration
    ): QuickDictionaryDialogState {
        val availableDicts = mainState.getAvailableServicesFor(
            com.github.ahatem.qtranslate.api.plugin.ServiceRole.DICTIONARY
        )
        val selectedDictId = config.getActivePreset()
            ?.selectedServices?.get(com.github.ahatem.qtranslate.api.plugin.ServiceRole.DICTIONARY)

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
            triggerCount         = mainState.quickDictionaryTriggerCount,
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
                closeOnClickOutside  = config.closePopupsOnClickOutside,
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
                closeTooltip     = localizer.getString("common.close"),
                listenTooltip    = localizer.getString("common.listen"),
                stopListeningTooltip = localizer.getString("common.stop")
            ),
            isTtsPlaying = mainState.isTtsPlaying,
            onLookup = { word -> mainStore.dispatch(MainIntent.LookupWord(word, resolvedLang)) },
            onListen = { word -> listenToLookedUpWord(word) },
            onStopListening = { mainStore.dispatch(MainIntent.StopTTS) },
            onDictionarySelected = { serviceId ->
                settingsStore.dispatch(
                    SettingsIntent.UpdateServiceInActivePreset(
                        com.github.ahatem.qtranslate.api.plugin.ServiceRole.DICTIONARY, serviceId
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
            errorDetailPopup.closeLabel   = localizer.getString("common.close")

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
            StatusCode.TextCopied                  -> localizer.getString("status_bar.text_copied")
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
            StatusCode.NoTermToIllustrate           -> localizer.getString("status_bar.no_term_to_illustrate")
            StatusCode.NoImageSearchServiceActive   -> localizer.getString("status_bar.no_image_search_active")
            StatusCode.SearchingImages              -> localizer.getString("status_bar.searching_images")
            StatusCode.ImageSearchReady             -> localizer.getString("status_bar.image_search_ready")
            is StatusCode.ImagesNotFound            -> localizer.getString("status_bar.images_not_found", code.term)
            StatusCode.ImageSearchTimeout           -> localizer.getString("status_bar.image_search_timeout")
            is StatusCode.ImageSearchFailed         -> localizer.getString("status_bar.image_search_failed", code.summary)
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
