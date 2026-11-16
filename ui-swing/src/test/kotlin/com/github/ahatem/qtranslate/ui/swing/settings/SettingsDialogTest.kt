package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.settings.panels.GeneralPanel
import com.github.ahatem.qtranslate.ui.swing.settings.panels.NetworkPanel
import com.github.ahatem.qtranslate.ui.swing.settings.panels.PluginsPanel
import com.github.ahatem.qtranslate.ui.swing.settings.panels.UnscrolledSettingsPage
import com.github.ahatem.qtranslate.ui.swing.settings.panels.ServicesPanel
import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Regression pins for the dialog shell after search/navigation were extracted into collaborators:
 * the wiring (source-level, runs everywhere) and the behavior a user sees (needs a toolkit).
 */
class SettingsDialogTest {

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        return result!!
    }

    private object SilentLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val directory by lazy { Files.createTempDirectory("qtranslate-settings-dialog").toFile() }
    private val localizer by lazy {
        LocalizationManager(directory, LanguageTomlParser(), SilentLogger)
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy {
        val repository = SettingsRepository(directory, json, SilentLogger)
        runBlocking { repository.updateConfiguration(Configuration.DEFAULT) }
        SettingsStore(repository, SilentLogger, storeScope, Configuration.DEFAULT)
    }
    private val pluginManager by lazy {
        PluginManager(
            appDataDirectory = directory,
            settingsRepository = SettingsRepository(directory, json, SilentLogger),
            pluginFingerprintRepository = PluginFingerprintRepository(directory, json),
            pluginKeyValueStore = PluginKeyValueStore(directory),
            loggerFactory = object : LoggerFactory {
                override fun getLogger(name: String): Logger = SilentLogger
            },
            notificationBus = NotificationBus(),
            networkConfig = { NetworkConfig() },
        )
    }
    private val owner by lazy { onEdt { JFrame() } }
    private val dialogs = mutableListOf<SettingsDialog>()

    private fun newDialog(): SettingsDialog = onEdt {
        SettingsDialog(
            owner = owner,
            settingsStore = store,
            pluginManager = pluginManager,
            iconManager = IconManager(pluginManager),
            themeManager = ThemeManager(directory, SilentLogger),
            localizationManager = localizer,
        ).also { dialogs += it }
    }

    @AfterTest
    fun tearDown() {
        onEdt { dialogs.forEach { it.dispose() } }
        dialogs.clear()
        storeScope.cancel()
    }

    // ── Wiring (headless, source-level) ────────────────────────────────────────

    @Test
    fun `the dialog orchestrates the collaborators instead of owning search and navigation`() {
        val source = File(
            "src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/settings/SettingsDialog.kt"
        ).readText()

        assertTrue(source.contains("SettingsNavigation(::label)"), "the sidebar structure is delegated")
        assertTrue(source.contains("SettingsNavigationController("), "page selection is delegated")
        assertTrue(source.contains("panelFactory = ::buildPanel"), "the dialog still owns panel composition")
        assertTrue(source.contains("SettingsSearchIndex("), "the search index is delegated")
        assertTrue(source.contains("SettingsSearchController("), "query handling is delegated")
        assertTrue(source.contains("tree = navigator.tree"), "the results swap with the navigation tree")
        assertTrue(source.contains("onHitActivated = navigator::openHit"), "hit activation routes to navigation")
        assertTrue(source.contains("entriesForPage = navigator::searchEntries"), "indexing reads the navigation panel cache")
        assertTrue(source.contains("glassPane = navigator.markerOverlay"), "the flash overlay stays the dialog's glass pane")
        assertTrue(source.contains("navigator.selectFirstPage()"), "the initial page selection is delegated")
        assertTrue(source.contains("navigator.selectPage(label(\"services\"))"), "external page selection is delegated")
        assertTrue(source.contains("navigator.renderCurrentPanel()"), "state re-render is delegated")

        assertFalse(source.contains("DefaultMutableTreeNode"), "tree construction moved out")
        assertFalse(source.contains("CardLayout"), "card switching moved out")
        assertFalse(source.contains("MarkerOverlay"), "the flash overlay moved out")
        assertFalse(source.contains("FlatSearchIcon"), "search presentation moved out")
    }

    @Test
    fun `reset is offered per page and for the whole dialog, with different intents`() {
        val source = File(
            "src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/settings/SettingsDialog.kt"
        ).readText()

        assertTrue(source.contains("resetPageButton"), "the header owns the page reset")
        assertTrue(source.contains("SettingsIntent.ResetPage(page)"), "a page reset names its page")
        assertTrue(source.contains("SettingsIntent.ResetPreferences"), "the bar resets ordinary preferences")
        assertFalse(source.contains("ResetToDefaults"), "the unscoped reset is gone")
    }

    @Test
    fun `the page reset is enabled where the page owns settings`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val resetPage = onEdt {
            descendants<JButton>(dialog.contentPane)
                .single { it.text == localizer.getString("settings_dialog.reset_page_button") }
        }

        assertTrue(onEdt { resetPage.isEnabled }, "General owns settings, so it can be reset")
    }

    @Test
    fun `the page reset is disabled where the page owns no settings`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val pluginsLabel = localizer.getString("settings_dialog_sidebar.plugins")
        val resetPage = onEdt {
            descendants<JButton>(dialog.contentPane)
                .single { it.text == localizer.getString("settings_dialog.reset_page_button") }
        }

        onEdt {
            val tree = descendants<JTree>(dialog.contentPane).single()
            val row = (0 until tree.rowCount).first { index ->
                (tree.getPathForRow(index).lastPathComponent as? DefaultMutableTreeNode)?.userObject == pluginsLabel
            }
            tree.setSelectionRow(row)
        }
        onEdt {}

        assertFalse(
            onEdt { resetPage.isEnabled },
            "the Plugins page is a way in, not a page of settings to restore",
        )
    }

    // ── Behavior (needs a toolkit) ─────────────────────────────────────────────

    @Test
    fun `opens on the general page with a clean button bar`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val generalLabel = localizer.getString("settings_dialog_sidebar.general")

        onEdt {
            assertTrue(currentPagePanel(dialog) is GeneralPanel, "the first page is shown at open")
            assertTrue(
                descendants<JLabel>(dialog.contentPane).any { it.text == generalLabel },
                "the header names the page",
            )
            val tree = descendants<JTree>(dialog.contentPane).single()
            assertEquals(0, tree.getRowForPath(tree.selectionPath), "the first sidebar row is selected")
            assertEquals(
                generalLabel,
                (tree.selectionPath.lastPathComponent as? DefaultMutableTreeNode)?.userObject,
            )
            val apply = descendants<JButton>(dialog.contentPane)
                .single { it.text == localizer.getString("common.apply") }
            assertFalse(apply.isEnabled, "nothing is dirty yet, so Apply stays off")
        }
    }

    @Test
    fun `showServicesPage jumps to the services page`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val servicesLabel = localizer.getString("settings_dialog_sidebar.services")

        onEdt { dialog.showServicesPage() }

        onEdt {
            assertTrue(currentPagePanel(dialog) is ServicesPanel, "the external entry point shows its page")
            assertTrue(
                descendants<JLabel>(dialog.contentPane).any { it.text == servicesLabel },
                "the header follows the page",
            )
            val tree = descendants<JTree>(dialog.contentPane).single()
            assertEquals(
                servicesLabel,
                (tree.selectionPath.lastPathComponent as? DefaultMutableTreeNode)?.userObject,
                "the sidebar selection moved with it",
            )
        }
    }

    @Test
    fun `activating a search hit opens its page and flashes the row`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val pluginsLabel = localizer.getString("settings_dialog_sidebar.plugins")
        val networkLabel = localizer.getString("settings_dialog_sidebar.network")

        // Found before the search builds the pages: afterwards the panels hold text fields too.
        val field = onEdt { descendants<JTextField>(dialog.contentPane).single() }

        onEdt { field.text = "proxy" }

        val list = onEdt {
            descendants<JList<*>>(dialog.contentPane)
                .filter { it.model.size > 0 && it.model.getElementAt(0) is SearchHit }
                .single()
        }
        val hits = onEdt { (0 until list.model.size).map { index -> list.model.getElementAt(index) as SearchHit } }

        assertTrue(hits.isNotEmpty(), "the proxy query finds hits")
        val networkHit = hits.first { it.pageLabel == networkLabel }

        onEdt { list.selectedIndex = (0 until list.model.size).first { list.model.getElementAt(it) == networkHit } }

        // openHit defers the scroll and the flash to the event queue; flush both rounds.
        onEdt {}
        onEdt {}

        onEdt {
            assertTrue(currentPagePanel(dialog) is NetworkPanel, "the hit's page is shown")
            assertTrue(dialog.glassPane.isVisible, "the flash marker is up on the glass pane")
        }
    }

    @Test
    fun `the plugins page is the manager, and says what Apply does not govern`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val pluginsLabel = localizer.getString("settings_dialog_sidebar.plugins")

        val field = onEdt { descendants<JTextField>(dialog.contentPane).single() }
        onEdt { field.text = "plugin" }
        assertTrue(
            searchHits(dialog).any { it.pageLabel == pluginsLabel },
            "the plugin manager is reachable by name, not only by browsing the sidebar",
        )

        onEdt {
            val tree = descendants<JTree>(dialog.contentPane).single()
            val row = (0 until tree.rowCount).first { index ->
                (tree.getPathForRow(index).lastPathComponent as? DefaultMutableTreeNode)?.userObject == pluginsLabel
            }
            tree.setSelectionRow(row)
        }
        onEdt {}

        onEdt {
            assertTrue(
                descendants<PluginsPanel>(dialog.contentPane).size == 1,
                "the page is the manager itself, not a signpost to one in another window",
            )
            assertTrue(
                descendants<JLabel>(dialog.contentPane)
                    .any { it.text == localizer.getString("settings_plugins.manager_hint") },
                "and it states, on the page, that its changes are immediate and unaffected by Apply or Cancel",
            )
            assertTrue(
                descendants<JScrollPane>(dialog.contentPane).none { it.viewport.view is UnscrolledSettingsPage },
                "mounted directly, so the manager's own scrolling is not nested inside another",
            )
        }
    }

    @Test
    fun `a page name is a navigation result of its own`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val popupsLabel = localizer.getString("settings_dialog_sidebar.popups")

        val field = onEdt { descendants<JTextField>(dialog.contentPane).single() }
        onEdt { field.text = "popups" }

        val hits = searchHits(dialog)
        val page = hits.firstOrNull() as? SearchHit.Page

        assertEquals(popupsLabel, page?.pageLabel, "typing a page name offers the page first")
        assertTrue(
            hits.none { it is SearchHit.Page && it.pageLabel != popupsLabel },
            "and nothing else claims to be a page for that query",
        )
    }

    @Test
    fun `reset is findable from search, on the pages that own settings`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val generalLabel = localizer.getString("settings_dialog_sidebar.general")
        val pluginsLabel = localizer.getString("settings_dialog_sidebar.plugins")

        val field = onEdt { descendants<JTextField>(dialog.contentPane).single() }
        onEdt { field.text = localizer.getString("settings_dialog.reset_page_button") }

        val actions = searchHits(dialog).filterIsInstance<SearchHit.Action>()

        assertTrue(actions.any { it.pageLabel == generalLabel }, "General can be reset, so search offers it")
        assertTrue(
            actions.none { it.pageLabel == pluginsLabel },
            "the page with no settings of its own offers no reset",
        )
    }

    @Test
    fun `the header reset says what it resets, and why it cannot on a page without settings`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val pluginsLabel = localizer.getString("settings_dialog_sidebar.plugins")
        val resetPage = onEdt {
            descendants<JButton>(dialog.contentPane)
                .single { it.text == localizer.getString("settings_dialog.reset_page_button") }
        }

        assertEquals(
            localizer.getString("settings_dialog.reset_page_tooltip"),
            onEdt { resetPage.toolTipText },
            "on a page that owns settings, the action explains its scope before it is clicked",
        )

        onEdt {
            val tree = descendants<JTree>(dialog.contentPane).single()
            val row = (0 until tree.rowCount).first { index ->
                (tree.getPathForRow(index).lastPathComponent as? DefaultMutableTreeNode)?.userObject == pluginsLabel
            }
            tree.setSelectionRow(row)
        }
        onEdt {}

        assertEquals(
            localizer.getString("settings_dialog.reset_page_unavailable_tooltip"),
            onEdt { resetPage.toolTipText },
            "and on a page without settings it says so instead of leaving a dead button",
        )
    }

    @Test
    fun `cancel closes the dialog`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()

        onEdt {
            val cancel = descendants<JButton>(dialog.contentPane)
                .single { it.text == localizer.getString("common.cancel") }
            cancel.doClick()
        }

        assertFalse(onEdt { dialog.isDisplayable }, "cancel disposes the dialog")
    }

    @Test
    fun `applying settings hands the committed configuration to the owner`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val applied = mutableListOf<Configuration>()
        val dialog = onEdt {
            SettingsDialog(
                owner = owner,
                settingsStore = store,
                pluginManager = pluginManager,
                iconManager = IconManager(pluginManager),
                themeManager = ThemeManager(directory, SilentLogger),
                localizationManager = localizer,
                onSettingsApplied = { committed -> applied += committed },
            ).also { dialogs += it }
        }
        val apply = onEdt {
            descendants<JButton>(dialog.contentPane).single { it.text == localizer.getString("common.apply") }
        }

        onEdt { store.dispatch(SettingsIntent.TransformDraft { it.copy(isHistoryEnabled = false) }) }
        // The button reflects store state through a coroutine, so wait for it rather than assume.
        waitUntil { onEdt { apply.isEnabled } }
        onEdt { apply.doClick() }
        waitUntil { applied.isNotEmpty() }

        assertFalse(
            applied.single().isHistoryEnabled,
            "the owner receives the configuration that was actually saved",
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Every row currently listed by the sidebar's search results. */
    private fun searchHits(dialog: SettingsDialog): List<SearchHit> {
        val list = onEdt {
            descendants<JList<*>>(dialog.contentPane)
                .filter { it.model.size > 0 && it.model.getElementAt(0) is SearchHit }
                .single()
        }
        return onEdt { (0 until list.model.size).map { index -> list.model.getElementAt(index) as SearchHit } }
    }

    /**
     * Spins the event queue until [condition] holds.
     *
     * The dialog's button state and the save receipt both arrive from coroutines, so a test that
     * clicked straight away would race them; a fixed sleep would be a guess.
     */
    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${timeoutMillis}ms" }
            onEdt {}
            Thread.sleep(10)
        }
    }

    /** The page currently in the content area; cached-but-hidden pages are not descendants. */
    private fun currentPagePanel(dialog: SettingsDialog): SettingsPanel =
        descendants<JScrollPane>(dialog.contentPane)
            .mapNotNull { it.viewport.view as? SettingsPanel }
            .single()

    private inline fun <reified T : Component> descendants(root: Container): List<T> {
        val result = mutableListOf<T>()
        val queue = ArrayDeque<Component>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (component is T) result += component
            if (component is Container) queue.addAll(component.components)
        }
        return result
    }
}
