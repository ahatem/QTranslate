package com.github.ahatem.qtranslate.ui.swing.settings

import com.formdev.flatlaf.util.UIScale
import com.github.michaelbull.result.fold
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.settings.panels.*
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.AppIcons
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import com.github.ahatem.qtranslate.core.plugin.storage.AppSecretStore
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.*
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

    private val headerStrip = JPanel(BorderLayout(12, 0)).apply {
        add(panelTitle, BorderLayout.LINE_START)
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
        onPageShown = { name -> panelTitle.text = name },
        renderPanel = ::renderPanel,
    )

    /**
     * Every setting on every page, indexed from panel search metadata.
     *
     * Built once on the first search and reused; Plugins is excluded deliberately — it is a
     * manager for a list that changes at runtime, not a page of settings, and it is the one
     * panel whose construction does real work.
     */
    private val searchIndex = SettingsSearchIndex(
        pageLabels = navigation.pages.map { it.label },
        excludedPageLabels = setOf(label("plugins")),
        entriesForPage = navigator::searchEntries,
    )

    /** The search field and its results, swapping with the section tree in the sidebar. */
    private val searchController = SettingsSearchController(
        searchIndex = searchIndex,
        tree = navigator.tree,
        localizationManager = localizationManager,
        onHitActivated = navigator::openHit,
    )

    private val sidebarPanel: JPanel = searchController.component

    private lateinit var okButton: JButton
    private lateinit var applyButton: JButton

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

        val mainPanel = JPanel(BorderLayout()).apply {
            add(sidebarPanel, BorderLayout.LINE_START)
            add(contentArea, BorderLayout.CENTER)
        }

        add(mainPanel, BorderLayout.CENTER)
        add(buttonBarPanel, BorderLayout.SOUTH)

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

        navigator.selectFirstPage()
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
        // Pages already built were reached by super above; ones built later read the dialog's
        // orientation in their factory.
        revalidate()
        repaint()
    }

    // ── Theme-aware borders ───────────────────────────────────────────────────

    private fun updateBorders() {
        val bc = UIManager.getColor("Component.borderColor") ?: Color.GRAY

        // The divider goes on whichever edge faces the content, which swaps with the layout
        // direction: the sidebar sits on the right in a right-to-left interface, so a border
        // fixed to its right edge ends up on the outside of the window against nothing.
        // MatteBorder takes absolute sides and knows nothing about orientation, so the side is
        // chosen here.
        sidebarPanel.border = MatteBorder(
            0,
            if (componentOrientation.isLeftToRight) 0 else 1,
            0,
            if (componentOrientation.isLeftToRight) 1 else 0,
            bc
        )

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

    private fun buildPanel(name: String): JPanel = when (name) {
        label("general") ->
            GeneralPanel(settingsStore, localizationManager)

        label("appearance") ->
            AppearancePanel(
                settingsStore, themeManager, localizationManager, scope,
                openEditor = { code -> openLanguageEditor(code) }
            )

        label("services") ->
            ServicesPanel(settingsStore, pluginManager.activeServices, localizationManager, scope)

        label("plugins") ->
            PluginsPanel(iconManager, pluginManager, localizationManager, scope)

        label("hotkeys") ->
            KeyboardPanel(settingsStore, localizationManager, pauseGlobalHotkeys, resumeGlobalHotkeys)

        label("behavior") ->
            TranslationPanel(settingsStore, localizationManager)

        label("languages") ->
            LanguagesPanel(settingsStore, localizationManager, availableLanguages)

        label("layout") ->
            LayoutPanel(settingsStore, localizationManager, availableTranslatorIds, onOpenServicesSettings = ::showServicesPage)

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
        }
    }

    // ── State observation ─────────────────────────────────────────────────────

    private fun observeState() {
        scope.launch {
            settingsStore.state.collect { state ->
                withContext(Dispatchers.Swing) {
                    val baseTitle = localizationManager.getString("settings_dialog.title")
                    title = if (state.isDirty) "● $baseTitle" else baseTitle

                    updateApplyButton(state)

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
                    if (error != null) showSaveError(error)
                    else if (close && !settingsStore.state.value.isDirty && !proxyPassword.hasPendingChanges()) {
                        dispose()
                        return@withContext
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
        settingsStore.dispatch(SettingsIntent.CancelChanges)
        proxyPassword.discard()
        dispose()
    }

    private fun onReset() {
        val result = JOptionPane.showConfirmDialog(
            this,
            localizationManager.getString("settings_dialog.reset_confirmation_message"),
            localizationManager.getString("settings_dialog.reset_confirmation_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (result == JOptionPane.YES_OPTION) settingsStore.dispatch(SettingsIntent.ResetToDefaults)
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
}
