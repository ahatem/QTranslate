package com.github.ahatem.qtranslate.ui.swing.main

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.components.FlatButton
import com.formdev.flatlaf.util.FontUtils
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.mvi.MainEvent
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.plugin.storage.AppSecretStore
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId
import com.github.ahatem.qtranslate.core.settings.data.*
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.shared.notification.NotificationCode
import com.github.ahatem.qtranslate.ui.swing.about.InfoDialog
import com.github.ahatem.qtranslate.ui.swing.about.InfoDialogState
import com.github.ahatem.qtranslate.ui.swing.dictionary.DictionaryDialog
import com.github.ahatem.qtranslate.ui.swing.dictionary.QuickDictionaryDialog
import com.github.ahatem.qtranslate.ui.swing.dictionary.buildDictionaryDialogState
import com.github.ahatem.qtranslate.ui.swing.dictionary.buildQuickDictionaryDialogState
import com.github.ahatem.qtranslate.ui.swing.imagesearch.ImageSearchDialog
import com.github.ahatem.qtranslate.ui.swing.imagesearch.buildImageSearchDialogState
import com.github.ahatem.qtranslate.ui.swing.document.DocumentTranslationDialog
import com.github.ahatem.qtranslate.ui.swing.document.DocumentTranslationStrings
import com.github.ahatem.qtranslate.ui.swing.history.HistoryDialog
import com.github.ahatem.qtranslate.ui.swing.history.buildHistoryDialogState
import com.github.ahatem.qtranslate.ui.swing.main.statusbar.NotificationPopover
import com.github.ahatem.qtranslate.ui.swing.main.statusbar.StatusBarController
import com.github.ahatem.qtranslate.ui.swing.update.UpdateDialog
import com.github.ahatem.qtranslate.ui.swing.update.UpdateDialogState
import com.github.ahatem.qtranslate.ui.swing.main.input.InputRuntimeState
import com.github.ahatem.qtranslate.ui.swing.main.input.LocalHotkeyRegistration
import com.github.ahatem.qtranslate.ui.swing.main.input.PasteInjector
import com.github.ahatem.qtranslate.ui.swing.main.input.QInputPasteInjector
import com.github.ahatem.qtranslate.ui.swing.main.layout.DockRoomPlanner
import com.github.ahatem.qtranslate.ui.swing.main.layout.LayoutManager
import com.github.ahatem.qtranslate.ui.swing.main.lookup.AutoLookupCoordinator
import com.github.ahatem.qtranslate.ui.swing.main.menus.*
import com.github.ahatem.qtranslate.ui.swing.quicktranslate.QuickTranslateDialog
import com.github.ahatem.qtranslate.ui.swing.quicktranslate.LoadingIndicator
import com.github.ahatem.qtranslate.ui.swing.quicktranslate.LoadingIndicatorState
import com.github.ahatem.qtranslate.ui.swing.quicktranslate.buildQuickTranslateDialogState
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
import com.github.ahatem.qtranslate.ui.swing.shared.util.copyToClipboard
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.*
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.connectedScreenBounds
import com.github.ahatem.qtranslate.ui.swing.shared.util.isPositionReachable
import java.util.Locale

class MainAppFrame(
    private val mainStore: MainStore,
    private val settingsStore: SettingsStore,
    private val iconManager: IconManager,
    private val themeManager: ThemeManager,
    private val pluginManager: PluginManager,
    private val localizer: LocalizationManager,
    private val notificationBus: NotificationBus,
    private val logger: Logger,
    /**
     * Translates one string, used by the language editor to offer a suggestion for an untranslated
     * key. Optional so a frame can be built without a translator, which simply hides the action.
     */
    private val translateString: (suspend (String, LanguageCode) -> Result<String>)? = null,
    /** The application's own secrets, for the proxy password on the Network settings page. */
    private val appSecrets: AppSecretStore? = null,
    /**
     * The single entry point into application shutdown, invoked once every exit route (main
     * window EXIT close, tray Exit, close-dialog Exit) has disposed the frame. Receives the
     * final window bounds, captured here while the frame is still live. This frame does not
     * own application-level shutdown itself: it only reports that the user asked to exit.
     */
    private val onApplicationExit: (Size, Position) -> Unit = { _, _ -> },
    /**
     * True when this process was launched from the Windows startup registration (#226).
     *
     * The frame is then constructed exactly as usual — tray, hotkeys, services — but never
     * made visible, so no window flashes and no taskbar entry appears until the user restores
     * it through the canonical [showAndFocus] path. The caller guarantees a tray is available
     * before passing true, so the window always has a recovery path.
     */
    private val initiallyHidden: Boolean = false
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

    private val quickTranslateDialog by lazy {
        QuickTranslateDialog(
            owner = this,
            iconManager = iconManager,
            localizationManager = localizer,
            onDismiss = { mainStore.dispatch(MainIntent.HideQuickTranslate) },
            onTranslatorSelected = { serviceId ->
                // Same promotion semantics as the Comparison header: the old
                // primary takes the promoted translator's comparison slot.
                settingsStore.dispatch(
                    SettingsIntent.PromoteTranslatorToPrimary(serviceId)
                )
                mainStore.dispatch(MainIntent.RetranslateQuickTranslate)
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
                mainStore.dispatch(MainIntent.RetranslateQuickTranslate)
            },
            onTargetLanguageSelected = { language ->
                mainStore.dispatch(MainIntent.SelectTargetLanguage(language))
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(preferredTargetLanguage = language.tag) }
                )
                mainStore.dispatch(MainIntent.RetranslateQuickTranslate)
            },
            onSwapLanguages = {
                mainStore.dispatch(MainIntent.SwapLanguages)
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
        availableTranslatorIds = { mainStore.state.value.availableTranslatorIds },
        translateString = translateString,
        appSecrets = appSecrets,
        pauseGlobalHotkeys  = { globalKeyListener.setPaused(true) },
        resumeGlobalHotkeys = { globalKeyListener.setPaused(false) },
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
        },
        onEnsureLookupDockRoom = { ensureRoomForLookupDock() },
        onOpenImageSource = { result -> openUrl(result.sourceUrl ?: result.fullUrl) }
    )

    private val autoLookupCoordinator = AutoLookupCoordinator(
        mainState = mainStore.state,
        settingsState = settingsStore.state,
        dispatch = { mainStore.dispatch(it) },
        isMainVisible = { isVisible },
        setDictionarySearchWord = { mainContentView.setDictionarySearchWord(it) },
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

    /** Returns true when [screenPoint] is inside any visible top-level Swing window we own. */
    private fun isQTranslateWindowAt(screenPoint: Point): Boolean =
        Window.getWindows().any { window ->
            window.isShowing && window.bounds.contains(screenPoint)
        }

    /** Returns true when the active Swing window belongs to this process. */
    private fun isQTranslateWindowActive(): Boolean {
        val activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: return isActive
        return Window.getWindows().any { it === activeWindow && it.isShowing }
    }

    private val globalKeyListener = MainGlobalKeyListener(
        scope = appScope,
        logger = logger,
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
                val lang = mainStore.state.value.resolvedSourceLanguage
                mainStore.dispatch(MainIntent.ShowQuickDictionary(selectedText, lang))
            }
        },
        onShowImages = { selectedText ->
            // Refreshes in place when already open, for the same reason as the dictionary.
            appScope.launch {
                mainStore.dispatch(MainIntent.ShowImageSearch(selectedText, mainStore.state.value.resolvedSourceLanguage))
            }
        },
        onTranslate = { mainStore.dispatch(MainIntent.Translate()) },
        onSelectionDetected = { text, location ->
            runOnUi {
                val behavior = settingsStore.state.value.originalConfiguration.selectionBehavior
                when (SelectionBehaviorRouter.decide(behavior, isQTranslateWindowActive())) {
                    SelectionAction.NONE -> Unit
                    SelectionAction.SHOW_ICON -> selectionTranslateButton.showAt(location, text)
                    SelectionAction.TRANSLATE ->
                        mainStore.dispatch(MainIntent.ShowQuickTranslate(text))
                    SelectionAction.TRANSLATE_AND_READ ->
                        mainStore.dispatch(MainIntent.ShowQuickTranslate(text, readSelectionAloud = true))
                }
            }
        },
        onPointerPressed = { location ->
            runOnUi {
                selectionTranslateButton.dismissIfOutside(location)
                dismissPopupsPressedOutside(location)
            }
        },
        shouldTrackSelectionAt = { location -> !isQTranslateWindowAt(location) }
    )

    internal var pasteInjector: PasteInjector =
        QInputPasteInjector(backend = { globalKeyListener.inputBackend() }, logger = logger)

    /** LOCAL-scope hotkeys, installed on the root pane. */
    private val localHotkeyRegistration = LocalHotkeyRegistration(
        rootPane = rootPane,
        bindings = { globalKeyListener.getLocalBindings() },
        directHandlers = mapOf(
            // FOCUS_* depend on which panes the current layout shows, so they go to
            // MainContentView directly.
            HotkeyAction.FOCUS_INPUT        to { mainContentView.focusInput() },
            HotkeyAction.FOCUS_OUTPUT       to { mainContentView.focusOutput() },
            HotkeyAction.FOCUS_EXTRA_OUTPUT to { mainContentView.focusExtraOutput() },
            // These need something the frame owns: a dialog, the clipboard, or the content view.
            HotkeyAction.COPY_TRANSLATION to {
                val text = mainStore.state.value.translatedText
                if (text.isNotBlank()) {
                    text.copyToClipboard()
                    mainStore.dispatch(MainIntent.NotifyTextCopied)
                }
            },
            HotkeyAction.CLEAR_INPUT to {
                mainStore.dispatch(MainIntent.UpdateInputText(""))
                mainContentView.focusInput()
            },
            HotkeyAction.SWAP_LANGUAGES     to { mainStore.dispatch(MainIntent.SwapLanguages) },
            HotkeyAction.OPEN_SETTINGS      to { openSettingsDialog() },
            HotkeyAction.SHOW_HISTORY       to { showHistoryDialog() },
            HotkeyAction.TRANSLATE_DOCUMENT to { documentTranslationDialog.open() },
        ),
        dispatch = { binding -> globalKeyListener.dispatchLocalAction(binding) },
    )

    /**
     * Fixed Escape-to-hide binding (#216, first half).
     *
     * Hides (`isVisible = false`) rather than disposing or exiting, so tray/global
     * hotkeys and the next normal show action restore the same window with its
     * text/state intact. An in-flight translation is cancelled first instead of
     * hiding, and an open menu/popup keeps Escape for its own dismissal.
     * See [MainWindowEscapeBinding] for the precedence contract.
     */
    private val escapeBinding = MainWindowEscapeBinding(
        rootPane = rootPane,
        isTranslationInFlight = { mainStore.state.value.isLoading },
        isChildHandlingEscape = { isEscapeOwnedByChild() },
        onCancelTranslation = { mainStore.dispatch(MainIntent.CancelTranslation) },
        onHide = { isVisible = false },
    )

    /**
     * True while a child owns Escape for its own dismissal, so the main-window
     * hide binding stays out of the way. Covers lightweight popups/menus in this
     * window (same focused window) and visible owned dialogs.
     */
    private fun isEscapeOwnedByChild(): Boolean {
        if (MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()) return true
        if (ownedWindows.any { it.isVisible }) return true
        return false
    }

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

    private val statusBarController: StatusBarController = StatusBarController(
        statusBar = mainContentView.statusBar,
        notificationPopover = notificationPopover,
        iconManager = iconManager,
        localizer = localizer,
        scope = appScope,
        defaultMessage = localizer.getString("main_window_status_bar.ready_message")
    )

    init {
        // One explicit runtime input state: bindings and selection follow saved configuration,
        // the enabled switch and dismissal follow the working copy like their enforcement paths.
        val storeState = settingsStore.state.value
        globalKeyListener.updateRuntimeState(
            InputRuntimeState(
                bindings = storeState.originalConfiguration.hotkeys,
                globalHotkeysEnabled = storeState.workingConfiguration.isGlobalHotkeysEnabled,
                selectionCaptureEnabled = storeState.originalConfiguration.selectionBehavior.selectionCaptureEnabled,
                dismissOnOutsideClickEnabled = storeState.workingConfiguration.closePopupsOnClickOutside
            )
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

            iconImages = applicationIcons

            mainContentView.render(mainStore.state.value, settingsStore.state.value)
            pack()
            // After pack, because deciding whether a position is still reachable needs the size
            // the window actually ended up with.
            restorePosition(config.mainWindowPosition)

            // Enforce Input → Output → Extra (→ Input) Tab cycle across all layouts.
            focusTraversalPolicy = TextPaneCycleFocusPolicy(mainContentView)

            setupWindowListeners()
            setupMenuBar()
            setupTrayMenu()
            setupDropTarget()
            escapeBinding.register()

            observeStateAndEvents()
            // A login launch stays hidden in the tray: the window is never shown, not shown
            // and re-hidden, so nothing flashes and no taskbar entry appears (#226).
            isVisible = !initiallyHidden

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
            logger.error("Unhandled exception in a MainAppFrame coroutine", throwable)
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
                            logger.error("Failed to apply theme", e)
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
                                val dialogState = buildQuickTranslateDialogState(
                                    mainState,
                                    settingsState.workingConfiguration,
                                    localizer
                                )
                                quickTranslateDialog.render(dialogState)
                            }

                            if (mainState.isQuickDictionaryVisible || quickDictionaryDialog.isVisible) {
                                quickDictionaryDialog.render(
                                    buildQuickDictionaryDialogState(
                                        mainState = mainState,
                                        config = settingsState.workingConfiguration,
                                        localizer = localizer,
                                        onLookup = { word ->
                                            mainStore.dispatch(MainIntent.LookupWord(word, mainState.resolvedSourceLanguage))
                                        },
                                        onListen = { word -> listenToLookedUpWord(word) },
                                        onStopListening = { mainStore.dispatch(MainIntent.StopTTS) },
                                        onDictionarySelected = { serviceId ->
                                            settingsStore.dispatch(
                                                SettingsIntent.UpdateServiceInActivePreset(
                                                    ServiceRole.DICTIONARY, serviceId
                                                )
                                            )
                                            val currentWord = mainStore.state.value.dictionaryWord
                                            if (currentWord.isNotBlank()) {
                                                mainStore.dispatch(
                                                    MainIntent.LookupWord(currentWord, mainState.resolvedSourceLanguage)
                                                )
                                            }
                                        },
                                        onAutoSourceChanged = { newSource ->
                                            settingsStore.dispatch(
                                                SettingsIntent.ToggleSetting {
                                                    it.copy(dictionaryAutoSource = newSource)
                                                }
                                            )
                                            settingsStore.dispatch(SettingsIntent.SaveChanges)
                                        },
                                        onPinToggled = {
                                            mainStore.dispatch(MainIntent.ToggleQuickDictionaryPin)
                                        },
                                        onClose = { mainStore.dispatch(MainIntent.HideQuickDictionary) },
                                        onSavePosition = { position ->
                                            settingsStore.dispatch(
                                                SettingsIntent.ToggleSetting {
                                                    it.copy(quickDictionaryLastKnownPosition = position)
                                                }
                                            )
                                            settingsStore.dispatch(SettingsIntent.SaveChanges)
                                        },
                                        onSaveSize = { size ->
                                            settingsStore.dispatch(
                                                SettingsIntent.ToggleSetting {
                                                    it.copy(quickDictionaryLastKnownSize = size)
                                                }
                                            )
                                            settingsStore.dispatch(SettingsIntent.SaveChanges)
                                        }
                                    )
                                )
                            }

                            if (mainState.isImageSearchVisible || imageSearchDialog.isVisible) {
                                imageSearchDialog.render(
                                    buildImageSearchDialogState(
                                        mainState = mainState,
                                        config = settingsState.workingConfiguration,
                                        localizer = localizer,
                                        onSearch = { term ->
                                            mainStore.dispatch(
                                                MainIntent.SearchImages(term, mainState.resolvedSourceLanguage)
                                            )
                                        },
                                        onServiceSelected = { serviceId ->
                                            settingsStore.dispatch(
                                                SettingsIntent.UpdateServiceInActivePreset(
                                                    ServiceRole.IMAGE_SEARCH, serviceId
                                                )
                                            )
                                            val term = mainStore.state.value.imageSearchTerm
                                            if (term.isNotBlank()) {
                                                mainStore.dispatch(
                                                    MainIntent.SearchImages(term, mainState.resolvedSourceLanguage)
                                                )
                                            }
                                        },
                                        onImageOpened = { result ->
                                            openUrl(result.sourceUrl ?: result.fullUrl)
                                        },
                                        onPinToggled = {
                                            mainStore.dispatch(MainIntent.ToggleImageSearchPin)
                                        },
                                        onClose = { mainStore.dispatch(MainIntent.HideImageSearch) },
                                        onSavePosition = { position ->
                                            settingsStore.dispatch(
                                                SettingsIntent.ToggleSetting {
                                                    it.copy(imageSearchLastKnownPosition = position)
                                                }
                                            )
                                            settingsStore.dispatch(SettingsIntent.SaveChanges)
                                        },
                                        onSaveSize = { size ->
                                            settingsStore.dispatch(
                                                SettingsIntent.ToggleSetting {
                                                    it.copy(imageSearchLastKnownSize = size)
                                                }
                                            )
                                            settingsStore.dispatch(SettingsIntent.SaveChanges)
                                        }
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to render UI", e)
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

        // Toggling the setting takes effect immediately, hiding any button on screen; runtime
        // input state itself is driven by the unified collector below.
        appScope.launch(handler) {
            settingsStore.state
                .map { it.originalConfiguration.selectionBehavior }
                .distinctUntilChanged()
                .collect { behavior ->
                    if (behavior != SelectionBehavior.SHOW_ICON) {
                        withContext(Dispatchers.Swing) { selectionTranslateButton.dismiss() }
                    }
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

        // Runtime input state: one collector over every applied-state input. The boolean
        // switches are part of the observed key, so a bare enable/disable toggle must reconcile
        // even when the binding list itself did not change.
        appScope.launch(handler) {
            settingsStore.state
                .map { state ->
                    InputRuntimeState(
                        bindings = state.originalConfiguration.hotkeys,
                        globalHotkeysEnabled = state.originalConfiguration.isGlobalHotkeysEnabled,
                        selectionCaptureEnabled = state.originalConfiguration.selectionBehavior.selectionCaptureEnabled,
                        dismissOnOutsideClickEnabled = state.workingConfiguration.closePopupsOnClickOutside
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { runtimeState ->
                    globalKeyListener.updateRuntimeState(runtimeState)
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
                            LanguageCode(languageCode)
                        )
                    }
                    withContext(Dispatchers.Swing) {
                        applyOrientation(localizer.isRtl)
                    }
                }
        }

        appScope.launch(handler) {
            autoLookupCoordinator.observe()
        }

        // Persist whether the lookup dock is open, which is what the "show dictionary panel" setting remembers.
        appScope.launch(handler) {
            mainStore.state
                .map { it.isLookupDockOpen }
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
                            renderHistoryDialog()
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
                            renderDictionaryDialog()
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
        localHotkeyRegistration.register()
    }


    private fun pasteTextToActiveApp(text: String) {
        appScope.launch {
            // No settle delay: neutralization inside the injector waits for contaminating
            // modifiers deterministically.
            if (!pasteInjector.injectPaste(text)) {
                logger.warn("Paste translation was not dispatched")
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

    /**
     * Canonical user-facing main-window presentation (#216).
     *
     * Every path that intentionally presents the main window for interaction —
     * the global show hotkey, tray restore, second-instance activation — routes
     * through here so visibility, restore, and input focus stay consistent.
     * Focus lands in the input editor; text and state are left untouched.
     */
    fun showAndFocus() {
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
            onChangeExtraOutput = { type ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting(
                        update = { it.copy(extraOutputType = type) },
                        onSuccess = { saved ->
                            mainStore.dispatch(
                                MainIntent.RefreshExtraOutput(ExtraOutputRequest.from(saved))
                            )
                        }
                    )
                )
            },
            onShowDictionary = { showDictionaryDialog() },
            onShowImageSearch = { showImageSearchDialog() },
            onRecognizeText = { openSnippingTool() },
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
            extraOutput = localizer.getString("settings_translation.extra_output_group"),
            extraOutputNone = localizer.getString("settings_translation.type_none"),
            extraOutputBackward = localizer.getString("settings_translation.type_backward"),
            extraOutputSummarize = localizer.getString("settings_translation.type_summarize"),
            extraOutputRewrite = localizer.getString("settings_translation.type_rewrite"),
            viewOptions = localizer.getString("main_window_main_menu.options_submenu"),
            dictionary = localizer.getString("system_tray_menu.dictionary"),
            isDictionaryPanelOpen = mainStore.state.value.isDictionaryPanelVisible,
            imageSearch = localizer.getString("system_tray_menu.image_search"),
            recognizeText = localizer.getString("system_tray_menu.recognize_text"),
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
            layoutComparisonAvailable = currentConfig.isComparisonEligible(mainStore.state.value.availableTranslatorIds),
            layoutComparisonUnavailableHint = localizer.getString("settings_window.layout_comparison_unavailable"),
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
        val iconsList = applicationIcons

        if (iconsList.isEmpty()) {
            logger.error("Failed to load any tray icons")
            return
        }

        val multiResImage = java.awt.image.BaseMultiResolutionImage(*iconsList.toTypedArray())

        trayIcon = TrayIcon(multiResImage, "QTranslate").apply {
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
            logger.error("Failed to add tray icon", e)
            trayIcon = null
        }
    }

    private fun createTrayPopupMenu(): JPopupMenu {
        val currentConfig = settingsStore.state.value.originalConfiguration

        val strings = TrayMenuStrings(
            showApplication = localizer.getString("system_tray_menu.show_application"),
            dictionary = localizer.getString("system_tray_menu.dictionary"),
            imageSearch = localizer.getString("system_tray_menu.image_search"),
            textRecognition = localizer.getString("system_tray_menu.recognize_text"),
            translateDocument = localizer.getString("main_window_main_menu.translate_document"),
            history = localizer.getString("system_tray_menu.history"),
            textSelection = localizer.getString("settings_general.selection_group"),
            selectionBehaviorOff = localizer.getString("settings_general.selection_behavior_off"),
            selectionBehaviorIcon = localizer.getString("settings_general.selection_behavior_icon"),
            selectionBehaviorTranslate = localizer.getString("settings_general.selection_behavior_translate"),
            selectionBehaviorRead = localizer.getString("settings_general.selection_behavior_read"),
            settings = localizer.getString("system_tray_menu.settings"),
            toggleHotkeys = localizer.getString("system_tray_menu.enable_hotkeys"),
            exit = localizer.getString("system_tray_menu.exit")
        )

        val actions = TrayMenuActions(
            onShowApplication = { runOnUi { showAndFocus() } },
            onShowDictionary = { showDictionaryDialog() },
            onShowImageSearch = { showImageSearchDialog() },
            onRecognizeText = { openSnippingTool() },
            onTranslateDocument = { documentTranslationDialog.open() },
            onShowHistory = { showHistoryDialog() },
            onSelectionBehaviorChanged = { behavior ->
                settingsStore.dispatch(
                    SettingsIntent.ToggleSetting { it.copy(selectionBehavior = behavior) }
                )
            },
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

        return TrayMenuPopup(
            actions = actions,
            strings = strings,
            isHotkeysEnabled = currentConfig.isGlobalHotkeysEnabled,
            selectionBehavior = currentConfig.selectionBehavior,
        )
    }

    private fun setupWindowListeners() {

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) {
                mainContentView.requestFocusOnInput()
                initializeGlobalHotkeys()
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
                mainContentView.requestFocusOnInput()
            }

            /**
             * Single convergence point for every exit route: main-window EXIT close, tray
             * Exit and the close dialog's Exit all call [dispose], which fires this event
             * exactly once. Stops locally-owned resources synchronously, so no new global
             * hotkey or tray event can start after this point, then hands off to the
             * application-level shutdown owner for the suspend teardown and process exit.
             * Capturing bounds here, not via [saveWindowBounds], because by the time
             * shutdown finishes off the EDT the frame may already be disposed.
             */
            override fun windowClosed(e: WindowEvent?) {
                globalKeyListener.shutdown()
                selectionTranslateButton.dispose()
                trayIcon?.let { SystemTray.getSystemTray().remove(it) }
                trayIcon = null
                appScope.cancel()
                onApplicationExit(Size(width, height), Position(x, y))
            }
        })
        // Global hotkeys must work even when the frame starts hidden in the tray (#226):
        // windowOpened only fires once the window is first shown, which a login launch may
        // never do until the user restores it. Initializing here as well is safe: the
        // backend guards with an atomic check-and-set and local registration reinstalls.
        initializeGlobalHotkeys()
    }

    private fun saveWindowBounds() {
        val s = size
        val p = location
        settingsStore.dispatch(
            SettingsIntent.ToggleSetting {
                it.copy(
                    mainWindowSize = Size(s.width, s.height),
                    mainWindowPosition = Position(p.x, p.y)
                )
            }
        )
    }

    /**
     * Puts the window back where it was left, unless that is nowhere the user could see it.
     *
     * A position saved on a display that is no longer attached restores a window that is running
     * and focusable and entirely invisible, which is indistinguishable from the application having
     * failed to start.
     */
    private fun restorePosition(saved: Position?) {
        if (saved == null) {
            setLocationRelativeTo(null)
            return
        }
        if (isPositionReachable(Rectangle(saved.x, saved.y, width, height), connectedScreenBounds())) {
            setLocation(saved.x, saved.y)
        } else {
            logger.info("Saved window position (${saved.x}, ${saved.y}) is off every connected display; centring instead")
            setLocationRelativeTo(null)
        }
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
        val settingsButton = createButtonWithIcon(iconManager, Icons.SETTINGS, 18).apply {
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

    private val applicationIcons: List<Image> by lazy {
        listOf(16, 20, 24, 32, 48, 64, 128, 256, 512).mapNotNull { size ->
            try {
                ImageIO.read(javaClass.classLoader.getResourceAsStream("icons/app/icon-$size.png"))
            } catch (e: Exception) {
                logger.warn("Failed to load window icon ($size): ${e.message}")
                null
            }
        }
    }

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

    private fun initializeGlobalHotkeys() {
        globalKeyListener.initialize()
        val config = settingsStore.state.value.workingConfiguration
        val saved = settingsStore.state.value.originalConfiguration
        globalKeyListener.updateRuntimeState(
            InputRuntimeState(
                bindings = saved.hotkeys,
                globalHotkeysEnabled = config.isGlobalHotkeysEnabled,
                selectionCaptureEnabled = saved.selectionBehavior.selectionCaptureEnabled,
                dismissOnOutsideClickEnabled = config.closePopupsOnClickOutside
            )
        )
        registerLocalHotkeys()
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
            icon = iconManager.getIcon("icons/app/icon-64.png", 64, 64),
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
            AppNotification(
                type = NotificationType.INFO,
                code = NotificationCode.Custom(
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
     * Grows the frame before a main-window lookup opens, if that would help: a resizable frame
     * that is narrower than the workspace and dock together would comfortably like gets wider,
     * clamped to its monitor's work area, with its height and position otherwise left alone.
     *
     * Left alone entirely while maximized (the dock adapts to whatever width that already gives)
     * or while the frame already has enough room, so opening the dock a second time never moves
     * anything.
     */
    private fun ensureRoomForLookupDock() {
        if (!isVisible) return
        val gc = graphicsConfiguration ?: return
        val screenInsets = runCatching { toolkit.getScreenInsets(gc) }.getOrDefault(Insets(0, 0, 0, 0))
        val screenBounds = gc.bounds
        val workArea = Rectangle(
            screenBounds.x + screenInsets.left,
            screenBounds.y + screenInsets.top,
            screenBounds.width - screenInsets.left - screenInsets.right,
            screenBounds.height - screenInsets.top - screenInsets.bottom
        )
        val isMaximized = (extendedState and Frame.MAXIMIZED_HORIZ) == Frame.MAXIMIZED_HORIZ
        val chromeWidth = width - contentPane.width
        val wantedWidth = mainContentView.comfortableWidthWithDock() + chromeWidth

        val plan = DockRoomPlanner.plan(bounds, workArea, wantedWidth, isMaximized) ?: return
        bounds = plan
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
        val language = mainStore.state.value.resolvedSourceLanguage
        // From the main window the pictures dock beside the workspace when there is room; from the
        // tray there is no window to dock in, so they appear as the popup.
        if (isVisible) mainContentView.openImages(term, language)
        else mainStore.dispatch(MainIntent.ShowImageSearch(term, language))
    }

    private fun showDictionaryDialog() {
        val initialWord = mainStore.state.value.inputText.trim()
            .takeIf { it.isNotBlank() && !it.contains(' ') } ?: ""

        // Main window visible → the dock beside the workspace, or the popup if it has no room.
        if (isVisible) {
            mainContentView.toggleDictionary(initialWord)
        } else {
            dictionaryDialog.setSearchWord(initialWord)
            renderDictionaryDialog()
            if (initialWord.isNotBlank()) {
                mainStore.dispatch(MainIntent.LookupWord(initialWord))
            }
            dictionaryDialog.isVisible = true
            dictionaryDialog.toFront()
        }
    }

    private fun renderDictionaryDialog() {
        val mainState = mainStore.state.value
        dictionaryDialog.render(
            buildDictionaryDialogState(
                mainState = mainState,
                config = settingsStore.state.value.workingConfiguration,
                localizer = localizer,
                onLookup = { word ->
                    mainStore.dispatch(MainIntent.LookupWord(word, mainState.resolvedSourceLanguage))
                },
                onListen = { word -> listenToLookedUpWord(word) },
                onStopListening = { mainStore.dispatch(MainIntent.StopTTS) },
                onDictionarySelected = { serviceId ->
                    settingsStore.dispatch(
                        SettingsIntent.UpdateServiceInActivePreset(ServiceRole.DICTIONARY, serviceId)
                    )
                    val currentWord = mainStore.state.value.dictionaryWord
                    if (currentWord.isNotBlank()) {
                        mainStore.dispatch(
                            MainIntent.LookupWord(currentWord, mainState.resolvedSourceLanguage)
                        )
                    }
                }
            )
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

    private fun showHistoryDialog() {
        renderHistoryDialog()
        historyDialog.isVisible = true
        historyDialog.toFront()
    }

    private fun renderHistoryDialog() {
        historyDialog.render(
            buildHistoryDialogState(
                mainState = mainStore.state.value,
                localizer = localizer,
                onEntrySelected = { snapshot ->
                    mainStore.dispatch(MainIntent.RestoreHistoryEntry(snapshot))
                    historyDialog.isVisible = false
                },
                onClearAll = { mainStore.dispatch(MainIntent.ClearHistory) }
            )
        )
    }

}

/**
 * Custom focus-traversal policy for the main application window.
 *
 * When Tab/Shift+Tab is pressed inside any of the three text panes (input, output, extra),
 * focus moves directly to the next/previous pane in the cycle — skipping toolbar buttons,
 * scrollbars, and other intermediate components.
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
        return all[(idx + 1) % all.size]
    }

    override fun getComponentBefore(aContainer: Container, aComponent: Component): Component {
        val all = panes()
        val idx = all.indexOfFirst { it === aComponent }
        if (idx < 0) return fallback.getComponentBefore(aContainer, aComponent)
        return all[(idx - 1 + all.size) % all.size]
    }

    override fun getFirstComponent(aContainer: Container): Component =
        panes().firstOrNull() ?: fallback.getFirstComponent(aContainer)

    override fun getLastComponent(aContainer: Container): Component =
        panes().lastOrNull() ?: fallback.getLastComponent(aContainer)

    override fun getDefaultComponent(aContainer: Container): Component =
        panes().firstOrNull() ?: fallback.getDefaultComponent(aContainer)
}
