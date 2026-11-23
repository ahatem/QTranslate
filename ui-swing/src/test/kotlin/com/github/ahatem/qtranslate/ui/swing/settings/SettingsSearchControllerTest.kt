package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel
import com.github.ahatem.qtranslate.ui.swing.testing.SilentLogger
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Query handling and result presentation, and the two edges of search mode the dialog hooks into. */
class SettingsSearchControllerTest {

    private val localizer by lazy {
        LocalizationManager(
            Files.createTempDirectory("qtranslate-search-controller").toFile(),
            LanguageTomlParser(),
            SilentLogger,
        )
    }

    /** One hit per page, on words only that page mentions. */
    private fun newController(
        activated: MutableList<SearchHit> = mutableListOf(),
        onSearchStarted: () -> Unit = {},
        onSearchEnded: () -> Unit = {},
    ): SettingsSearchController {
        val entriesByPage = mapOf(
            "General" to listOf(
                SettingsPanel.SettingEntry("Launch on system startup", "Startup", "Opens with the system", JLabel()),
            ),
            "Network" to listOf(
                SettingsPanel.SettingEntry("Proxy address:", "Proxy", "The proxy requests go through", JLabel()),
            ),
        )
        val index = SettingsSearchIndex(listOf(PageRef("General"), PageRef("Network"))) {
            entriesByPage.getValue(it)
        }
        return onEdt {
            SettingsSearchController(
                index,
                JTree(),
                localizer,
                onHitActivated = { activated += it },
                onSearchStarted = onSearchStarted,
                onSearchEnded = onSearchEnded,
            )
        }
    }

    private fun hits(controller: SettingsSearchController): List<SearchHit> = onEdt {
        val list = controller.resultsList
        (0 until list.model.size).map { list.model.getElementAt(it) }
    }

    @Test
    fun `the query decides which card shows and what it lists`() {
        class Case(val name: String, val query: String, val card: String, val hitCount: Int)
        val cases = listOf(
            Case("blank", "   ", SettingsSearchController.NAV_TREE, 0),
            Case("match", "proxy", SettingsSearchController.NAV_RESULTS, 1),
            Case("no hit", "zzz", SettingsSearchController.NAV_EMPTY, 0),
            Case("cleared", "", SettingsSearchController.NAV_TREE, 0),
        )
        for (case in cases) {
            val controller = newController()
            onEdt {
                // The cleared case types a query first so there is something to clear.
                controller.searchField.text = "proxy"
                controller.onQueryChanged()
                controller.searchField.text = case.query
                controller.onQueryChanged()
            }
            assertEquals(case.card, controller.visibleCard, "card for ${case.name}")
            assertEquals(case.hitCount, hits(controller).size, "hits for ${case.name}")
        }

        val match = newController()
        onEdt {
            match.searchField.text = "proxy"
            match.onQueryChanged()
        }
        assertEquals(listOf("Proxy address:"), hits(match).filterIsInstance<SearchHit.Setting>().map { it.entry.label })
        assertEquals("Network", hits(match).single().pageLabel)

        val none = newController()
        onEdt {
            none.searchField.text = "zzz"
            none.onQueryChanged()
        }
        val text = onEdt { none.noResultsLabel.text }
        assertTrue(text.contains("zzz") && text.contains("No settings match"), "the empty state names the query: $text")
    }

    @Test
    fun `search start and end are each reported once as the query opens and clears`() {
        var started = 0
        var ended = 0
        val controller = newController(onSearchStarted = { started++ }, onSearchEnded = { ended++ })

        onEdt { controller.searchField.text = "proxy" }
        assertEquals(1, started, "the first keystroke opens search")

        // Typing goes through the document; assigning text would read as closing and reopening.
        onEdt { appendToQuery(controller, " se") }
        onEdt { appendToQuery(controller, "rver") }
        assertEquals(1, started, "typing inside an open query does not open it again")
        assertEquals(0, ended)

        onEdt { controller.searchField.text = "" }
        onEdt { controller.onQueryChanged() }
        assertEquals(1, ended, "clearing ends search once")
        assertEquals(1, started)
        assertEquals(SettingsSearchController.NAV_TREE, controller.visibleCard)

        onEdt { controller.searchField.text = "   " }
        assertEquals(1, started, "whitespace is not a query")
        assertEquals(1, ended)

        onEdt { controller.searchField.text = "zzz" }
        assertEquals(2, started, "a query with no hits is still search mode")
        assertEquals(SettingsSearchController.NAV_EMPTY, controller.visibleCard)
        onEdt { controller.searchField.text = "" }
        assertEquals(2, ended, "clearing the empty card leaves search mode")
    }

    @Test
    fun `a result row shows the label and the kind or page path for each hit type`() {
        val index = SettingsSearchIndex(
            pages = listOf(PageRef("General"), PageRef("Network"), PageRef("Popups")),
            entriesForPage = { page ->
                if (page == "General") {
                    listOf(SettingsPanel.SettingEntry("Launch on system startup", "Startup", "", JLabel()))
                } else {
                    emptyList()
                }
            },
            actionsForPage = { page ->
                if (page == "Popups") listOf(SearchHit.Action(page, "Reset Page", run = {})) else emptyList()
            },
        )
        val controller = onEdt { SettingsSearchController(index, JTree(), localizer, onHitActivated = {}) }

        fun rowLabels(query: String): Pair<SearchHit, List<String>> {
            onEdt {
                controller.searchField.text = query
                controller.onQueryChanged()
            }
            val hit = hits(controller).first()
            val rendered = onEdt {
                controller.resultsList.cellRenderer
                    ?.getListCellRendererComponent(controller.resultsList, hit, 0, false, false) as JPanel
            }
            return hit to onEdt { descendants<JLabel>(rendered) }.map { it.text }
        }

        val (setting, settingLabels) = rowLabels("launch")
        assertTrue(setting is SearchHit.Setting)
        assertTrue("Launch on system startup" in settingLabels)
        assertTrue("General › Startup" in settingLabels, "the path tells identically-labelled rows apart: $settingLabels")

        val (page, pageLabels) = rowLabels("network")
        assertTrue(page is SearchHit.Page)
        assertEquals("Network", pageLabels.first())
        assertEquals(localizer.getString("settings_dialog.search_kind_page"), pageLabels.last())

        val (action, actionLabels) = rowLabels("reset")
        assertTrue(action is SearchHit.Action)
        assertTrue("Reset Page" in actionLabels)
        assertEquals("Popups › ${localizer.getString("settings_dialog.search_kind_action")}", actionLabels.last())
    }

    @Test
    fun `choosing a result reports the hit for activation`() {
        val activated = mutableListOf<SearchHit>()
        val controller = newController(activated)
        onEdt {
            controller.searchField.text = "proxy"
            controller.onQueryChanged()
            controller.resultsList.selectedIndex = 0
        }
        assertEquals(1, activated.size)
        val hit = activated.single()
        assertTrue(hit is SearchHit.Setting, "the hit is reported with its kind intact")
        assertEquals("Proxy address:", hit.entry.label)
        assertEquals("Network", hit.pageLabel)
    }

    @Test
    fun `down arrow enters the first result, and stays put when there is none`() {
        val controller = newController()
        onEdt {
            controller.searchField.text = "proxy"
            controller.onQueryChanged()
        }
        pressDown(controller)
        assertEquals(0, onEdt { controller.resultsList.selectedIndex })

        onEdt {
            controller.searchField.text = "zzz"
            controller.onQueryChanged()
            pressDown(controller)
        }
        assertEquals(-1, onEdt { controller.resultsList.selectedIndex })
    }

    /** Types [text] at the end of the query, the way the field's own document does. */
    private fun appendToQuery(controller: SettingsSearchController, text: String) {
        val document = controller.searchField.document
        document.insertString(document.length, text, null)
    }

    private fun pressDown(controller: SettingsSearchController) {
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)
        val key = controller.searchField.getInputMap(JComponent.WHEN_FOCUSED).get(stroke)
        assertNotNull(key, "Down is registered on the search field")
        val action = controller.searchField.actionMap.get(key)
        assertNotNull(action, "Down is wired to an action")
        action.actionPerformed(ActionEvent(controller.searchField, ActionEvent.ACTION_PERFORMED, ""))
    }

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
