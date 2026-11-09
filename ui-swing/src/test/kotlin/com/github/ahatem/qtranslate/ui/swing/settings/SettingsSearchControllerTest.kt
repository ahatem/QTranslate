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
 * and how a row is chosen.
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
    private fun newController(activated: MutableList<SearchHit> = mutableListOf()): SettingsSearchController {
        val entriesByPage = mapOf(
            "General" to listOf(
                SettingsPanel.SettingEntry("Launch on system startup", "Startup", "Opens with the system", JLabel()),
            ),
            "Network" to listOf(
                SettingsPanel.SettingEntry("Proxy address:", "Proxy", "The proxy requests go through", JLabel()),
            ),
        )
        val index = SettingsSearchIndex(listOf("General", "Network"), emptySet()) { entriesByPage.getValue(it) }
        return onEdt { SettingsSearchController(index, JTree(), localizer) { activated += it } }
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
        assertEquals(listOf("Proxy address:"), hits.map { it.entry.label })
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

    @Test
    fun `a result row shows the setting label and its page path`() {
        val controller = newController()
        onEdt {
            controller.searchField.text = "launch"
            controller.onQueryChanged()
        }
        val hit = hits(controller).single()
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
        assertEquals("Proxy address:", activated.single().entry.label)
        assertEquals("Network", activated.single().pageLabel)
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
