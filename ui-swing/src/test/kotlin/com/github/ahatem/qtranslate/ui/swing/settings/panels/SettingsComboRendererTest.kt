package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
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
import kotlin.test.assertNull
import kotlin.test.assertFalse
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

    private fun newStore(config: Configuration = Configuration.DEFAULT): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-combo-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, config)
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

    // The layout entries are a private data class; their toString carries the preset id.
    private fun JComboBox<*>.indexOfLayoutId(id: String): Int =
        (0 until itemCount).indexOfFirst { getItemAt(it).toString().startsWith("LayoutInfo(id=$id,") }

    private fun JComboBox<*>.hasLayoutId(id: String) = indexOfLayoutId(id) >= 0

    private fun assertNativeRows(panel: SettingsPanel, expectedCombos: Int) {
        val found = onEdt { combos(panel) }
        assertTrue(found.size >= expectedCombos, "expected at least $expectedCombos combo boxes, found ${found.size}")
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
        val panel = onEdt { GeneralPanel(newStore(), localizer) }
        assertNativeRows(panel, expectedCombos = 2)
    }

    @Test
    fun `translation page combos preserve native selection`() {
        val panel = onEdt { TranslationPanel(newStore(), localizer) }
        assertNativeRows(panel, expectedCombos = 4)
    }

    @Test
    fun `layout page combos preserve native selection`() {
        val panel = onEdt { LayoutPanel(newStore(), localizer) }
        assertNativeRows(panel, expectedCombos = 4)
    }

    private fun JComboBox<*>.layoutIds(): List<String> =
        (0 until itemCount).map { Regex("""id=([^,]+),""").find(getItemAt(it).toString())!!.groupValues[1] }

    private fun comparisonConfig(layoutPresetId: String) = Configuration.DEFAULT.copy(
        servicePresets = listOf(
            ServicePreset(
                id = "preset",
                name = "preset",
                selectedServices = mapOf(ServiceRole.TRANSLATOR to "google"),
                comparisonTranslatorIds = listOf("bing")
            )
        ),
        activeServicePresetId = "preset",
        layoutPresetId = layoutPresetId
    )

    @Test
    fun `layout picker offers exactly Classic, Side By Side and Comparison`() {
        val panel = onEdt { LayoutPanel(newStore(), localizer) }
        val layoutCombo = onEdt { combos(panel).first { it.hasLayoutId(LayoutPresetIds.COMPARISON) } }

        assertEquals(listOf("classic", "side_by_side", "comparison"), onEdt { layoutCombo.layoutIds() })
        assertEquals(
            listOf("Classic", "Side By Side", "Comparison"),
            onEdt { (0 until layoutCombo.itemCount).map { layoutCombo.rowAt(it, selected = false).text } }
        )
        assertFalse(onEdt { layoutCombo.hasLayoutId("compact") })
    }

    @Test
    fun `comparison is selectable once two translators are usable`() {
        val store = newStore(comparisonConfig(LayoutPresetIds.CLASSIC))
        val panel = onEdt { LayoutPanel(store, localizer) { listOf("google", "bing") } }
        onEdt { panel.render(store.state.value) }
        val layoutCombo = onEdt { combos(panel).first { it.hasLayoutId(LayoutPresetIds.COMPARISON) } }

        onEdt {
            val row = layoutCombo.rowAt(layoutCombo.indexOfLayoutId(LayoutPresetIds.COMPARISON), selected = false)
            assertNull(row.toolTipText, "an eligible Comparison carries no unavailable hint")
            assertNull(layoutCombo.toolTipText)
        }
    }

    @Test
    fun `a saved compact layout is selected as Classic rather than left blank`() {
        val store = newStore(Configuration.DEFAULT.copy(layoutPresetId = "compact"))
        val panel = onEdt { LayoutPanel(store, localizer) }
        onEdt { panel.render(store.state.value) }
        val layoutCombo = onEdt { combos(panel).first { it.hasLayoutId(LayoutPresetIds.COMPARISON) } }

        val selected = onEdt { layoutCombo.selectedItem.toString() }
        assertTrue(selected.startsWith("LayoutInfo(id=classic,"), "selected was $selected")
    }

    @Test
    fun `unavailable comparison layout is dimmed with its reason and other layouts are not`() {
        val store = newStore()
        val panel = onEdt { LayoutPanel(store, localizer) { emptyList() } }
        onEdt { panel.render(store.state.value) }
        val layoutCombo = onEdt { combos(panel).first { it.hasLayoutId(LayoutPresetIds.COMPARISON) } }

        val dim = Color(120, 121, 122)
        val previous = UIManager.getColor("Label.disabledForeground")
        UIManager.put("Label.disabledForeground", dim)
        try {
            onEdt {
                val comparison = layoutCombo.indexOfLayoutId(LayoutPresetIds.COMPARISON)
                val classic = layoutCombo.indexOfLayoutId(LayoutPresetIds.CLASSIC)
                assertTrue(comparison >= 0, "comparison layout must still be listed")

                // The renderer reuses one component, so read each row before rendering the next.
                val disabledRow = layoutCombo.rowAt(comparison, selected = true)
                assertEquals(dim, disabledRow.foreground)
                assertEquals(Color(10, 90, 200), disabledRow.background)
                assertEquals(
                    localizer.getString("settings_window.layout_comparison_unavailable"),
                    disabledRow.toolTipText
                )

                val normalRow = layoutCombo.rowAt(classic, selected = true)
                assertEquals(Color(255, 255, 255), normalRow.foreground)
                assertNull(normalRow.toolTipText)
            }
        } finally {
            UIManager.put("Label.disabledForeground", previous)
        }
    }
}
