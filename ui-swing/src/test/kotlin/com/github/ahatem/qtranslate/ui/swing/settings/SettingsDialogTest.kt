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
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.settings.panels.GeneralPanel
import com.github.ahatem.qtranslate.ui.swing.settings.panels.NetworkPanel
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
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
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
        assertTrue(hits.none { it.pageLabel == pluginsLabel }, "Plugins is deliberately not indexed")
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

    // ── Helpers ────────────────────────────────────────────────────────────────

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
