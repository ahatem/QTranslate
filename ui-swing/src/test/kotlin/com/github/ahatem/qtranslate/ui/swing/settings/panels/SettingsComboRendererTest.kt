package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DisplayValueRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real Settings pages, checked through their combo boxes: a popup row must take the list's
 * selection colors, which is what makes mouse hover and keyboard navigation visible.
 */
class SettingsComboRendererTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-combo-test").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun newStore(configuration: Configuration = Configuration.DEFAULT): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-combo-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(configuration)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, configuration)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun combos(root: Component): List<JComboBox<*>> =
        (if (root is JComboBox<*>) listOf(root) else emptyList()) +
            if (root is Container) root.components.flatMap(::combos) else emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun JComboBox<*>.rowAt(index: Int, selected: Boolean): JLabel {
        val list = JList<Any?>().apply {
            selectionBackground = Color(10, 90, 200)
            selectionForeground = Color(255, 255, 255)
            background = Color(250, 250, 250)
            foreground = Color(20, 20, 20)
        }
        val renderer = renderer as ListCellRenderer<Any?>
        return renderer.getListCellRendererComponent(list, getItemAt(index), index, selected, false) as JLabel
    }

    private fun assertNativeRows(panel: SettingsPanel, expectedCombos: Int) {
        val found = onEdt { combos(panel) }
        assertTrue(found.size >= expectedCombos, "expected at least $expectedCombos combo boxes, found ${found.size}")
        assertNativeRowsOf(found)
    }

    /** The row-rendering assertions, for a picker list the caller has already chosen. */
    private fun assertNativeRowsOf(found: List<JComboBox<*>>) {
        found.forEach { combo ->
            onEdt {
                assertTrue(combo.renderer is DisplayValueRenderer<*>, "combo must use the shared renderer")
                assertTrue(combo.itemCount > 0)
                val row = combo.rowAt(0, selected = true)
                assertEquals(Color(10, 90, 200), row.background, "hovered row must take the list selection background")
                assertEquals(Color(255, 255, 255), row.foreground)
                assertTrue(row.text.isNotEmpty())
                val plain = combo.rowAt(0, selected = false)
                assertEquals(Color(250, 250, 250), plain.background)
            }
        }
    }

    @Test
    fun `general page combos preserve native selection`() {
        val panel = onEdt {
            GeneralPanel(
                newStore(),
                localizer,
                CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it },
            )
        }
        // Four now: the interface-language picker moved here from Appearance, and it renders
        // translated language names through a renderer of its own rather than the shared one, so
        // the shared-renderer check covers the selection pickers and the title-bar buttons that
        // came over from the Layout page.
        val shared = onEdt { combos(panel).filter { it.renderer is DisplayValueRenderer<*> } }
        assertEquals(4, shared.size, "the selection and title-bar pickers use the shared renderer")
        assertNativeRowsOf(shared)
    }

    @Test
    fun `translation page combos preserve native selection`() {
        // Was 4 before the dictionary auto-lookup source combo (Off/Translated/Source) was
        // replaced with an explicit checkbox plus a radio group -- the on/off state no longer
        // hides inside a combo box value.
        val panel = onEdt { TranslationPanel(newStore(), localizer) }
        assertNativeRows(panel, expectedCombos = 3)
    }
}
