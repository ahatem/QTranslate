package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.plugin.PluginManifest
import com.github.ahatem.qtranslate.core.plugin.PluginState
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
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
import com.github.ahatem.qtranslate.ui.swing.settings.panels.PluginsSettingsPage
import com.github.ahatem.qtranslate.ui.swing.settings.panels.PluginsPanel
import com.github.ahatem.qtranslate.ui.swing.settings.panels.UnscrolledSettingsPage
import com.github.ahatem.qtranslate.ui.swing.settings.panels.ServicesPanel
import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.testing.SilentLogger
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode

/** The dialog shell as a user sees it: navigation, search, Apply and Cancel. Needs a windowing toolkit. */
class SettingsDialogTest {

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

    private fun newDialog(
        rememberedPage: () -> String? = { null },
        onPageVisited: (String) -> Unit = {},
    ): SettingsDialog {
        val frame = owner
        return onEdt {
            SettingsDialog(
                owner = frame,
                settingsStore = store,
                pluginManager = pluginManager,
                iconManager = IconManager(pluginManager),
                themeManager = ThemeManager(directory, SilentLogger),
                localizationManager = localizer,
                rememberedPage = rememberedPage,
                onPageVisited = onPageVisited,
            ).also { dialogs += it }
        }
    }

    @AfterTest
    fun tearDown() {
        onEdt { dialogs.forEach { it.dispose() } }
        dialogs.clear()
        storeScope.cancel()
    }

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
    fun `page reset is available on a page of settings and explains itself on one without`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val pluginsLabel = localizer.getString("settings_dialog_sidebar.plugins")
        val resetPage = footerButton(dialog, "settings_dialog.reset_page_button")

        assertTrue(onEdt { resetPage.isEnabled }, "General owns settings, so it can be reset")
        assertEquals(
            localizer.getString("settings_dialog.reset_page_tooltip"),
            onEdt { resetPage.toolTipText },
        )

        onEdt {
            val tree = descendants<JTree>(dialog.contentPane).single()
            tree.setSelectionRow(sidebarRow(tree, pluginsLabel))
        }
        onEdt {}

        assertFalse(onEdt { resetPage.isEnabled }, "the Plugins page has no settings to restore")
        assertEquals(
            localizer.getString("settings_dialog.reset_page_unavailable_tooltip"),
            onEdt { resetPage.toolTipText },
        )
    }

    @Test
    fun `applying settings hands the committed configuration to the owner`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val applied = mutableListOf<Configuration>()
        val frame = owner
        val dialog = onEdt {
            SettingsDialog(
                owner = frame,
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

    @Test
    fun `cancel asks before throwing away what was staged`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val cancel = footerButton(dialog, "common.cancel")
        val apply = footerButton(dialog, "common.apply")
        var asked = 0

        onEdt { store.dispatch(SettingsIntent.TransformDraft { it.copy(isHistoryEnabled = false) }) }
        waitUntil { onEdt { apply.isEnabled } }

        onEdt { dialog.confirmDiscard = { asked += 1; false } }
        onEdt { cancel.doClick() }

        assertEquals(1, asked, "a staged change is worth a question before it is lost")
        assertTrue(onEdt { dialog.isDisplayable }, "declining the question leaves the dialog open")
        assertTrue(store.state.value.isDirty, "and leaves the draft exactly where it was")

        onEdt { dialog.confirmDiscard = { asked += 1; true } }
        onEdt { cancel.doClick() }

        assertEquals(2, asked, "and it asks again the next time Cancel is pressed")
        assertFalse(store.state.value.isDirty, "agreeing throws the draft away")
        assertFalse(onEdt { dialog.isDisplayable }, "and closes the dialog")
    }

    @Test
    fun `cancel does not ask when nothing was staged`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val cancel = footerButton(dialog, "common.cancel")
        var asked = false

        onEdt { dialog.confirmDiscard = { asked = true; true } }
        onEdt { cancel.doClick() }

        assertFalse(asked, "there is nothing to lose, so there is nothing to ask about")
        assertFalse(onEdt { dialog.isDisplayable }, "Cancel closes a clean dialog without ceremony")
    }

    @Test
    fun `Ctrl+F reaches the search field from anywhere in the dialog`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val dialog = newDialog()
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK)

        val binding = assertNotNull(
            onEdt { dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke) },
            "Ctrl+F is bound at window scope, so no control has to hold focus first",
        )
        val action = assertNotNull(
            onEdt { dialog.rootPane.actionMap.get(binding) },
            "'$binding' resolves to a real action rather than a dead binding",
        )

        val field = onEdt { descendants<JTextField>(dialog.contentPane).single() }
        onEdt { field.text = "proxy" }

        onEdt { action.actionPerformed(ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "")) }

        assertEquals(
            "proxy",
            onEdt { field.selectedText },
            "the shortcut selects the query, so the reader can type over it",
        )
    }

    @Test
    fun `the dialog opens on the page the caller remembered`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val pluginsLabel = localizer.getString("settings_dialog_sidebar.plugins")
        val visited = mutableListOf<String>()
        val dialog = newDialog(rememberedPage = { pluginsLabel }, onPageVisited = { visited += it })

        assertEquals(
            listOf(pluginsLabel),
            onEdt { visited.toList() },
            "the caller is told which page is showing, so it can remember it for the next open",
        )
        onEdt {
            assertEquals(
                1,
                descendants<PluginsSettingsPage>(dialog.contentPane).size,
                "the remembered page is mounted, not merely selected in the sidebar",
            )
            val tree = descendants<JTree>(dialog.contentPane).single()
            assertEquals(
                pluginsLabel,
                (tree.selectionPath.lastPathComponent as? DefaultMutableTreeNode)?.userObject,
                "and the sidebar shows it as the selected row",
            )
        }
    }

    @Test
    fun `the sidebar counts plugins and services beside their page names`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        seedSidebarCounts(plugins = 3, services = 2)
        val dialog = newDialog()
        val pluginsLabel = localizer.getString("settings_dialog_sidebar.plugins")
        val servicesLabel = localizer.getString("settings_dialog_sidebar.services")
        val tree = onEdt { descendants<JTree>(dialog.contentPane).single() }

        assertEquals(
            countedRow(pluginsLabel, 3),
            onEdt { renderedRowText(tree, sidebarRow(tree, pluginsLabel)) },
            "the Plugins row says how many are installed",
        )
        assertEquals(
            countedRow(servicesLabel, 2),
            onEdt { renderedRowText(tree, sidebarRow(tree, servicesLabel)) },
            "and the Services row says how many are live",
        )
        assertEquals(
            pluginsLabel,
            onEdt {
                (tree.getPathForRow(sidebarRow(tree, pluginsLabel)).lastPathComponent
                    as? DefaultMutableTreeNode)?.userObject
            },
            "the count is decoration: the row still means the page, so search and navigation keep matching the name",
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** The button labelled by the localization [key]. */
    private fun footerButton(dialog: SettingsDialog, key: String): JButton =
        onEdt { descendants<JButton>(dialog.contentPane).single { it.text == localizer.getString(key) } }

    /** The sidebar row that stands for the page labelled [pageLabel]. */
    private fun sidebarRow(tree: JTree, pageLabel: String): Int =
        (0 until tree.rowCount).first { row ->
            (tree.getPathForRow(row).lastPathComponent as? DefaultMutableTreeNode)?.userObject == pageLabel
        }

    /**
     * What the sidebar actually draws for [row].
     *
     * The page label lives on the tree node; the text on screen is the renderer's business, and
     * asking the renderer is the only way to see the decoration it adds to that label.
     */
    private fun renderedRowText(tree: JTree, row: Int): String =
        (tree.cellRenderer.getTreeCellRendererComponent(
            tree,
            tree.getPathForRow(row).lastPathComponent,
            false,
            true,
            false,
            row,
            false,
        ) as JLabel).text

    /** A sidebar row's text when [count] items sit behind the page: the name, then the total. */
    private fun countedRow(pageLabel: String, count: Int): String =
        "$pageLabel " + localizer.getString("settings_dialog_sidebar.count_format").format(count)

    /**
     * Puts plugin and service counts in front of the sidebar.
     *
     * Both counts come from state the plugin manager only fills by discovering real plugin JARs,
     * which a rendering test has none of; the flows are seeded instead. What is under test is the
     * sidebar's decoration, and it reads the same two values either way.
     */
    private fun seedSidebarCounts(plugins: Int, services: Int) {
        val plugin = PluginState(
            manifest = PluginManifest(
                id = "test.plugin",
                name = "Test Plugin",
                version = "1.0.0",
                author = "Test",
                description = "Synthetic plugin, for the sidebar's counts",
                minApiVersion = "1.0.0",
            ),
            status = PluginStatus.ENABLED,
            jarPath = "plugin.jar",
        )
        seedFlow<List<PluginState>>("_plugins", List(plugins) { plugin })
        seedFlow("_activeServices", buildMap<String, Service> {
            repeat(services) { index -> put("service-$index", ServiceStub) }
        })
    }

    private fun <T> seedFlow(fieldName: String, value: T) {
        val field = try {
            PluginManager::class.java.getDeclaredField(fieldName)
        } catch (error: NoSuchFieldException) {
            throw AssertionError("PluginManager has no '$fieldName' flow for the sidebar counts to read", error)
        }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(pluginManager) as MutableStateFlow<T>).value = value
    }

    /** Stands in for a plugin's service; only its presence is counted here. */
    private object ServiceStub : Service {
        override val key = "service-stub"
        override val name = "Service Stub"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
    }

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
