package com.github.ahatem.qtranslate.ui.swing.settings

import com.formdev.flatlaf.util.UIScale
import com.github.michaelbull.result.fold
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.ocr.OcrServiceCatalog
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.settings.panels.*
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.AppIcons
import com.github.ahatem.qtranslate.ui.swing.shared.util.TranslationCapabilities
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import com.github.ahatem.qtranslate.core.plugin.storage.AppSecretStore
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.data.SettingsPage
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import javax.swing.border.MatteBorder

class SettingsDialog(
    owner: JFrame,
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
    private val iconManager: IconManager,
    private val themeManager: ThemeManager,
    private val localizationManager: LocalizationManager,
    /** Shared OCR provider/language projection. Null only in harnesses that never open OCR. */
    private val ocrServiceCatalog: OcrServiceCatalog? = null,
    /**
     * Snapshot of languages currently supported by the active translator.
     * Evaluated lazily so [GeneralPanel] always gets the latest list when
     * it renders — not whatever was available when the dialog was constructed.
     */
    private val availableLanguages: () -> List<com.github.ahatem.qtranslate.api.language.LanguageCode> = { emptyList() },
    /**
     * Ids of currently usable translators, for Comparison eligibility.
     * Evaluated lazily so [LayoutPanel] always sees the latest registry —
     * not whatever was available when the dialog was constructed.
     */
    private val availableTranslatorIds: () -> List<String> = { emptyList() },
    /**
     * Which optional translation recipients the application can actually run, so the Translation
     * page never offers a summarizer or rewriter that is not there. Evaluated lazily: plugins can
     * be enabled or installed while this dialog is open.
     */
    private val translationCapabilities: () -> TranslationCapabilities = {
        TranslationCapabilities.UNKNOWN
    },
    /** Invoked just before the hotkey recorder opens; should disable global hotkeys. */
    private val pauseGlobalHotkeys:  (() -> Unit)? = null,
    /** Invoked after the recorder closes; should restore the global hotkey state. */
    private val resumeGlobalHotkeys: (() -> Unit)? = null,
    /**
     * Translates one string, for the language editor's suggestion action. Absent when nothing can
     * translate, which hides the action rather than offering one that fails.
     */
    private val translateString: (suspend (String, LanguageCode) -> Result<String>)? = null,
    /**
     * The application's own secrets, for the proxy password. Absent in contexts that have no
     * store, which hides the field rather than offering one that forgets what is typed in it.
     */
    private val appSecrets: AppSecretStore? = null,
    /**
     * Whether the window can actually be hidden into a tray. Evaluated lazily: the tray icon is
     * installed during frame construction, which may finish after the first dialog is built.
     */
    private val isTrayAvailable: () -> Boolean = { true },
    /**
     * Invoked on the event thread after a successful commit, with the configuration that is now
     * saved. Lets the owner apply preferences that describe live window state — the lookup dock —
     * without waiting for a restart.
     */
    private val onSettingsApplied: ((Configuration) -> Unit)? = null,
    /**
     * The page to open on — the one this session closed on, when the caller remembers it. Supplied
     * by the caller rather than kept here, so a dialog owns no state that outlives it.
     */
    private val rememberedPage: () -> String? = { null },
    /** Told which page is showing, so the caller can remember it for the next open. */
    private val onPageVisited: (String) -> Unit = {},
) : JDialog(owner, "Settings", true) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The proxy password, loaded once when the dialog opens.
     *
     * Held here because the secret store is suspending and the Network panel is built on the
     * event thread. Blocking there to read one value would freeze the dialog opening; the
     * page is built lazily on first navigation, by which time this has long since arrived.
     */
    private val proxyPassword = StagedSecret()
    private var savePending = false


    /** Held as a field so it can be removed again — a lambda passed inline never can be. */
    private val themeListener = java.beans.PropertyChangeListener { event ->
        if (event.propertyName == "lookAndFeel") SwingUtilities.invokeLater { updateBorders() }
    }

    private fun label(key: String) = localizationManager.getString("settings_dialog_sidebar.$key")

    /** The sidebar's page structure: order, grouping and icons. */
    private val navigation = SettingsNavigation(::label)

    // ── Widgets ───────────────────────────────────────────────────────────────
    private val contentArea = JPanel(BorderLayout())

    private val panelTitle = JLabel(navigation.pages.firstOrNull()?.label ?: "").apply {
        font = font.deriveFont(Font.BOLD, font.size + 3f)
    }

    /**
     * Resets the page that is currently shown.
     *
     * Deliberately in the header rather than in the button bar: it resets one page, and the bar
     * holds whole-dialog actions. It is disabled on a page that owns no settings of its own.
     *
     * The tooltip spells out both what it resets and what it keeps, because "Reset Page" beside a
     * "Reset All Settings" button is exactly the pair a user is most likely to confuse, and the
     * difference between them is the whole point of having two.
     */
    private val resetPageButton = JButton(
        localizationManager.getString("settings_dialog.reset_page_button")
    ).apply {
        addActionListener { onResetPage() }
        toolTipText = localizationManager.getString("settings_dialog.reset_page_tooltip")
        accessibleContext.accessibleDescription =
            localizationManager.getString("settings_dialog.reset_page_tooltip")
    }

    /** The page the reset-page action applies to; null where the page owns nothing to reset. */
    private var currentSettingsPage: SettingsPage? = null

    private val headerStrip = JPanel(BorderLayout(12, 0)).apply {
        add(panelTitle, BorderLayout.LINE_START)
        add(resetPageButton, BorderLayout.LINE_END)
    }

    /**
     * Page selection, lazy panel construction and search-hit activation.
     *
     * Panels are built the first time they are shown or searched, and one cache serves both, so
     * a page found by search is never rebuilt when it is opened by hand.
     */
    private val navigator = SettingsNavigationController(
        navigation = navigation,
        contentArea = contentArea,
        panelFactory = ::buildPanel,
        orientation = { componentOrientation },
        onPageShown = { name ->
            panelTitle.text = name
            updateResetPageAction(name)
            onPageVisited(name)
        },
        renderPanel = ::renderPanel,
        rowSuffix = ::sidebarSuffix,
    )

    /**
     * The search index: every page, every setting on it, and the actions the dialog itself offers
     * on those pages.
     *
     * Built once on the first search and reused. The Plugins page is indexed like any other page:
     * it is a real entry point with a real action now, so finding it by name costs nothing and
     * lands on a page that explains itself.
     */
    private val searchIndex = SettingsSearchIndex(
        pages = navigation.tree.flatMap { nav ->
            when (nav) {
                // An ungrouped page is its own result; a grouped one also answers to its heading.
                is Nav.Page -> listOf(PageRef(nav.label))
                is Nav.Group -> nav.children.map { PageRef(it.label, groupLabel = nav.label) }
            }
        },
        entriesForPage = navigator::searchEntries,
        actionsForPage = ::searchActionsFor,
    )

    /** The search field and its results, swapping with the section tree in the sidebar. */
    private val searchController = SettingsSearchController(
        searchIndex = searchIndex,
        tree = navigator.tree,
        localizationManager = localizationManager,
        onHitActivated = navigator::openHit,
        onSearchStarted = navigator::rememberPageBeforeSearch,
        onSearchEnded = navigator::restorePageAfterSearch,
    )

    private val sidebarPanel: JPanel = searchController.component

    /**
     * The sidebar and the page, separated by the workspace's border seam.
     *
     * The same split pane the workspace docks use: a hairline boundary rather than grip dots, and
     * mirroring by exchanging children so the divider still drags the right way in Arabic. It
     * replaces the matte border that used to be painted on the sidebar's content-facing edge, and
     * it can be dragged — a page with wide content and a reader who wants the sidebar narrow are
     * both reasonable.
     */
    private lateinit var mainSplit: SettingsSplitPane

    private lateinit var okButton: JButton
    private lateinit var applyButton: JButton
    private lateinit var cancelButton: JButton

    /**
     * Asked before Cancel throws staged changes away.
     *
     * A field rather than a call, so the decision can be exercised without a real dialog on
     * screen: the interesting part is that Cancel asks at all, and that it asks only when there is
     * something to lose.
     */
    internal var confirmDiscard: () -> Boolean = ::askDiscardConfirmation

    // Panels that own theme-sensitive borders (refreshed in updateBorders)
    private val buttonBarPanel: JPanel = buildButtonBar()

    // ─────────────────────────────────────────────────────────────────────────

    /** Fetches the stored proxy password so the Network page can show it when opened. */
    private fun loadProxyPassword() {
        val secrets = appSecrets ?: return
        scope.launch {
            val value = secrets.get(NetworkConfig.proxyPasswordKey).orEmpty()
            proxyPassword.initialize(value)
        }
    }

    init {
        loadProxyPassword()
        AppIcons.applyTo(this)
        title = localizationManager.getString("settings_dialog.title")
        layout = BorderLayout()

        // ── Header strip ─────────────────────────────────────────────────────
        contentArea.add(headerStrip, BorderLayout.NORTH)

        val mainPanel = SettingsSplitPane.horizontal(sidebarPanel, contentArea).apply {
            leadingResizeWeight = SIDEBAR_SHARE
            border              = BorderFactory.createEmptyBorder()
            isMirrored          = !componentOrientation.isLeftToRight
        }
        mainSplit = mainPanel

        add(mainPanel, BorderLayout.CENTER)
        add(buttonBarPanel, BorderLayout.SOUTH)
        mainPanel.setLeadingProportion(SIDEBAR_SHARE)

        glassPane = navigator.markerOverlay

        // Apply borders based on current theme, then keep them fresh on theme changes
        updateBorders()
        UIManager.addPropertyChangeListener(themeListener)

        rootPane.registerKeyboardAction(
            { cancelAndClose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = cancelAndClose()

            /**
             * Detaches from [UIManager] on the way out.
             *
             * A fresh dialog is built every time settings are opened, and `UIManager` holds its
             * listeners for the life of the process. Without this, each open pins an entire
             * dialog — every panel and everything they reference — in memory for good, and every
             * later theme change runs the handlers of all the dead ones.
             */
            override fun windowClosed(e: WindowEvent) {
                UIManager.removePropertyChangeListener(themeListener)
            }
        })

        observeState()

        minimumSize = Dimension(UIScale.scale(860), UIScale.scale(580))
        preferredSize = Dimension(UIScale.scale(1020), UIScale.scale(700))
        pack()
        setLocationRelativeTo(owner)

        // Ctrl+F reaches the search field from anywhere in the dialog, which is otherwise only
        // reachable with the mouse: it is chrome in the sidebar, not a row on a page. A page that
        // registers the same shortcut closer to its own controls — the Plugins list filter — wins,
        // because ancestor bindings are consulted before window-wide ones.
        rootPane.registerKeyboardAction(
            {
                searchController.searchField.requestFocusInWindow()
                searchController.searchField.selectAll()
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        watchSidebarCounts()

        // Back to where the reader left off, rather than to the top of the sidebar every time.
        navigator.selectStartPage(rememberedPage())
    }

    /**
     * Keeps the sidebar's counts honest while the dialog is open.
     *
     * Enabling a plugin changes how many services are live and installing one changes how many
     * plugins there are; neither goes through the configuration draft, so nothing else in the
     * dialog would repaint the sidebar when they change.
     */
    private fun watchSidebarCounts() {
        scope.launch {
            pluginManager.plugins.collect { SwingUtilities.invokeLater { navigator.tree.repaint() } }
        }
        scope.launch {
            pluginManager.activeServices.collect { SwingUtilities.invokeLater { navigator.tree.repaint() } }
        }
    }

    /**
     * The count shown after a page's name, where a number says something the name cannot: how many
     * plugins are installed, how many services are live. Pages that are lists of their own already
     * report their own totals, so they carry nothing here.
     */
    private fun sidebarSuffix(pageLabel: String): String {
        val count = when (pageLabel) {
            label("plugins")  -> pluginManager.plugins.value.size
            label("services") -> pluginManager.activeServices.value.size
            else -> return ""
        }
        if (count <= 0) return ""
        return " " + localizationManager.getString("settings_dialog_sidebar.count_format").format(count)
    }

    /**
     * Recomputes anything that depends on layout direction, after it is known.
     *
     * The caller applies an orientation once the dialog is built, so everything decided during
     * construction was decided left-to-right. The sidebar's divider is the visible case: it is a
     * `MatteBorder` on an absolute edge, chosen from the orientation, and without this it stayed
     * on the edge it was given at construction and disappeared against the window frame.
     */
    override fun applyComponentOrientation(orientation: ComponentOrientation) {
        super.applyComponentOrientation(orientation)
        updateBorders()
        // The split mirrors by exchanging its children, so it only needs telling which way round
        // the interface now reads.
        if (::mainSplit.isInitialized) mainSplit.isMirrored = !orientation.isLeftToRight
        // Pages already built were reached by super above; ones built later read the dialog's
        // orientation in their factory.
        revalidate()
        repaint()
    }

    // ── Theme-aware borders ───────────────────────────────────────────────────

    private fun updateBorders() {
        val bc = UIManager.getColor("Component.borderColor") ?: Color.GRAY

        // The line between the sidebar and the page is the split pane's own seam now, so there is
        // nothing to paint on the sidebar's edge here.

        // Divides the search box from the sections it searches, matching the rule under the
        // header strip on the other side of the sidebar so the two line up as one row of chrome.
        searchController.searchField.border = BorderFactory.createCompoundBorder(
            MatteBorder(0, 0, 1, 0, bc),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        )

        headerStrip.border = BorderFactory.createCompoundBorder(
            MatteBorder(0, 0, 1, 0, bc),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)
        )

        buttonBarPanel.border = BorderFactory.createCompoundBorder(
            MatteBorder(1, 0, 0, 0, bc),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )

        revalidate()
        repaint()
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    /**
     * Jumps straight to Services & Presets.
     *
     * The one external navigation entry point this dialog offers, for callers outside it that
     * have a specific reason to send the user to that page -- today, Comparison's unavailable
     * state telling the user where to go set up a second translator.
     */
    fun showServicesPage() = navigator.selectPage(label("services"))

    fun showOcrPage() = navigator.selectPage(label("ocr"))

    /**
     * The actions a page offers to search.
     *
     * Reset is the dialog's own action on a page — it acts on whichever page is showing — so it
     * cannot be registered by the panel, which knows nothing about the dialog. Offering it per
     * page that has settings makes "reset" findable, and finding it on the Popups page means
     * resetting Popups, which is what the header button does too.
     */
    private fun searchActionsFor(pageLabel: String): List<SearchHit.Action> {
        if (navigation.settingsPageOf(pageLabel) == null) return emptyList()
        return listOf(
            SearchHit.Action(
                pageLabel = pageLabel,
                label = localizationManager.getString("settings_dialog.reset_page_button"),
                // The page is already selected by the time a hit is activated, so the header action
                // resets the right one without repeating the navigation here.
                run = ::onResetPage,
            )
        )
    }

    private fun buildPanel(name: String): JPanel = when (name) {
        label("general") ->
            GeneralPanel(
                store = settingsStore,
                localizationManager = localizationManager,
                scope = scope,
                openEditor = { code -> openLanguageEditor(code) },
            )

        label("appearance") ->
            AppearancePanel(settingsStore, themeManager, localizationManager)

        label("services") ->
            ServicesPanel(settingsStore, pluginManager.activeServices, localizationManager, scope)

        label("plugins") ->
            PluginsSettingsPage(localizationManager, iconManager, pluginManager, scope)

        label("ocr") ->
            ocrServiceCatalog?.let { catalog ->
                OcrSettingsPanel(
                    store = settingsStore,
                    localizationManager = localizationManager,
                    catalog = catalog,
                    scope = scope,
                    onOpenHotkeys = { navigator.selectPage(label("hotkeys")) },
                    onOpenServices = ::showServicesPage,
                )
            } ?: JPanel()

        label("hotkeys") ->
            KeyboardPanel(settingsStore, localizationManager, pauseGlobalHotkeys, resumeGlobalHotkeys)

        label("behavior") ->
            TranslationPanel(
                settingsStore,
                localizationManager,
                translationCapabilities = translationCapabilities,
                onOpenServices = ::showServicesPage,
            )

        label("languages") ->
            LanguagesPanel(settingsStore, localizationManager, availableLanguages)

        label("layout") ->
            LayoutPanel(
                settingsStore,
                localizationManager,
                availableTranslatorIds,
                onOpenServicesSettings = ::showServicesPage,
                isTrayAvailable = isTrayAvailable,
            )

        label("popups") ->
            PopupsPanel(settingsStore, localizationManager)

        label("network") ->
            NetworkPanel(
                settingsStore,
                localizationManager,
                proxyPassword = appSecrets?.let {
                    object : NetworkPanel.PasswordAccess {
                        override fun read(): String = proxyPassword.read()
                        override fun write(value: String) {
                            proxyPassword.stage(value)
                        }
                    }
                }
            )

        else -> JPanel()
    }

    /** Renders the store's current state into a freshly shown page. */
    private fun renderPanel(panel: JPanel) {
        if (panel is Renderable<*>) {
            @Suppress("UNCHECKED_CAST")
            (panel as Renderable<SettingsState>).render(settingsStore.state.value)
        }
    }

    // ── Button bar ────────────────────────────────────────────────────────────

    private fun buildButtonBar(): JPanel {
        okButton = JButton(localizationManager.getString("common.ok")).apply {
            mnemonic = KeyEvent.VK_O
            addActionListener { onOk() }
        }
        val cancelButton = JButton(localizationManager.getString("common.cancel")).apply {
            mnemonic = KeyEvent.VK_C
            addActionListener { cancelAndClose() }
        }
        this.cancelButton = cancelButton
        applyButton = JButton(localizationManager.getString("common.apply")).apply {
            mnemonic = KeyEvent.VK_A
            isEnabled = false
            addActionListener { onApply() }
        }
        val resetButton = JButton(
            localizationManager.getString("settings_dialog.reset_defaults_button")
        ).apply {
            mnemonic = KeyEvent.VK_R
            addActionListener { onReset() }
        }

        return JPanel(GridBagLayout()).apply {
            // Border applied by updateBorders()
            val gbc = GridBagConstraints().apply { gridy = 0; insets = Insets(10, 10, 10, 4) }
            gbc.gridx = 0; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.LINE_START
            add(resetButton, gbc)
            gbc.gridx = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.LINE_END
            add(okButton, gbc)
            gbc.gridx = 2; add(cancelButton, gbc)
            gbc.gridx = 3; gbc.insets = Insets(10, 4, 10, 10); add(applyButton, gbc)
        }.also { updateFooterHelp() }
    }

    /**
     * Says what each footer button will do, in the state it will do it in.
     *
     * On a page that owns no settings of its own — Plugins — the footer looks like it governs the
     * page, and it does not: plugin lifecycle and plugin settings take effect where they are
     * clicked. A tooltip that names the draft is what keeps the bar from reading as a lie, and it
     * is also the only description a screen reader gets of a button whose label is one word.
     */
    private fun updateFooterHelp(state: SettingsState = settingsStore.state.value) {
        if (!::okButton.isInitialized || !::applyButton.isInitialized || !::cancelButton.isInitialized) return
        val staged = hasStagedChanges(state)
        okButton.toolTipText = localizationManager.getString("settings_dialog.ok_tooltip")
        applyButton.toolTipText = localizationManager.getString(
            if (staged) "settings_dialog.apply_tooltip" else "settings_dialog.apply_nothing_tooltip"
        )
        cancelButton.toolTipText = localizationManager.getString(
            if (staged) "settings_dialog.cancel_tooltip" else "settings_dialog.cancel_clean_tooltip"
        )
        okButton.accessibleContext.accessibleDescription = okButton.toolTipText
        applyButton.accessibleContext.accessibleDescription = applyButton.toolTipText
        cancelButton.accessibleContext.accessibleDescription = cancelButton.toolTipText
    }

    // ── State observation ─────────────────────────────────────────────────────

    private fun observeState() {
        scope.launch {
            settingsStore.state.collect { state ->
                withContext(Dispatchers.Swing) {
                    val baseTitle = localizationManager.getString("settings_dialog.title")
                    title = if (state.isDirty) "● $baseTitle" else baseTitle

                    updateApplyButton(state)
                    updateFooterHelp(state)

                    navigator.renderCurrentPanel()
                }
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun onOk() = saveAndMaybeClose(close = true)

    private fun updateApplyButton(state: SettingsState = settingsStore.state.value) {
        applyButton.isEnabled = (state.isDirty || proxyPassword.hasPendingChanges()) && !state.isSaving && !savePending
    }

    /** Whether closing without saving would keep something back that the reader typed. */
    private fun hasStagedChanges(state: SettingsState = settingsStore.state.value): Boolean =
        state.isDirty || proxyPassword.hasPendingChanges()

    private fun onApply() = saveAndMaybeClose(close = false)

    private fun saveAndMaybeClose(close: Boolean) {
        if (savePending) return
        if (!settingsStore.state.value.isDirty && !proxyPassword.hasPendingChanges()) {
            if (close) dispose()
            return
        }
        savePending = true
        okButton.isEnabled = false
        okButton.text = localizationManager.getString("settings_dialog.saving")
        updateApplyButton()
        val secret = proxyPassword.snapshot()
        val receipt = settingsStore.submitDraftSave {
            val secrets = appSecrets
            if (secrets != null) {
                proxyPassword.persistSnapshot(secret) { value ->
                    secrets.put(NetworkConfig.proxyPasswordKey, value)
                }?.let { throw it }
            }
        }
        scope.launch {
            try {
                val error = receipt.await().fold(success = { null }, failure = { it.message })
                withContext(Dispatchers.Swing) {
                    savePending = false
                    okButton.isEnabled = true
                    okButton.text = localizationManager.getString("common.ok")
                    if (error != null) {
                        showSaveError(error)
                    } else {
                        // The commit landed: let the owner apply preferences that describe live
                        // window state, so they take effect now rather than after a restart.
                        onSettingsApplied?.invoke(settingsStore.state.value.originalConfiguration)
                        if (close && !settingsStore.state.value.isDirty && !proxyPassword.hasPendingChanges()) {
                            dispose()
                            return@withContext
                        }
                    }
                    updateApplyButton()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.Swing) {
                    savePending = false
                    okButton.isEnabled = true
                    okButton.text = localizationManager.getString("common.ok")
                    showSaveError(error.message ?: localizationManager.getString("settings_dialog.save_failed_title"))
                    updateApplyButton()
                }
            }
        }
    }

    private fun showSaveError(message: String) {
        JOptionPane.showMessageDialog(
            this,
            message,
            localizationManager.getString("settings_dialog.save_failed_title"),
            JOptionPane.ERROR_MESSAGE
        )
    }

    private fun cancelAndClose() {
        // The only warning that edits would be lost is a dot in the title, which is nothing beside
        // a Cancel button one pixel from OK. Esc and the window's close box take the same path:
        // all three mean "leave without saving", so all three ask the same question.
        if (hasStagedChanges() && !confirmDiscard()) return
        settingsStore.dispatch(SettingsIntent.CancelChanges)
        proxyPassword.discard()
        dispose()
    }

    /**
     * Asks whether to throw the staged changes away, and answers for the safer choice when the
     * window is dismissed without a button being pressed.
     */
    private fun askDiscardConfirmation(): Boolean {
        val choice = JOptionPane.showOptionDialog(
            this,
            localizationManager.getString("settings_dialog.discard_message"),
            localizationManager.getString("settings_dialog.discard_title"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            arrayOf(
                localizationManager.getString("settings_dialog.discard_button"),
                localizationManager.getString("settings_dialog.keep_editing_button"),
            ),
            localizationManager.getString("settings_dialog.keep_editing_button"),
        )
        return choice == 0
    }

    private fun onReset() {
        val result = JOptionPane.showConfirmDialog(
            this,
            localizationManager.getString("settings_dialog.reset_confirmation_message"),
            localizationManager.getString("settings_dialog.reset_confirmation_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (result == JOptionPane.YES_OPTION) settingsStore.dispatch(SettingsIntent.ResetPreferences)
    }

    /** Keeps the header action honest about what the shown page can reset, and why. */
    private fun updateResetPageAction(pageName: String) {
        currentSettingsPage = navigation.settingsPageOf(pageName)
        val resettable = currentSettingsPage != null
        resetPageButton.isEnabled = resettable
        // A disabled button with no explanation is a dead end. On a page that owns no settings the
        // tooltip says so instead of leaving the reader to guess whether the button is broken.
        val explanation = localizationManager.getString(
            if (resettable) "settings_dialog.reset_page_tooltip"
            else "settings_dialog.reset_page_unavailable_tooltip"
        )
        resetPageButton.toolTipText = explanation
        resetPageButton.accessibleContext.accessibleDescription = explanation
    }

    private fun onResetPage() {
        val page = currentSettingsPage ?: return
        val result = JOptionPane.showConfirmDialog(
            this,
            localizationManager.getString("settings_dialog.reset_page_confirmation_message"),
            localizationManager.getString("settings_dialog.reset_page_confirmation_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (result == JOptionPane.YES_OPTION) settingsStore.dispatch(SettingsIntent.ResetPage(page))
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    /**
     * Opens the language editor over this dialog and blocks until it closes.
     *
     * Modal on purpose: it writes the files this dialog is reading from, so letting both be used
     * at once would show settings that no longer match what is on disk.
     */
    private fun openLanguageEditor(languageCode: String?) {
        LanguageEditorDialog(
            owner = this,
            localizationManager = localizationManager,
            scope = scope,
            translateString = translateString,
            initialCode = languageCode
        ).isVisible = true
    }

    private companion object {
        /** The sidebar's share of the dialog's width, before the reader drags the divider. */
        const val SIDEBAR_SHARE = 0.2
    }
}
