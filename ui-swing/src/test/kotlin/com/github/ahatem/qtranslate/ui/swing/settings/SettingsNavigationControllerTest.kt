package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel
import java.awt.BorderLayout
import java.awt.ComponentOrientation
import java.awt.Rectangle
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Page navigation and hit activation: lazy one-shot panel construction shared by navigation and
 * search, group selection, external page selection, the flash that marks an activated hit, the
 * count drawn beside a page name in the sidebar, and the page Settings opens on and comes back to
 * after a search.
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
        rowSuffix: (String) -> String = { "" },
    ) {
        val controller: SettingsNavigationController = SettingsNavigationController(
            navigation = SettingsNavigation { "sidebar.$it" },
            contentArea = contentArea,
            panelFactory = panelFactory,
            orientation = orientation,
            onPageShown = { shown += it },
            renderPanel = { rendered += it },
            rowSuffix = rowSuffix,
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
        rowSuffix: (String) -> String = { "" },
    ): Fixture = onEdt {
        Fixture(
            built, created, shown, rendered,
            orientation = orientation,
            panelFactory = panelFactory,
            rowSuffix = rowSuffix,
        )
    }

    private fun rowOfLabel(tree: JTree, label: String): Int =
        (0 until tree.rowCount).first { row ->
            (tree.getPathForRow(row).lastPathComponent as? DefaultMutableTreeNode)?.userObject == label
        }

    /**
     * A group heading keeps the icon slot, so its text starts where the pages' text does.
     *
     * Headings used to have no icon at all, which put their text a column further out at the
     * panel's edge — the sidebar read as two ragged columns. The slot is now kept but left blank,
     * and sized from a real icon: `FlatSVGIcon` reports its own scaled size, so a nominal 14 was a
     * few pixels narrow on a HiDPI display and the headings were still slightly out of line.
     */
    @Test
    fun `a group heading keeps an icon slot sized like a page's`() {
        val fixture = newFixture()

        val (groupIcon, pageIcon) = onEdt {
            val tree = fixture.controller.tree
            val renderer = tree.cellRenderer as DefaultTreeCellRenderer
            fun iconFor(label: String): Icon? {
                val row = rowOfLabel(tree, label)
                renderer.getTreeCellRendererComponent(
                    tree,
                    tree.getPathForRow(row).lastPathComponent,
                    false, false, false, row, false,
                )
                return renderer.icon
            }
            iconFor("sidebar.group_translation") to iconFor("sidebar.general")
        }

        // If the SVG resources are not loadable here there is nothing to compare against.
        val reference = pageIcon ?: return
        val slot = assertNotNull(groupIcon, "a group heading keeps the icon slot")
        assertEquals(
            reference.iconWidth,
            slot.iconWidth,
            "sized like a page's icon, so the heading's text starts at the same edge",
        )
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

    // ── The row suffix ────────────────────────────────────────────────────────

    @Test
    fun `a row suffix is drawn beside the page name while the row keeps the plain label`() {
        val fixture = newFixture(rowSuffix = { name -> if (name == "sidebar.hotkeys") " (12)" else "" })

        assertEquals(
            "sidebar.hotkeys (12)",
            renderedRowText(fixture.controller, "sidebar.hotkeys"),
            "the count sits beside the page name, where the row shows it",
        )
        assertEquals(
            "sidebar.general",
            renderedRowText(fixture.controller, "sidebar.general"),
            "a row without a count is drawn as before",
        )
        assertEquals(
            "sidebar.hotkeys",
            onEdt {
                val tree = fixture.controller.tree
                val node = tree.getPathForRow(rowOfLabel(tree, "sidebar.hotkeys")).lastPathComponent
                (node as DefaultMutableTreeNode).userObject
            },
            "the tree's own value stays the plain page label, so matching is unaffected",
        )

        // The suffix is display only: everything that matches on the name still does.
        onEdt { fixture.controller.selectPage("sidebar.hotkeys") }
        assertEquals(listOf("sidebar.hotkeys"), fixture.built, "navigation still matches the plain label")
    }

    // ── The page to open when Settings starts ─────────────────────────────────

    @Test
    fun `selectStartPage opens the page the session left off on`() {
        val fixture = newFixture()

        onEdt { fixture.controller.selectStartPage("sidebar.network") }

        assertEquals(listOf("sidebar.network"), fixture.built)
        assertEquals("sidebar.network", selectedLabel(fixture.controller.tree))
    }

    @Test
    fun `selectStartPage falls back to the first page with nothing to return to`() {
        val fixture = newFixture()

        onEdt { fixture.controller.selectStartPage(null) }

        assertEquals(listOf("sidebar.general"), fixture.built, "the first read of Settings opens the first page")
        assertEquals("sidebar.general", selectedLabel(fixture.controller.tree))
    }

    @Test
    fun `selectStartPage falls back to the first page when the remembered page is gone`() {
        val fixture = newFixture()

        onEdt { fixture.controller.selectStartPage("sidebar.renamed-away") }

        assertEquals(
            listOf("sidebar.general"),
            fixture.built,
            "a page renamed by a language change must not leave the content area blank",
        )
        assertEquals("sidebar.general", selectedLabel(fixture.controller.tree))
    }

    // ── Coming back from a search ─────────────────────────────────────────────

    @Test
    fun `restoring after a search returns to the page that was showing`() {
        val fixture = newFixture()
        onEdt { fixture.controller.showPanel("sidebar.network") }
        onEdt { fixture.controller.rememberPageBeforeSearch() }

        // The reader opens another page from a result, then clears the query.
        onEdt { fixture.controller.openHit(SearchHit.Page("sidebar.general")) }
        assertEquals("sidebar.general", selectedLabel(fixture.controller.tree))

        onEdt { fixture.controller.restorePageAfterSearch() }

        assertEquals(
            "sidebar.network",
            selectedLabel(fixture.controller.tree),
            "the sidebar returns to the page the reader was on when the search interrupted them",
        )
        assertEquals("sidebar.network", onEdt { fixture.controller.currentPanel?.name })
    }

    @Test
    fun `restoring without a remembered page leaves the page alone`() {
        val fixture = newFixture()
        onEdt { fixture.controller.showPanel("sidebar.network") }
        val showing = fixture.controller.currentPanel
        val shownBefore = fixture.shown.size

        onEdt { fixture.controller.restorePageAfterSearch() }

        assertSame(showing, fixture.controller.currentPanel, "nothing was remembered, so nothing is restored")
        assertEquals(shownBefore, fixture.shown.size, "and nothing is shown again")
    }

    @Test
    fun `restoring when the page never changed does not reopen it`() {
        val fixture = newFixture()
        onEdt { fixture.controller.showPanel("sidebar.network") }
        onEdt { fixture.controller.rememberPageBeforeSearch() }
        val shownBefore = fixture.shown.size

        onEdt { fixture.controller.restorePageAfterSearch() }

        // Re-selecting the page already showing would move the sidebar's selection onto it and
        // reset its scroll position, which is the work of losing the reader's place for nothing.
        assertTrue(onEdt { fixture.controller.tree.isSelectionEmpty }, "no navigation was issued")
        assertEquals(shownBefore, fixture.shown.size, "the page is not shown a second time")
        assertEquals("sidebar.network", onEdt { fixture.controller.currentPanel?.name })
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
        val hit = SearchHit.Setting(
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

    /** The text the sidebar's renderer draws for the row holding [label]. */
    private fun renderedRowText(controller: SettingsNavigationController, label: String): String = onEdt {
        val tree = controller.tree
        val row = rowOfLabel(tree, label)
        val renderer = tree.cellRenderer as DefaultTreeCellRenderer
        renderer.getTreeCellRendererComponent(
            tree,
            tree.getPathForRow(row).lastPathComponent,
            false, false, true, row, false,
        )
        renderer.text
    }
}
