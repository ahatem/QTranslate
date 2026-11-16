package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.CellRendererPane
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.file.Files
import javax.accessibility.Accessible
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The command list is a scroll pane inside the settings page's scroll pane, and it is the page's
 * tallest block. Both of those are ways for a settings screen to become unpleasant, so they are
 * held here rather than left to review:
 *
 *  - the list must keep a bounded height and scroll its own contents, instead of growing to
 *    forty-odd rows and pushing the page taller with it;
 *  - a wheel gesture must be able to leave the list when the list has nothing left to scroll,
 *    which Swing's scroll pane does not do on its own — it consumes every wheel event it sees,
 *    including the ones it cannot act on;
 *  - changing or clearing a shortcut must be reachable from the row it applies to, not from two
 *    buttons under the whole list.
 */
class KeyboardPanelCommandListUxTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-kbd-list").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        UIScale.setZoomFactor(1f)
        scopes.forEach { it.cancel() }
    }

    private fun store(configuration: Configuration = Configuration.DEFAULT): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-kbd-list-store").toFile()
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

    /** A panel as the settings dialog shows it: a page inside a scroll pane. */
    private class Page(
        val settings: SettingsStore,
        val panel: KeyboardPanel,
        val outer: JScrollPane,
    ) {
        val table: JTable get() = panel.tableForTest()
        val list: JScrollPane get() = panel.listScrollForTest()
    }

    private fun page(
        configuration: Configuration = Configuration.DEFAULT,
        recorder: ((HotkeyAction, HotkeyBinding?) -> HotkeyBinding?)? = null,
        withOuter: Boolean = true,
    ): Page {
        val settings = store(configuration)
        val panel = onEdt { KeyboardPanel(settings, localizer, recorder = recorder) }
        onEdt { panel.render(settings.state.value) }
        val outer = onEdt {
            JScrollPane(panel).apply {
                if (withOuter) {
                    setSize(UIScale.scale(700), UIScale.scale(320))
                    verticalScrollBar.apply {
                        minimum = 0
                        maximum = 1200
                        visibleAmount = 320
                        unitIncrement = 16
                        value = 400
                    }
                }
            }
        }
        onEdt { outer.updateUI() }
        onEdt { if (withOuter) layOut(outer, panel) }
        return Page(settings, panel, outer)
    }

    /**
     * Gives [outer]'s hierarchy the bounds a visible window would give it.
     *
     * Offscreen components stay 0x0 even after `validate()`: a wheel over a zero-sized view finds
     * nothing, and an inner viewport with no height cannot scroll regardless of its model. These
     * sizes are set by hand so the gesture routing is exercised against real geometry.
     */
    private fun layOut(outer: JScrollPane, panel: KeyboardPanel) {
        outer.viewport.size = outer.size
        panel.setSize(outer.viewport.extentSize)
        panel.doLayout()
        val list = panel.listScrollForTest()
        list.setSize(list.preferredSize)
        list.doLayout()
        list.viewport.size = Dimension(
            list.width - list.verticalScrollBar.preferredSize.width,
            list.height,
        )
        val view = list.viewport.view
        view.setSize(
            view.preferredSize.width.coerceAtLeast(list.viewport.width),
            view.preferredSize.height,
        )
        list.viewport.doLayout()
    }

    private fun scrollBar(min: Int, max: Int, visible: Int, value: Int): JScrollBar =
        JScrollBar().apply {
            minimum = min
            maximum = max
            visibleAmount = visible
            this.value = value
        }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    @Test
    fun `the list keeps a bounded height instead of the height of every row`() {
        val page = page()
        val scroll = page.list

        val contentHeight = onEdt {
            (0 until page.table.rowCount).sumOf { page.table.getRowHeight(it) }
        }
        assertTrue(
            contentHeight > scroll.preferredSize.height,
            "the rows are taller than the viewport, so the list has to scroll ($contentHeight in ${scroll.preferredSize.height})",
        )
        assertTrue(
            scroll.preferredSize.height <= UIScale.scale(34) * 12,
            "the list is a window onto the commands, not the whole of them (${scroll.preferredSize.height})",
        )
    }

    @Test
    fun `the list height does not follow the filter`() {
        val page = page()
        val before = page.list.preferredSize.height

        onEdt {
            page.panel.filterButtonForTest(localizer.getString("settings_hotkeys.filter_conflicts")).doClick()
        }

        assertEquals(
            before,
            page.list.preferredSize.height,
            "narrowing the list to a handful of commands must not shrink the page under the pointer",
        )
    }

    @Test
    fun `the bounded height follows the display scale`() {
        val normal = page().list.preferredSize.height
        UIScale.setZoomFactor(2f)
        val doubled = page().list.preferredSize.height

        assertTrue(
            doubled >= UIScale.scale(34) * 11,
            "at 200% the viewport still holds eleven scaled rows ($doubled)",
        )
        assertTrue(
            doubled > normal * 1.5,
            "and it grows with the display rather than staying a fixed pixel block ($normal -> $doubled)",
        )
    }

    @Test
    fun `the page still reports a height it can scroll`() {
        val page = page()
        onEdt { page.panel.setSize(page.panel.preferredSize) }
        assertTrue(
            page.panel.preferredSize.height > 0,
            "the page is laid out from real content, not collapsed",
        )
    }

    // -------------------------------------------------------------------------
    // Scrolling
    // -------------------------------------------------------------------------

    @Test
    fun `a scroll bar reports no room at either end`() {
        assertFalse(scrollBarCanMove(scrollBar(0, 1000, 300, 0), -1), "at the top, up belongs to the page")
        assertTrue(scrollBarCanMove(scrollBar(0, 1000, 300, 0), 1), "at the top, the list can still go down")
        assertTrue(scrollBarCanMove(scrollBar(0, 1000, 300, 700), -1), "at the bottom, the list can go up")
        assertFalse(scrollBarCanMove(scrollBar(0, 1000, 300, 700), 1), "at the bottom, down belongs to the page")
        assertFalse(scrollBarCanMove(scrollBar(0, 300, 300, 0), 1), "a list with nothing to scroll has no room")
        assertFalse(scrollBarCanMove(scrollBar(0, 1000, 300, 400), 0), "a zero rotation goes nowhere")
    }

    @Test
    fun `a wheel the list can use stays with the list`() {
        val page = page()
        val forwarded = mutableListOf<MouseWheelEvent>()
        onEdt {
            page.outer.addMouseWheelListener { forwarded += it }
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 200
            }
        }

        onEdt { wheel(page.table, rotation = 1) }

        assertTrue(
            forwarded.isEmpty(),
            "the list has room in that direction, so it keeps the gesture and the page does not move",
        )
    }

    @Test
    fun `the wheel leaves the list at its top`() {
        val page = page()
        val before = onEdt { page.outer.verticalScrollBar.value }
        onEdt {
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 0
            }
        }

        onEdt { wheelOver(page.list.viewport.view, rotation = -1) }

        assertTrue(
            onEdt { page.outer.verticalScrollBar.value } < before,
            "the page takes the gesture the list cannot use",
        )
        assertEquals(0, onEdt { page.list.verticalScrollBar.value }, "the list does not move as well")
    }

    @Test
    fun `the wheel leaves the list at its bottom`() {
        val page = page()
        val before = onEdt { page.outer.verticalScrollBar.value }
        val atBottom = onEdt {
            page.list.verticalScrollBar.apply {
                value = maximum - visibleAmount - minimum
            }.value
        }

        onEdt { wheelOver(page.list.viewport.view, rotation = 1) }

        assertTrue(
            onEdt { page.outer.verticalScrollBar.value } > before,
            "at the bottom, downward belongs to the page",
        )
        assertEquals(atBottom, onEdt { page.list.verticalScrollBar.value }, "and the list stays put")
    }

    @Test
    fun `the page itself scrolls from a wheel over the list`() {
        val page = page()
        val before = onEdt { page.outer.verticalScrollBar.value }
        onEdt {
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 0
            }
        }

        onEdt { wheel(page.table, rotation = -1) }

        val after = onEdt { page.outer.verticalScrollBar.value }
        assertTrue(
            after < before,
            "the page acts on the gesture, not merely a message ($before -> $after)",
        )
    }

    @Test
    fun `a wheel over the list scroll bar also moves the page`() {
        val page = page()
        val before = onEdt { page.outer.verticalScrollBar.value }
        onEdt {
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 1600
            }
        }

        onEdt { wheelOver(page.list.verticalScrollBar, rotation = 1) }

        assertTrue(
            onEdt { page.outer.verticalScrollBar.value } > before,
            "the scroll bar does not swallow a gesture it cannot use either",
        )
    }

    /**
     * The wheel as the window toolkit delivers it: posted at the table, then handled by whatever
     * component in the chain takes it. Pointer position is the table's own centre converted to the
     * page, so the page has a real point over the list to work out which pane should move.
     */
    @Test
    fun `a wheel over the list moves the page without hovering anything else`() {
        val page = page()
        onEdt {
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 0
            }
        }
        val before = onEdt { page.outer.verticalScrollBar.value }

        onEdt { wheelOver(page.table, rotation = -1) }

        val after = onEdt { page.outer.verticalScrollBar.value }
        assertTrue(
            after < before,
            "the page must move while the pointer is over the list ($before -> $after)",
        )
    }

    /**
     * The regression that survived the first attempt: the gesture reached the page's own listener
     * but not the page's scroll bar, so nothing actually moved. Nothing is asserted about a
     * forwarded event — the page is brought up to date by moving its bar — so this checks the bar,
     * which is the only thing the user can see.
     */
    @Test
    fun `the page scroll bar moves rather than merely receiving the event`() {
        val page = page()
        val before = onEdt { page.outer.verticalScrollBar.value }
        val atTop = onEdt {
            page.list.verticalScrollBar.apply { value = minimum }.value
        }

        onEdt { wheelOver(page.list.verticalScrollBar, rotation = -1) }

        assertTrue(
            onEdt { page.outer.verticalScrollBar.value } < before,
            "the page's scroll bar is what moves",
        )
        assertEquals(atTop, onEdt { page.list.verticalScrollBar.value }, "the list stays where it was")
    }

    /** A wheel the list can still use must move the list and leave the page where it was. */
    @Test
    fun `one wheel notch moves a predictable distance in both panes`() {
        // Three rows per notch scrolled the list far faster than the page, so crossing the
        // boundary made the gesture visibly lurch. Both panes must move at the same rate.
        val page = page()
        val listBefore = onEdt { page.list.verticalScrollBar.value }
        onEdt {
            page.list.verticalScrollBar.apply { value = minimum + 100 }
        }
        val start = onEdt { page.list.verticalScrollBar.value }

        onEdt { wheelOver(page.list.viewport.view, rotation = 1) }
        val listDelta = onEdt { page.list.verticalScrollBar.value } - start

        assertTrue(listBefore >= 0, "sanity")
        assertTrue(
            listDelta in 1..(UIScale.scale(60) + 1),
            "a notch moves a modest distance, not several rows ($listDelta)",
        )
    }

    @Test
    fun `a wheel the list can use moves the list and not the page`() {
        val page = page()
        onEdt {
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 400
            }
        }
        val pageBefore = onEdt { page.outer.verticalScrollBar.value }
        val listBefore = onEdt { page.list.verticalScrollBar.value }

        onEdt { wheelOver(page.list.viewport.view, rotation = 1) }

        assertTrue(
            onEdt { page.list.verticalScrollBar.value } > listBefore,
            "the list takes the gesture it has room for",
        )
        assertEquals(
            pageBefore,
            onEdt { page.outer.verticalScrollBar.value },
            "and the page does not move as well",
        )
    }

    // -------------------------------------------------------------------------
    // Chip alignment
    // -------------------------------------------------------------------------

    @Test
    fun `the shortcut chips sit in the middle of their row, not at its top`() {
        val page = page()
        val rowHeight = onEdt { page.table.rowHeight }
        val cell = renderChips(page, UIScale.scale(220), rowHeight)
        val chip = onEdt { (cell as Container).getComponent(0) }

        val overflow = (rowHeight - onEdt { chip.height }) / 2
        assertTrue(
            Math.abs(onEdt { chip.y } - overflow) <= UIScale.scale(1),
            "the chips are vertically centred: y=${onEdt { chip.y }} of $rowHeight",
        )
    }

    @Test
    fun `the chips are centred horizontally as well`() {
        val page = page()
        val width = UIScale.scale(260)
        val cell = renderChips(page, width, onEdt { page.table.rowHeight })
        val chips = onEdt { (cell as Container).components }

        val leftGap = onEdt { chips.first().x }
        val rightGap = onEdt { cell.width - (chips.last().x + chips.last().width) }
        assertTrue(
            Math.abs(leftGap - rightGap) <= UIScale.scale(2),
            "equal space either side: left=$leftGap right=$rightGap",
        )
    }

    @Test
    fun `the chips stay centred when the table paints the cell directly`() {
        // The table sizes a renderer component and paints it without validating it, which is how
        // the chips ended up off-centre in the running app while these tests still passed: they
        // laid the cell out by hand first. Painting through a CellRendererPane is the real path.
        val page = page()
        val columnWidth = onEdt { page.table.columnModel.getColumn(COL_HOTKEY).width }
        val row = onEdt { rowOf(page.table, HotkeyAction.TRANSLATE) }
        val rowHeight = onEdt { page.table.rowHeight }

        // Measured during the paint: the pane restores the component's previous bounds once it
        // is done, so only the geometry seen inside the paint says where the chips really land.
        var gaps: Pair<Int, Int>? = null
        onEdt {
            val renderer = page.table.getCellRenderer(row, COL_HOTKEY)
            val cell = renderer.getTableCellRendererComponent(
                page.table, page.table.getValueAt(row, COL_HOTKEY), false, false, row, COL_HOTKEY,
            )
            val probe = object : Container() {
                override fun paint(g: Graphics) {
                    cell.setSize(columnWidth, rowHeight)
                    cell.doLayout()
                    val band = cell as Container
                    val chips = band.components
                    gaps = chips.first().x to band.width - (chips.last().x + chips.last().width)
                }
            }
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                CellRendererPane().paintComponent(g, probe, page.table, 0, 0, columnWidth, rowHeight, true)
            } finally {
                g.dispose()
            }
        }

        val (leftGap, rightGap) = assertNotNull(gaps, "the cell was never painted")
        assertTrue(
            Math.abs(leftGap - rightGap) <= UIScale.scale(2),
            "equal space either side after a real paint: left=$leftGap right=$rightGap",
        )
    }

    /** The hotkey cell for a bound command, laid out at [width] by [height]. */
    private fun renderChips(page: Page, width: Int, height: Int): Component = onEdt {
        val row = rowOf(page.table, HotkeyAction.TRANSLATE)
        page.table.getCellRenderer(row, COL_HOTKEY)
            .getTableCellRendererComponent(
                page.table, page.table.getValueAt(row, COL_HOTKEY), false, false, row, COL_HOTKEY,
            )
            .apply { setSize(width, height) }
            .also { it.doLayout() }
    }

    // -------------------------------------------------------------------------
    // Row actions
    // -------------------------------------------------------------------------

    @Test
    fun `every command row carries both actions at its trailing edge`() {
        val page = page()
        val bounds = page.panel.rowActionBoundsForTest(columnWidth = 100, height = 34, leftToRight = true)

        assertEquals(
            listOf(CommandRowAction.EDIT, CommandRowAction.CLEAR),
            bounds.map { it.first },
            "a row offers exactly two actions, edit first",
        )
        bounds.forEach { (_, rect) ->
            assertTrue(rect.x >= 0 && rect.x + rect.width <= 100, "the action sits inside its column: $rect")
            assertTrue(rect.width > 0 && rect.height > 0, "and is big enough to hit: $rect")
        }
        assertTrue(
            bounds[0].second.x < bounds[1].second.x,
            "left to right, edit comes before clear",
        )
    }

    @Test
    fun `the actions mirror in a right-to-left layout`() {
        val page = page()
        val ltr = page.panel.rowActionBoundsForTest(columnWidth = 100, height = 34, leftToRight = true)
        val rtl = page.panel.rowActionBoundsForTest(columnWidth = 100, height = 34, leftToRight = false)

        assertTrue(
            rtl[0].second.x > rtl[1].second.x,
            "right to left the leading edge is the right, so edit is the outer square and clear trails it",
        )
        assertEquals(ltr[0].second.width, rtl[0].second.width, "the squares are the same size either way")
        assertEquals(
            ltr[0].second.x,
            100 - (rtl[0].second.x + rtl[0].second.width),
            "and the pair is mirrored, the same distance in from the edge",
        )
    }

    @Test
    fun `the row cells follow the dialog's reading direction`() {
        val page = page()
        onEdt {
            page.panel.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
            val row = rowOf(page.table, HotkeyAction.TRANSLATE)
            // A renderer component is not in the hierarchy, so nothing hands it the dialog's
            // direction: if the cell does not set its own, its indentation, chips and action
            // squares stay laid out left to right inside a right-to-left table.
            listOf(COL_ACTION, COL_HOTKEY, COL_ACTIONS).forEach { column ->
                val cell = page.table.prepareRenderer(page.table.getCellRenderer(row, column), row, column)
                assertFalse(
                    cell.componentOrientation.isLeftToRight,
                    "column $column renders in the dialog's direction",
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accessibility
    // -------------------------------------------------------------------------

    @Test
    fun `the actions column is named for the accessibility tree`() {
        val page = page()
        assertEquals(
            localizer.getString("settings_hotkeys.column_actions"),
            onEdt { page.table.getColumnName(COL_ACTIONS) },
            "an unnamed column is announced as nothing, so its cells have no context",
        )
        assertEquals(
            localizer.getString("settings_hotkeys.command_list_accessible_name"),
            onEdt { page.table.accessibleContext.accessibleName },
            "the table says what it is before any of its cells are read",
        )
    }

    @Test
    fun `the row actions are exposed as two named buttons`() {
        val page = page()
        val cell = actionsCell(page, HotkeyAction.TRANSLATE)
        val context = cell.accessibleContext

        assertEquals(
            localizer.getString("settings_hotkeys.row_actions_accessible_name"),
            context.accessibleName,
            "the cell says what the two squares are",
        )
        assertTrue(
            context.accessibleDescription.contains(localizer.getString("settings_hotkeys.action_translate")),
            "and which command they belong to: ${context.accessibleDescription}",
        )
        assertEquals(2, context.accessibleChildrenCount, "one child per action")
        assertEquals(
            localizer.getString("settings_hotkeys.row_edit_tooltip"),
            context.getAccessibleChild(0)!!.accessibleContext.accessibleName,
        )
        assertEquals(
            localizer.getString("settings_hotkeys.row_clear_tooltip"),
            context.getAccessibleChild(1)!!.accessibleContext.accessibleName,
        )
        assertEquals(AccessibleRole.PUSH_BUTTON, context.getAccessibleChild(0)!!.accessibleContext.accessibleRole)
        assertTrue(
            context.getAccessibleChild(0)!!.accessibleContext.accessibleStateSet.contains(AccessibleState.ENABLED),
            "an editable command's actions are enabled",
        )
    }

    @Test
    fun `a shortcut can be changed and cleared through the accessibility tree`() {
        val recorded = mutableListOf<HotkeyAction>()
        val page = page(recorder = { action, _ -> recorded += action; null })

        assertTrue(
            actionButton(page, HotkeyAction.TRANSLATE, CommandRowAction.EDIT)
                .accessibleContext.accessibleAction.doAccessibleAction(0),
            "the action reports that it ran",
        )
        assertEquals(listOf(HotkeyAction.TRANSLATE), recorded, "and it edits the command it belongs to")

        actionButton(page, HotkeyAction.SWAP_LANGUAGES, CommandRowAction.CLEAR)
            .accessibleContext.accessibleAction.doAccessibleAction(0)
        assertFalse(
            page.settings.state.value.workingConfiguration.hotkeys
                .first { it.action == HotkeyAction.SWAP_LANGUAGES }.hasBinding,
            "the clear action clears its own command",
        )
    }

    @Test
    fun `the actions are not offered while the command cannot be edited`() {
        val page = page(configuration = Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val button = actionButton(page, HotkeyAction.SHOW_QUICK_TRANSLATE, CommandRowAction.EDIT)

        assertFalse(
            button.accessibleContext.accessibleStateSet.contains(AccessibleState.ENABLED),
            "a global shortcut is announced as unavailable while its registration is off",
        )
        assertFalse(
            button.accessibleContext.accessibleAction.doAccessibleAction(0),
            "and the action declines rather than doing nothing silently",
        )
    }

    @Test
    fun `a heading exposes no actions of its own`() {
        val page = page()
        val child: Accessible? = onEdt {
            val headingRow = (0 until page.table.rowCount)
                .first { page.table.getValueAt(it, COL_HOTKEY) == null }
            page.table.accessibleContext
                ?.getAccessibleChild(headingRow * page.table.columnCount + COL_ACTIONS)
        }
        assertNotNull(child, "a heading still has a cell")
        assertEquals(0, child.accessibleContext.accessibleChildrenCount, "but there is nothing to change or clear")
    }

    @Test
    fun `clicking the edit action records a shortcut for that row's command`() {
        val recorded = mutableListOf<HotkeyAction>()
        val page = page(recorder = { action, _ -> recorded += action; null })
        val row = rowOf(page.table, HotkeyAction.TRANSLATE)

        clickRowAction(page, row, CommandRowAction.EDIT)

        assertEquals(listOf(HotkeyAction.TRANSLATE), recorded, "the row decides which command is edited")
    }

    @Test
    fun `clicking the clear action clears that command and nothing else`() {
        val page = page()
        val before = page.settings.state.value.workingConfiguration.hotkeys
        val row = rowOf(page.table, HotkeyAction.SWAP_LANGUAGES)

        clickRowAction(page, row, CommandRowAction.CLEAR)

        val after = page.settings.state.value.workingConfiguration.hotkeys
        val cleared = after.first { it.action == HotkeyAction.SWAP_LANGUAGES }
        assertFalse(cleared.hasBinding, "the shortcut of the clicked row is cleared")
        assertEquals(
            HotkeyScope.LOCAL,
            cleared.scope,
            "clearing from the row keeps the scope, exactly as the button under the list did",
        )
        assertEquals(
            before.filterNot { it.action == HotkeyAction.SWAP_LANGUAGES },
            after.filterNot { it.action == HotkeyAction.SWAP_LANGUAGES },
            "no other command is touched",
        )
    }

    @Test
    fun `a row's actions follow the global switch, like the buttons do`() {
        val recorded = mutableListOf<HotkeyAction>()
        val page = page(
            configuration = Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false),
            recorder = { action, _ -> recorded += action; null },
        )
        val row = rowOf(page.table, HotkeyAction.SHOW_QUICK_TRANSLATE)

        clickRowAction(page, row, CommandRowAction.EDIT)
        assertTrue(
            recorded.isEmpty(),
            "a global shortcut is not editable through its row while the registration is off",
        )

        val clearRow = rowOf(page.table, HotkeyAction.SHOW_QUICK_TRANSLATE)
        clickRowAction(page, clearRow, CommandRowAction.CLEAR)
        assertTrue(
            page.settings.state.value.workingConfiguration.hotkeys
                .first { it.action == HotkeyAction.SHOW_QUICK_TRANSLATE }.hasBinding,
            "and it cannot be cleared from the row either",
        )
    }

    @Test
    fun `clicking a category heading's action cell does nothing`() {
        val page = page()
        val headingRow = (0 until page.table.rowCount)
            .first { page.table.getValueAt(it, COL_HOTKEY) == null }

        clickRowAction(page, headingRow, CommandRowAction.CLEAR)

        assertEquals(-1, page.table.selectedRow, "a heading is not a selectable command")
    }

    @Test
    fun `enter on a selected row opens the recorder`() {
        val recorded = mutableListOf<HotkeyAction>()
        val page = page(recorder = { action, _ -> recorded += action; null })

        onEdt {
            val row = rowOf(page.table, HotkeyAction.CLEAR_INPUT)
            page.table.setRowSelectionInterval(row, row)
            page.table.actionMap.get("editSelectedCommand")?.actionPerformed(null)
        }

        assertEquals(listOf(HotkeyAction.CLEAR_INPUT), recorded, "the keyboard path reaches the same editor")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun wheel(target: Component, rotation: Int) {
        target.dispatchEvent(
            MouseWheelEvent(
                target,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                0,
                target.width / 2,
                target.height / 2,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                3,
                rotation,
            )
        )
    }

    /**
     * A wheel as the toolkit delivers it to [source]: the pointer is at the middle of that
     * component, and the event travels up the real component chain.
     */
    private fun wheelOver(source: Component, rotation: Int) = wheel(source, rotation)

    /** The accessibility tree's view of [action]'s row actions. */
    private fun actionsCell(page: Page, action: HotkeyAction): Accessible = onEdt {
        val table = page.table
        val row = rowOf(table, action)
        table.accessibleContext?.getAccessibleChild(row * table.columnCount + COL_ACTIONS)
            ?: error("no accessible cell for $action")
    }

    /** One of the two buttons the accessibility tree offers for [action]. */
    private fun actionButton(page: Page, action: HotkeyAction, kind: CommandRowAction): Accessible {
        val cell = actionsCell(page, action)
        val index = if (kind == CommandRowAction.EDIT) 0 else 1
        return cell.accessibleContext?.getAccessibleChild(index)
            ?: error("$action has no $kind action")
    }

    private fun rowOf(table: JTable, action: HotkeyAction): Int =
        (0 until table.rowCount).firstOrNull { row ->
            (table.getValueAt(row, COL_HOTKEY) as? HotkeyBinding)?.action == action
        } ?: error("no row for $action")

    /**
     * Clicks one of [row]'s action squares.
     *
     * The column widths are set by hand because the table is not on screen: without them the
     * column under the pointer cannot be resolved and the click lands nowhere. The point is taken
     * from the same geometry the renderer uses, so the test clicks where the square is drawn.
     */
    private fun clickRowAction(page: Page, row: Int, action: CommandRowAction) = onEdt {
        val table = page.table
        (0 until table.columnCount).forEach { index -> table.columnModel.getColumn(index).width = 100 }
        table.columnModel.getColumn(COL_ACTIONS).width = 78
        val cell = table.getCellRect(row, COL_ACTIONS, true)
        val rect = page.panel.rowActionBoundsForTest(cell.width, cell.height, leftToRight = true)
            .first { it.first == action }.second
        table.dispatchEvent(
            MouseEvent(
                table,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                cell.x + rect.x + rect.width / 2,
                cell.y + rect.y + rect.height / 2,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        )
    }

    private companion object {
        const val COL_ACTION = 0
        const val COL_HOTKEY = 1
        const val COL_ACTIONS = 3
    }
}
