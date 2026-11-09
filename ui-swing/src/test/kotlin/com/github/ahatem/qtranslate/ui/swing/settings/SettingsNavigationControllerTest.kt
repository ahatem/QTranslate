package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel
import java.awt.BorderLayout
import java.awt.ComponentOrientation
import java.awt.Rectangle
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Page navigation and hit activation: lazy one-shot panel construction shared by navigation and
 * search, group selection, external page selection, and the flash that marks an activated hit.
 */
class SettingsNavigationControllerTest {

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        return result!!
    }

    private class Fixture(
        val built: MutableList<String>,
        val created: MutableList<JPanel>,
        val shown: MutableList<String>,
        val rendered: MutableList<JPanel>,
        val contentArea: JPanel = JPanel(BorderLayout()),
        orientation: () -> ComponentOrientation,
        panelFactory: (String) -> JPanel,
    ) {
        val controller: SettingsNavigationController = SettingsNavigationController(
            navigation = SettingsNavigation { "sidebar.$it" },
            contentArea = contentArea,
            panelFactory = panelFactory,
            orientation = orientation,
            onPageShown = { shown += it },
            renderPanel = { rendered += it },
        )
    }

    private fun newFixture(
        built: MutableList<String> = mutableListOf(),
        created: MutableList<JPanel> = mutableListOf(),
        shown: MutableList<String> = mutableListOf(),
        rendered: MutableList<JPanel> = mutableListOf(),
        orientation: () -> ComponentOrientation = { ComponentOrientation.LEFT_TO_RIGHT },
        panelFactory: (String) -> JPanel = { name ->
            built += name
            JPanel().apply { this.name = name }.also { created += it }
        },
    ): Fixture = onEdt { Fixture(built, created, shown, rendered, orientation = orientation, panelFactory = panelFactory) }

    private fun rowOfLabel(tree: JTree, label: String): Int =
        (0 until tree.rowCount).first { row ->
            (tree.getPathForRow(row).lastPathComponent as? DefaultMutableTreeNode)?.userObject == label
        }

    @Test
    fun `pages are built lazily, once, and swapped in place`() {
        val fixture = newFixture()

        assertTrue(fixture.built.isEmpty(), "no page is built at construction")

        onEdt { fixture.controller.showPanel("sidebar.general") }
        assertEquals(listOf("sidebar.general"), fixture.built)

        onEdt { fixture.controller.showPanel("sidebar.general") }
        onEdt { fixture.controller.showPanel("sidebar.network") }
        assertEquals(listOf("sidebar.general", "sidebar.network"), fixture.built, "a revisited page is never rebuilt")

        val scrolls = onEdt { fixture.contentArea.components.filterIsInstance<JScrollPane>() }
        assertEquals(1, scrolls.size, "the previous page's scroll pane is removed")
        assertEquals("sidebar.network", scrolls.single().viewport.view.name, "the latest page is the one shown")

        assertEquals(listOf("sidebar.general", "sidebar.general", "sidebar.network"), fixture.shown)
        // Like the dialog it replaces, each show renders the panel it put on screen.
        assertEquals(3, fixture.rendered.size)
        assertSame(fixture.created[1], fixture.controller.currentPanel)
    }

    @Test
    fun `the current panel is re-rendered on request`() {
        val fixture = newFixture()
        onEdt { fixture.controller.showPanel("sidebar.general") }
        assertEquals(1, fixture.rendered.size)

        onEdt { fixture.controller.renderCurrentPanel() }

        assertEquals(2, fixture.rendered.size)
        assertSame(fixture.rendered[0], fixture.rendered[1], "the same panel instance is rendered again")
    }

    @Test
    fun `rendering before any page is shown does nothing`() {
        val fixture = newFixture()
        onEdt { fixture.controller.renderCurrentPanel() }
        assertTrue(fixture.rendered.isEmpty())
    }

    @Test
    fun `selecting a group opens its first child`() {
        val fixture = newFixture()

        onEdt { fixture.controller.tree.setSelectionRow(rowOfLabel(fixture.controller.tree, "sidebar.group_translation")) }

        assertEquals(listOf("sidebar.services"), fixture.built, "the group's first child is the page shown")
        val selected = onEdt {
            (fixture.controller.tree.selectionPath.lastPathComponent as? DefaultMutableTreeNode)?.userObject
        }
        assertEquals("sidebar.services", selected)
    }

    @Test
    fun `selectPage selects the page row and shows it`() {
        val fixture = newFixture()

        onEdt { fixture.controller.selectPage("sidebar.network") }

        assertEquals(listOf("sidebar.network"), fixture.built)
        assertEquals("sidebar.network", selectedLabel(fixture.controller.tree))
    }

    @Test
    fun `selectPage ignores labels that are not pages`() {
        val fixture = newFixture()

        onEdt { fixture.controller.selectPage("sidebar.missing") }

        assertTrue(fixture.built.isEmpty())
        assertTrue(onEdt { fixture.controller.tree.isSelectionEmpty })
    }

    @Test
    fun `pages are built with the dialog's orientation`() {
        val fixture = newFixture(orientation = { ComponentOrientation.RIGHT_TO_LEFT })

        onEdt { fixture.controller.showPanel("sidebar.general") }

        assertEquals(
            ComponentOrientation.RIGHT_TO_LEFT,
            onEdt { fixture.created.single().componentOrientation },
            "a page built after the dialog flipped reads the dialog's direction",
        )
    }

    @Test
    fun `a search hit shows its page and flashes its anchor`() {
        val anchor = JLabel("proxy row")
        val hit = SearchHit(
            "sidebar.network",
            SettingsPanel.SettingEntry("Proxy address:", "Proxy", "", anchor),
        )
        val built = mutableListOf<String>()
        val created = mutableListOf<JPanel>()
        onEdt { anchor.bounds = Rectangle(0, 0, 60, 20) }
        val fixture = newFixture(built = built, created = created, panelFactory = { name ->
            built += name
            JPanel().apply {
                this.name = name
                if (name == "sidebar.network") add(anchor)
            }.also { created += it }
        })

        onEdt { fixture.controller.openHit(hit) }
        // openHit defers the scroll and the flash to the event queue; flush both rounds.
        onEdt {}
        onEdt {}

        assertEquals(listOf("sidebar.network"), built)
        assertSame(fixture.created.single(), fixture.controller.currentPanel)
        assertEquals("sidebar.network", selectedLabel(fixture.controller.tree))
        assertTrue(onEdt { fixture.controller.markerOverlay.isVisible }, "the marker is up")
        assertEquals(1f, onEdt { fixture.controller.markerOverlay.strength }, "the marker starts at full strength")
    }

    @Test
    fun `flash strength holds full, then fades to zero`() {
        assertEquals(1f, SettingsNavigationController.flashStrength(0f))
        assertEquals(1f, SettingsNavigationController.flashStrength(1320f), "held for the first part")
        assertEquals(0.5f, SettingsNavigationController.flashStrength(1760f), 0.001f, "mid fade")
        assertEquals(0f, SettingsNavigationController.flashStrength(2200f), "gone at the end")
    }

    @Test
    fun `search entries build the page once through the shared cache`() {
        val built = mutableListOf<String>()
        val fixture = newFixture(built = built, panelFactory = { name ->
            built += name
            if (name == "sidebar.general") EntryPanel() else JPanel().apply { this.name = name }
        })

        val first = onEdt { fixture.controller.searchEntries("sidebar.general") }
        val second = onEdt { fixture.controller.searchEntries("sidebar.general") }

        assertEquals(1, first.size)
        assertEquals("Fake setting", first.single().label)
        assertEquals(first, second)
        assertEquals(listOf("sidebar.general"), built, "the second read reuses the cached panel")

        assertTrue(
            onEdt { fixture.controller.searchEntries("sidebar.network") }.isEmpty(),
            "a page without settings metadata contributes nothing",
        )
        assertEquals(listOf("sidebar.general", "sidebar.network"), built)
    }

    /** A panel exposing one searchable row, the way real panels do through the layout helpers. */
    private class EntryPanel : SettingsPanel() {
        init {
            addCheckbox("Fake setting", false) {}
            finishLayout()
        }

        override fun render(state: com.github.ahatem.qtranslate.core.settings.mvi.SettingsState) = Unit
    }

    private fun selectedLabel(tree: JTree): String? = onEdt {
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? String
    }
}
