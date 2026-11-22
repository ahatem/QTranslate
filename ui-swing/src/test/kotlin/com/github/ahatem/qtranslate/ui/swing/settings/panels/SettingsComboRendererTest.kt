package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HistoryPlacement
import com.github.ahatem.qtranslate.core.settings.data.LanguagePlacement
import com.github.ahatem.qtranslate.core.settings.data.LayoutConfig
import com.github.ahatem.qtranslate.core.settings.data.LayoutConfigMigration
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.ServicePlacement
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelector
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.ToolbarStyle
import com.github.ahatem.qtranslate.core.settings.data.WorkspaceLayout
import com.github.ahatem.qtranslate.core.settings.data.withLayoutConfig
import com.github.ahatem.qtranslate.core.settings.data.withWorkspaceLayout
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
import java.awt.GridLayout
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    private fun allButtons(root: Component): List<JButton> =
        (if (root is JButton) listOf(root) else emptyList()) +
            if (root is Container) root.components.flatMap(::allButtons) else emptyList()

    private fun labels(root: Component): List<JLabel> =
        (if (root is JLabel) listOf(root) else emptyList()) +
            if (root is Container) root.components.flatMap(::labels) else emptyList()

    /**
     * The page's preset cards: the buttons laid out by the 2×2 grid, in that grid's order.
     *
     * Not every `JButton` on the page: each combo box carries a `BasicArrowButton` of its own
     * inside its UI — three of them here, all with empty text.
     */
    private fun cards(panel: Container): List<JButton> =
        allButtons(panel).filter { it.parent?.layout is GridLayout }

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

    /**
     * The picker whose entries are pane arrangements.
     *
     * The page's other four pickers hold their own enums, so the item type identifies this one
     * without depending on row order or on text that the renderer localizes.
     */
    private fun panesCombo(panel: Container): JComboBox<*> =
        combos(panel).single { combo ->
            (0 until combo.itemCount).any { combo.getItemAt(it) is PaneLayout }
        }

    private fun historyCombo(panel: Container): JComboBox<*> =
        combos(panel).single { combo ->
            (0 until combo.itemCount).any { combo.getItemAt(it) is HistoryPlacement }
        }

    /**
     * The Comparison starting point: the last of the four cards, in the order the page promises.
     *
     * That order is asserted in `the four starting points are cards in order`, so this cannot
     * drift silently.
     */
    private fun comparisonCard(panel: Container): JButton = cards(panel).last()

    private fun JComboBox<*>.items(): List<Any?> = (0 until itemCount).map { getItemAt(it) }

    private fun JComboBox<*>.renderedTexts(): List<String> =
        (0 until itemCount).map { rowAt(it, selected = false).text }

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

    @Test
    fun `layout page combos preserve native selection`() {
        // Panes, toolbar and the service selector. The status bar is a checkbox rather than a
        // picker, and the close and minimise pickers are on the General page now.
        val panel = onEdt { LayoutPanel(newStore(), localizer) }
        assertNativeRows(panel, expectedCombos = 3)
    }

    private fun comparisonConfig(layout: LayoutConfig = LayoutConfig.DEFAULT) = Configuration.DEFAULT
        .copy(
            servicePresets = listOf(
                ServicePreset(
                    id = "preset",
                    name = "preset",
                    selectedServices = mapOf(ServiceRole.TRANSLATOR to "google"),
                    comparisonTranslatorIds = listOf("bing")
                )
            ),
            activeServicePresetId = "preset",
        )
        .withLayoutConfig(layout)

    private fun testPanel(store: SettingsStore, translatorIds: () -> List<String>, onOpenServices: () -> Unit = {}): LayoutPanel =
        onEdt {
            LayoutPanel(store, localizer, availableTranslatorIds = translatorIds, onOpenServicesSettings = onOpenServices).also {
                it.render(store.state.value)
            }
        }

    @Test
    fun `the panes picker offers exactly stacked, side by side and comparison`() {
        val panel = testPanel(newStore(), { emptyList() })
        val panes = onEdt { panesCombo(panel) }

        assertEquals(
            listOf(PaneLayout.STACKED, PaneLayout.SIDE_BY_SIDE, PaneLayout.COMPARISON),
            onEdt { panes.items() },
            "the picker lists the three arrangements and nothing else",
        )
        assertEquals(
            listOf("Stacked", "Side by side", "Comparison"),
            onEdt { panes.renderedTexts() },
            "each arrangement is named, not printed as its enum constant",
        )
        // The retired compact layout is a toolbar style now, not a fourth arrangement to choose.
        assertFalse(
            onEdt { panes.renderedTexts() }.any { it == "Compact" },
            "the retired layout id is not offered",
        )
    }

    /** What the page's own summary line should say for [layout], built from the same keys. */
    private fun expectedSummary(layout: WorkspaceLayout): String {
        val panes = when (layout.panes) {
            PaneLayout.STACKED -> localizer.getString("settings_window.layout_panes_stacked")
            PaneLayout.SIDE_BY_SIDE -> localizer.getString("settings_window.layout_panes_side_by_side")
            PaneLayout.COMPARISON -> localizer.getString("settings_window.layout_panes_comparison")
        }
        val history = when (layout.history) {
            HistoryPlacement.TOP_ROW -> localizer.getString("settings_window.layout_history_top_row")
            HistoryPlacement.BOTTOM_ROW -> localizer.getString("settings_window.layout_history_bottom_row")
            HistoryPlacement.COMBINED -> localizer.getString("settings_window.layout_history_combined")
            HistoryPlacement.RELOCATED -> localizer.getString("settings_window.layout_history_relocated")
        }
        val languages = when (layout.languages) {
            LanguagePlacement.ROW -> localizer.getString("settings_window.layout_languages_row")
            LanguagePlacement.RELOCATED -> localizer.getString("settings_window.layout_languages_relocated")
        }
        val selector = if (layout.panes == PaneLayout.COMPARISON) {
            localizer.getString("settings_window.layout_selector_absent")
        } else {
            when (layout.services) {
                ServicePlacement.BOTTOM_STRIP -> localizer.getString("settings_window.layout_selector_bottom_strip")
                ServicePlacement.PICKER -> localizer.getString("settings_window.layout_selector_picker")
                ServicePlacement.RELOCATED -> localizer.getString("settings_window.layout_services_relocated")
            }
        }
        return localizer.getString("settings_window.layout_summary_format", panes, history, languages, selector)
    }

    private fun summaryLine(panel: Container, layout: WorkspaceLayout): JLabel {
        val expected = expectedSummary(layout)
        return labels(panel).singleOrNull { it.text == expected }
            ?: error(
                "no label reads '$expected'; the page shows:\n" +
                    labels(panel).mapNotNull { it.text }.joinToString("\n")
            )
    }

    @Test
    fun `the summary line names all four values in the order of the rows below`() {
        val layout = WorkspaceLayout(
            PaneLayout.SIDE_BY_SIDE, HistoryPlacement.COMBINED, LanguagePlacement.ROW,
            ServicePlacement.BOTTOM_STRIP,
        )
        val panel = testPanel(newStore(Configuration.DEFAULT.withWorkspaceLayout(layout)), { emptyList() })

        onEdt {
            assertEquals(
                expectedSummary(layout),
                summaryLine(panel, layout).text,
                "the page opens by saying what the window is, not by making the user read the rows",
            )
        }
    }

    @Test
    fun `the summary says the selector is not shown on a comparison board`() {
        val layout = WorkspaceLayout(PaneLayout.COMPARISON, HistoryPlacement.TOP_ROW, LanguagePlacement.ROW)
        val panel = testPanel(newStore(Configuration.DEFAULT.withWorkspaceLayout(layout)), { emptyList() })

        onEdt {
            val summary = summaryLine(panel, layout).text
            assertTrue(
                summary.contains(localizer.getString("settings_window.layout_selector_absent")),
                "a comparison board has no selector, and the summary says so instead of naming an unused value: $summary",
            )
        }
    }

    @Test
    fun `the summary is rebuilt on every render`() {
        val store = newStore()
        val panel = testPanel(store, { emptyList() })
        val stacked = Configuration.DEFAULT.workspaceLayout
        val compared = stacked.copy(panes = PaneLayout.COMPARISON)
        onEdt { assertEquals(expectedSummary(stacked), summaryLine(panel, stacked).text) }

        onEdt {
            store.dispatch(
                SettingsIntent.TransformDraft { it.withWorkspaceLayout(compared) }
            )
            panel.render(store.state.value)
        }

        onEdt {
            assertEquals(
                expectedSummary(compared),
                summaryLine(panel, compared).text,
                "the line follows the state rather than being computed once at build time",
            )
        }
    }

    @Test
    fun `the four starting points are cards in order`() {
        val panel = testPanel(newStore(), { emptyList() })

        assertEquals(
            listOf(
                localizer.getString("settings_window.layout_card_classic"),
                localizer.getString("settings_window.layout_card_compact"),
                localizer.getString("settings_window.layout_card_side_by_side"),
                // Comparison is unavailable here — one usable translator — so its card already
                // says how to obtain it rather than only what it is called.
                localizer.getString("settings_window.layout_comparison_configure"),
            ),
            onEdt { cards(panel).map { it.text } },
            "four one-click arrangements, in the order the page promises",
        )
        assertTrue(
            onEdt { cards(panel).all { it.toolTipText != null } },
            "every card explains what pressing it gets you",
        )
    }

    @Test
    fun `a preset card writes that arrangement`() {
        val store = newStore()
        val panel = testPanel(store, { emptyList() })

        onEdt { cards(panel)[1].doClick() }

        assertEquals(
            HistoryPlacement.COMBINED,
            store.state.value.workingConfiguration.workspaceLayout.history,
            "the Compact card writes the shared-row history placement, not a label",
        )
        assertTrue(store.state.value.isDirty)
    }

    @Test
    fun `comparison is offered once two translators are usable`() {
        val store = newStore(comparisonConfig())
        val panel = testPanel(store, { listOf("google", "bing") })
        val panes = onEdt { panesCombo(panel) }

        onEdt {
            val row = panes.rowAt(panes.items().indexOf(PaneLayout.COMPARISON), selected = false)
            assertNull(row.toolTipText, "an eligible Comparison carries no unavailable hint")
        }
        assertEquals(
            localizer.getString("settings_window.layout_card_comparison"),
            onEdt { comparisonCard(panel).text },
            "the card names the arrangement rather than the setup it does not need",
        )
        assertEquals(
            localizer.getString("settings_window.layout_card_comparison_hint"),
            onEdt { comparisonCard(panel).toolTipText },
        )
    }

    @Test
    fun `a retired layout id still shows the arrangement it stands for`() {
        // "compact" was a stored layout id until this release. It is a toolbar style now, and a
        // configuration that carries it must still render as the arrangement it described rather
        // than leaving the pickers blank.
        val legacy = Configuration.DEFAULT.copy(layoutPresetId = LayoutPresetIds.LEGACY_COMPACT)
        val store = newStore(legacy.withLayoutConfig(LayoutConfigMigration.fromV10(legacy)))
        val panel = testPanel(store, { emptyList() })

        assertEquals(PaneLayout.STACKED, onEdt { panesCombo(panel).selectedItem })
        assertEquals(HistoryPlacement.TOP_ROW, onEdt { historyCombo(panel).selectedItem })
    }

    @Test
    fun `unavailable comparison is dimmed with its reason and other panes are not`() {
        val panel = testPanel(newStore(), { emptyList() })
        val panes = onEdt { panesCombo(panel) }

        val dim = Color(120, 121, 122)
        val previous = UIManager.getColor("Label.disabledForeground")
        UIManager.put("Label.disabledForeground", dim)
        try {
            onEdt {
                val comparison = panes.items().indexOf(PaneLayout.COMPARISON)
                val stacked = panes.items().indexOf(PaneLayout.STACKED)
                assertTrue(comparison >= 0, "comparison must still be listed")

                // The renderer reuses one component, so read each row before rendering the next.
                val disabledRow = panes.rowAt(comparison, selected = true)
                assertEquals(dim, disabledRow.foreground)
                assertEquals(Color(10, 90, 200), disabledRow.background)
                assertEquals(
                    localizer.getString("settings_window.layout_comparison_unavailable"),
                    disabledRow.toolTipText
                )

                val normalRow = panes.rowAt(stacked, selected = true)
                assertEquals(Color(255, 255, 255), normalRow.foreground)
                assertNull(normalRow.toolTipText)
            }
        } finally {
            UIManager.put("Label.disabledForeground", previous)
        }

        // The card is the way there, so it carries the reason as its hint too.
        assertEquals(
            localizer.getString("settings_window.layout_comparison_unavailable"),
            onEdt { comparisonCard(panel).toolTipText },
        )
    }

    @Test
    fun `picking the unavailable comparison card opens Services settings instead of doing nothing`() {
        val store = newStore()
        var opened = 0
        val panel = testPanel(store, { emptyList() }, onOpenServices = { opened++ })

        onEdt { comparisonCard(panel).doClick() }

        assertEquals(1, opened, "acting on the unavailable card sends the user to fix it")
        assertFalse(store.state.value.isDirty, "and writes no arrangement the window cannot build")
        assertEquals(
            PaneLayout.STACKED,
            store.state.value.workingConfiguration.layoutConfig.paneLayout,
        )
    }

    @Test
    fun `picking comparison in the panes picker while it is unavailable sends the user to Services`() {
        val store = newStore()
        var opened = 0
        val panel = testPanel(store, { emptyList() }, onOpenServices = { opened++ })
        val panes = onEdt { panesCombo(panel) }

        onEdt { panes.selectedIndex = panes.items().indexOf(PaneLayout.COMPARISON) }

        assertEquals(1, opened, "selecting the unavailable entry is an action, not a no-op")
        assertEquals(
            PaneLayout.STACKED,
            store.state.value.workingConfiguration.layoutConfig.paneLayout,
            "and the stored arrangement is left alone rather than set to something unusable",
        )
        assertEquals(
            PaneLayout.STACKED,
            onEdt { panes.selectedItem },
            "the picker falls back to the saved arrangement rather than showing a layout it refused",
        )
    }
}
