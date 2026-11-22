package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.LayoutProfile
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import com.github.ahatem.qtranslate.core.settings.data.withLayoutProfiles
import com.github.ahatem.qtranslate.core.settings.data.withWindowLayout
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.ui.swing.main.layout.RegionRow
import com.github.ahatem.qtranslate.ui.swing.main.menus.ProfilePrompts
import com.github.ahatem.qtranslate.ui.swing.main.menus.RegionMenu
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import kotlinx.coroutines.runBlocking
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import java.io.File
import java.nio.file.Files
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The window's own chrome rows are where it is arranged: each row has the popup, every control in
 * it shares that popup, the text panes keep their own menus, and a choice reaches the settings as
 * one whole layout through the single write path.
 */
class RegionRowMenusTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val iconManager = TestIcons.iconManager()
    private val writes = mutableListOf<SettingsIntent>()

    private fun localizer(rtl: Boolean = false): LocalizationManager {
        val localizer = LocalizationManager(Files.createTempDirectory("qtranslate-rows").toFile(), LanguageTomlParser(), logger)
        if (rtl) {
            File(localizer.languagesDirectory, "ar-SA.toml").writeText(
                "[meta]\nname = \"Test\"\nnative_name = \"Test\"\nlocale = \"ar-SA\"\nrtl = true\n"
            )
            runBlocking { localizer.loadLanguage(LanguageCode("ar-SA")) }
            assertTrue(localizer.isRtl)
        }
        return localizer
    }

    private fun view(localizer: LocalizationManager, prompts: ProfilePrompts? = null): MainContentView = onEdt {
        MainContentView(
            iconManager = iconManager,
            localizer = localizer,
            dispatch = { _: MainIntent -> },
            dispatchSettings = { writes += it },
            onOpenSnippingTool = {},
            onOpenOcrSettings = {},
            onOpenDocumentTranslation = {},
            onNotificationsClicked = {},
            onConfigureService = {},
            onOpenServiceSettings = {},
            onEnsureLookupDockRoom = {},
            onOpenImageSource = {},
            profilePrompts = prompts,
        )
    }

    private val eligible = MainState(
        availableServices = listOf(
            ServiceInfo("google", "Google", null, ServiceRole.TRANSLATOR),
            ServiceInfo("bing", "Bing", null, ServiceRole.TRANSLATOR),
        )
    )

    private val base = Configuration.DEFAULT.copy(
        servicePresets = listOf(
            ServicePreset(
                id = "preset", name = "preset",
                selectedServices = mapOf(ServiceRole.TRANSLATOR to "google"),
                comparisonTranslatorIds = listOf("bing"),
            )
        ),
        activeServicePresetId = "preset",
    )

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun render(view: MainContentView, config: Configuration) {
        onEdt { view.render(eligible, SettingsState(originalConfiguration = config, workingConfiguration = config)) }
        // The rebuild is queued with invokeLater; drain it, and anything it queues in turn.
        repeat(2) { onEdt { } }
    }

    private fun render(view: MainContentView, layout: WindowLayout) = render(view, base.withWindowLayout(layout))

    private fun descendants(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(descendants(it)) }
    }

    private fun popupOf(view: MainContentView, region: WindowRegion): JPopupMenu =
        view.rowMenus.rows.first { region in it.regions }.container.componentPopupMenu

    /** The settings write the last intent stands for, applied to [from]. */
    private fun lastWrite(from: Configuration): Configuration =
        (writes.last() as SettingsIntent.ToggleSetting).update(from)

    private fun names(popup: JPopupMenu) = popup.components.filterIsInstance<JMenuItem>().map { it.name }

    private val shared = WindowLayout(history = RegionSlot.TOP, languages = RegionSlot.TOP, shareRows = true)

    // ---- Hosts ----

    @Test
    fun `every row of every arrangement has the popup and every control in it inherits it`() {
        val layouts = listOf(
            WindowLayout.DEFAULT,
            shared,
            WindowLayout(history = RegionSlot.BOTTOM, services = RegionSlot.TOP),
            WindowLayout(panes = PaneLayout.SIDE_BY_SIDE),
            WindowLayout(panes = PaneLayout.COMPARISON),
        )
        layouts.forEach { layout ->
            val view = view(localizer())
            render(view, layout)
            val plan = layout.rowPlan(layout.effectivePanes(true)).values.flatten()
            val rows = view.rowMenus.rows
            assertEquals(plan, rows.map { it.regions }, "one popup per row of the plan for $layout")

            rows.forEach { row ->
                val popup = row.container.componentPopupMenu
                assertNotNull(popup, "${row.regions} has a popup")
                assertFalse(row.container.inheritsPopupMenu, "the row itself owns the popup")
                val inner = descendants(row.container).filterIsInstance<JComponent>().filter { it !== row.container }.toList()
                assertTrue(inner.isNotEmpty())
                inner.forEach {
                    assertSame(popup, it.componentPopupMenu, "${it.javaClass.simpleName} in ${row.regions} shares the row's popup")
                }
            }
        }
    }

    @Test
    fun `the text panes keep their own editor menus`() {
        val view = view(localizer())
        render(view, shared)
        val rowPopups = view.rowMenus.popups.toSet()
        val panes = descendants(view).filterIsInstance<JTextComponent>().toList()
        assertTrue(panes.isNotEmpty(), "the window has text panes")
        panes.forEach { pane ->
            assertFalse(pane.inheritsPopupMenu, "${pane.javaClass.simpleName} does not inherit")
            assertFalse(pane.componentPopupMenu in rowPopups, "${pane.javaClass.simpleName} keeps its own popup")
        }
        assertTrue(panes.any { it.componentPopupMenu != null }, "the editors have a context menu of their own")
    }

    @Test
    fun `a control with a popup of its own is left alone`() {
        val view = view(localizer())
        render(view, WindowLayout.DEFAULT)
        val row = view.rowMenus.rows.first { WindowRegion.HISTORY in it.regions }
        val own = JPopupMenu()
        val button = descendants(row.container).filterIsInstance<javax.swing.AbstractButton>().first()
        onEdt {
            button.inheritsPopupMenu = false
            button.componentPopupMenu = own
            view.rowMenus.refreshInheritance()
        }
        assertSame(own, button.componentPopupMenu)
        assertFalse(button.inheritsPopupMenu)
    }

    // ---- Content and effect ----

    @Test
    fun `a single-region row shows that region's menu from the current layout`() {
        val view = view(localizer())
        render(view, WindowLayout.DEFAULT)
        val popup = popupOf(view, WindowRegion.HISTORY) as com.github.ahatem.qtranslate.ui.swing.main.layout.RegionRowMenus.RowPopup
        onEdt { popup.refresh() }
        assertEquals(
            listOf(
                "heading", "slot:TOP", "slot:ABOVE_RESULTS", "slot:BOTTOM", "slot:HIDDEN", "share_rows",
                "heading", "density:COMFORTABLE", "density:COMPACT", RegionMenu.SAVE_PROFILE,
            ),
            names(popup),
        )

        // The popup follows the layout: it is built each time it opens.
        render(view, WindowLayout(history = RegionSlot.BOTTOM))
        onEdt { popup.refresh() }
        val checked = popup.components.filterIsInstance<JMenuItem>().filter { it.isSelected }.map { it.name }
        assertTrue("slot:BOTTOM" in checked, "the popup shows the layout the window has now: $checked")
    }

    @Test
    fun `a choice applies at once as one whole layout through the single write path`() {
        val view = view(localizer())
        render(view, WindowLayout.DEFAULT)
        val popup = popupOf(view, WindowRegion.HISTORY) as com.github.ahatem.qtranslate.ui.swing.main.layout.RegionRowMenus.RowPopup
        onEdt {
            popup.refresh()
            popup.components.filterIsInstance<JMenuItem>().single { it.name == "slot:HIDDEN" }.doClick(0)
        }
        assertEquals(1, writes.size)
        assertTrue(writes.single() is SettingsIntent.ToggleSetting, "the scoped save the View menu uses")
        assertEquals(
            WindowLayout.DEFAULT.copy(history = RegionSlot.HIDDEN),
            lastWrite(base.withWindowLayout(WindowLayout.DEFAULT)).windowLayout,
        )
    }

    @Test
    fun `a shared row lists one submenu per region and one save entry`() {
        val view = view(localizer())
        render(view, shared)
        val row = view.rowMenus.rows.single { it.regions.size == 2 }
        assertEquals(listOf(WindowRegion.HISTORY, WindowRegion.LANGUAGES), row.regions)
        val popup = row.container.componentPopupMenu as com.github.ahatem.qtranslate.ui.swing.main.layout.RegionRowMenus.RowPopup
        onEdt { popup.refresh() }
        assertEquals(listOf("region:HISTORY", "region:LANGUAGES", RegionMenu.SAVE_PROFILE), names(popup))
        val languages = popup.components.filterIsInstance<JMenu>().last()
        onEdt { languages.menuComponents.filterIsInstance<JMenuItem>().single { it.name == "slot:HIDDEN" }.doClick(0) }
        assertEquals(shared.copy(languages = RegionSlot.HIDDEN), lastWrite(base.withWindowLayout(shared)).windowLayout)
    }

    @Test
    fun `under comparison the service row is absent and history keeps its menu`() {
        val view = view(localizer())
        render(view, WindowLayout(panes = PaneLayout.COMPARISON))
        assertTrue(view.rowMenus.rows.none { WindowRegion.SERVICES in it.regions })
        assertNotNull(popupOf(view, WindowRegion.HISTORY))
    }

    @Test
    fun `the popup reads right to left in a right-to-left window`() {
        val rtl = view(localizer(rtl = true))
        render(rtl, WindowLayout.DEFAULT)
        val popup = popupOf(rtl, WindowRegion.HISTORY) as com.github.ahatem.qtranslate.ui.swing.main.layout.RegionRowMenus.RowPopup
        onEdt { popup.refresh() }
        assertEquals(ComponentOrientation.RIGHT_TO_LEFT.isLeftToRight, popup.componentOrientation.isLeftToRight)
        assertFalse(popup.componentOrientation.isLeftToRight)

        val ltr = view(localizer())
        render(ltr, WindowLayout.DEFAULT)
        val ltrPopup = popupOf(ltr, WindowRegion.HISTORY) as com.github.ahatem.qtranslate.ui.swing.main.layout.RegionRowMenus.RowPopup
        onEdt { ltrPopup.refresh() }
        assertTrue(ltrPopup.componentOrientation.isLeftToRight)
    }

    // ---- Saving a profile ----

    private class Prompts(var name: String?, var replace: Boolean = true) : ProfilePrompts {
        var replaceAsked: String? = null
        var full: Int? = null
        override fun askName() = name
        override fun confirmReplace(name: String): Boolean { replaceAsked = name; return replace }
        override fun showFull(max: Int) { full = max }
    }

    private fun clickSave(view: MainContentView) {
        val popup = popupOf(view, WindowRegion.HISTORY) as com.github.ahatem.qtranslate.ui.swing.main.layout.RegionRowMenus.RowPopup
        onEdt {
            popup.refresh()
            popup.components.filterIsInstance<JMenuItem>().single { it.name == RegionMenu.SAVE_PROFILE }.doClick(0)
        }
    }

    @Test
    fun `save layout as profile stores the window's layout under the trimmed name`() {
        val view = view(localizer(), Prompts("  Reading  "))
        render(view, shared)
        clickSave(view)
        val saved = lastWrite(base.withWindowLayout(shared))
        assertEquals(listOf(LayoutProfile("Reading", shared)), saved.layoutProfiles)
        assertEquals(shared, saved.windowLayout, "saving a profile does not change the window")
    }

    @Test
    fun `an empty name saves nothing`() {
        val view = view(localizer(), Prompts("   "))
        render(view, shared)
        clickSave(view)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a taken name asks before it replaces`() {
        val existing = base.withWindowLayout(shared).withLayoutProfiles(listOf(LayoutProfile("Reading", WindowLayout.DEFAULT)))
        val declining = Prompts("reading", replace = false)
        val view = view(localizer(), declining)
        render(view, existing)
        clickSave(view)
        assertEquals("Reading", declining.replaceAsked)
        assertTrue(writes.isEmpty(), "declining keeps the saved layout")

        declining.replace = true
        clickSave(view)
        assertEquals(listOf(LayoutProfile("reading", shared)), lastWrite(existing).layoutProfiles)
    }

    @Test
    fun `a full list says so and saves nothing for a new name`() {
        val full = (1..LayoutProfile.MAX_PROFILES).map { LayoutProfile("Layout $it", WindowLayout.DEFAULT) }
        val config = base.withWindowLayout(shared).withLayoutProfiles(full)
        val prompts = Prompts("One more")
        val view = view(localizer(), prompts)
        render(view, config)
        clickSave(view)
        assertEquals(LayoutProfile.MAX_PROFILES, prompts.full)
        assertTrue(writes.isEmpty())
        assertNull(prompts.replaceAsked)
    }
}
