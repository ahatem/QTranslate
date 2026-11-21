package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Query handling and result presentation: which card shows for a query, what the rows carry,
 * how a row is chosen, and the two edges of search mode the dialog hooks into to restore the
 * page the reader came from.
 */
class SettingsSearchControllerTest {

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
    fun `a blank query keeps the section tree card and lists nothing`() {
        val controller = newController()
        onEdt {
            controller.searchField.text = "   "
            controller.onQueryChanged()
        }
        assertEquals(SettingsSearchController.NAV_TREE, controller.visibleCard)
        assertEquals(0, hits(controller).size)
    }

    @Test
    fun `a matching query lists the hits on the results card`() {
        val controller = newController()
        onEdt {
            controller.searchField.text = "proxy"
            controller.onQueryChanged()
        }
        assertEquals(SettingsSearchController.NAV_RESULTS, controller.visibleCard)
        val hits = hits(controller)
        assertEquals(
            listOf("Proxy address:"),
            hits.filterIsInstance<SearchHit.Setting>().map { it.entry.label },
        )
        assertEquals("Network", hits.single().pageLabel)
    }

    @Test
    fun `a query with no hits shows the empty card naming the query`() {
        val controller = newController()
        onEdt {
            controller.searchField.text = "zzz"
            controller.onQueryChanged()
        }
        assertEquals(SettingsSearchController.NAV_EMPTY, controller.visibleCard)
        val text = onEdt { controller.noResultsLabel.text }
        assertTrue(text.contains("zzz"), "the empty state names the query: $text")
        assertTrue(text.contains("No settings match"), "the empty state uses the localized template: $text")
    }

    @Test
    fun `clearing the query returns the tree card`() {
        val controller = newController()
        onEdt {
            controller.searchField.text = "proxy"
            controller.onQueryChanged()
            controller.searchField.text = ""
            controller.onQueryChanged()
        }
        assertEquals(SettingsSearchController.NAV_TREE, controller.visibleCard)
        assertEquals(0, hits(controller).size)
    }

    // ── The edges of search mode ──────────────────────────────────────────────
    //
    // Searching is a temporary mode over the sidebar: the dialog remembers the page that was
    // showing when it opens and takes the reader back to it when it closes. Both edges therefore
    // have to be reported exactly once — a second "started" would remember the page a result had
    // already opened, and a second "ended" would restore a page nobody was on.

    @Test
    fun `opening a query reports the start of search exactly once`() {
        var started = 0
        var ended = 0
        val controller = newController(
            onSearchStarted = { started++ },
            onSearchEnded = { ended++ },
        )

        onEdt { controller.searchField.text = "proxy" }
        assertEquals(1, started, "the first keystroke opens search")

        // More typing goes through the document, as real typing does. Assigning to the field
        // would replace the whole query, which the controller rightly reads as closing and
        // reopening search.
        onEdt { appendToQuery(controller, " se") }
        onEdt { appendToQuery(controller, "rver") }
        assertEquals(1, started, "typing on inside an open query does not open it again")
        assertEquals(0, ended, "and nothing has ended while the query is still up")
    }

    @Test
    fun `clearing the query reports the end of search exactly once`() {
        var started = 0
        var ended = 0
        val controller = newController(
            onSearchStarted = { started++ },
            onSearchEnded = { ended++ },
        )

        onEdt { controller.searchField.text = "proxy" }
        onEdt { controller.searchField.text = "" }

        assertEquals(1, ended, "emptying the field ends search")
        assertEquals(SettingsSearchController.NAV_TREE, controller.visibleCard, "and the tree is back")

        onEdt { controller.onQueryChanged() }
        assertEquals(1, ended, "a further pass over the cleared field does not end it again")
        assertEquals(1, started, "nor opens it again")
    }

    @Test
    fun `a blank query typed into an empty field reports neither edge of search`() {
        var started = 0
        var ended = 0
        val controller = newController(
            onSearchStarted = { started++ },
            onSearchEnded = { ended++ },
        )

        onEdt {
            controller.searchField.text = "   "
            controller.onQueryChanged()
        }

        assertEquals(0, started, "whitespace is not a query, so search never opened")
        assertEquals(0, ended, "and there was nothing to close")
        assertEquals(SettingsSearchController.NAV_TREE, controller.visibleCard)
    }

    @Test
    fun `a query that matches nothing still opens search until it is cleared`() {
        var started = 0
        var ended = 0
        val controller = newController(
            onSearchStarted = { started++ },
            onSearchEnded = { ended++ },
        )

        onEdt { controller.searchField.text = "zzz" }
        assertEquals(1, started, "the empty card is still search mode, not the tree")
        assertEquals(SettingsSearchController.NAV_EMPTY, controller.visibleCard)

        onEdt { controller.searchField.text = "" }
        assertEquals(1, ended, "clearing the empty card also leaves search mode")
        assertEquals(SettingsSearchController.NAV_TREE, controller.visibleCard)
    }

    @Test
    fun `a result row shows the setting label and its page path`() {
        val controller = newController()
        onEdt {
            controller.searchField.text = "launch"
            controller.onQueryChanged()
        }
        val hit = hits(controller).single()
        assertTrue(hit is SearchHit.Setting, "a label query finds the setting")
        val rendered = onEdt {
            @Suppress("UNCHECKED_CAST")
            controller.resultsList.cellRenderer
                ?.getListCellRendererComponent(controller.resultsList, hit, 0, false, false) as JPanel
        }
        val labels = onEdt { descendants<JLabel>(rendered) }
        assertTrue(labels.any { it.text == hit.entry.label }, "the row names the setting")
        assertEquals(
            "General › Startup",
            labels.single { it.text.contains("›") }.text,
            "the path tells identically-labelled rows apart",
        )
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
    fun `a page result says it is a page rather than a setting`() {
        val controller = newController()
        onEdt {
            controller.searchField.text = "network"
            controller.onQueryChanged()
        }
        val hit = hits(controller).single()

        val rendered = onEdt {
            @Suppress("UNCHECKED_CAST")
            controller.resultsList.cellRenderer
                ?.getListCellRendererComponent(controller.resultsList, hit, 0, false, false) as JPanel
        }
        val labels = onEdt { descendants<JLabel>(rendered) }

        assertTrue(hit is SearchHit.Page, "a page name finds the page")
        assertTrue(labels.any { it.text == "Network" }, "the row names the page")
        assertEquals(
            localizer.getString("settings_dialog.search_kind_page"),
            labels.last().text,
            "and says that choosing it navigates rather than opening a setting",
        )
    }

    @Test
    fun `an action result says what kind of result it is`() {
        val index = SettingsSearchIndex(
            pages = listOf(PageRef("Popups")),
            entriesForPage = { emptyList() },
            actionsForPage = { page -> listOf(SearchHit.Action(page, "Reset Page", run = {})) },
        )
        val controller = onEdt {
            SettingsSearchController(index, JTree(), localizer, onHitActivated = {})
        }
        onEdt {
            controller.searchField.text = "reset"
            controller.onQueryChanged()
        }
        val hit = hits(controller).single()

        val rendered = onEdt {
            @Suppress("UNCHECKED_CAST")
            controller.resultsList.cellRenderer
                ?.getListCellRendererComponent(controller.resultsList, hit, 0, false, false) as JPanel
        }
        val labels = onEdt { descendants<JLabel>(rendered) }

        assertTrue(labels.any { it.text == "Reset Page" }, "the row names the action")
        assertEquals(
            "Popups › ${localizer.getString("settings_dialog.search_kind_action")}",
            labels.last().text,
            "the path says which page the action belongs to",
        )
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
