package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.Density
import com.github.ahatem.qtranslate.core.settings.data.LayoutDisabledReason
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceWidget
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import com.github.ahatem.qtranslate.core.settings.data.withWindowLayout
import kotlinx.coroutines.runBlocking
import java.awt.ComponentOrientation
import java.io.File
import java.nio.file.Files
import javax.swing.AbstractButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The region menu: what it holds for each region and state, why a control is disabled, and what each choice writes. */
class RegionMenuTest {

    private val reasons = mapOf(
        LayoutDisabledReason.COMPARISON_OWNS_SERVICES to "Not shown in comparison",
        LayoutDisabledReason.SELECTOR_NEEDS_OWN_ROW to "Always a row of its own",
        LayoutDisabledReason.APPEARANCE_NEEDS_STRIP to "Only the strip has labels",
    )

    private val strings = RegionMenuStrings(
        regionName = { it.name.lowercase() },
        moveTo = "Move to",
        slotName = { it.name.lowercase() },
        hide = { "Hide ${it.name.lowercase()}" },
        shareRow = "Share row",
        selector = "Selector",
        widgetName = { it.name.lowercase() },
        stripStyle = "Strip style",
        appearanceName = { it.name.lowercase() },
        density = "Density",
        densityName = { it.name.lowercase() },
        saveProfile = "Save layout as profile...",
        reason = { reasons.getValue(it) },
    )

    private val changes = mutableListOf<WindowLayout>()

    private fun menu(
        region: WindowRegion,
        layout: WindowLayout = WindowLayout.DEFAULT,
        effectivePanes: PaneLayout = layout.panes,
        save: (() -> Unit)? = null,
    ) = RegionMenu(region, layout, strings, { changes += it }, effectivePanes, save)

    private fun JComponent.isChoice() = this is JMenuItem && name !in setOf(RegionMenu.HEADING, RegionMenu.REASON)

    private fun List<JComponent>.named(name: String) = filterIsInstance<JMenuItem>().single { it.name == name }

    private fun List<JComponent>.names() = filter { it !is JSeparator }.map { (it as JMenuItem).name }

    // ---- Structure ----

    @Test
    fun `history and languages offer position, share row and density`() {
        listOf(WindowRegion.HISTORY, WindowRegion.LANGUAGES).forEach { region ->
            val items = menu(region).items()
            assertEquals(
                listOf(
                    "heading", "slot:TOP", "slot:ABOVE_RESULTS", "slot:BOTTOM", "slot:HIDDEN",
                    "share_rows", "heading", "density:COMFORTABLE", "density:COMPACT",
                ),
                items.names(),
                "$region",
            )
            assertEquals("Move to", (items.first() as JMenuItem).text)
            assertTrue(items.filter { it.isChoice() }.all { it.isEnabled }, "nothing is unavailable for $region")
            assertTrue(items.names().none { it == RegionMenu.REASON }, "no reason is shown while nothing is disabled")
            assertTrue(items.names().none { it?.startsWith("widget") == true || it?.startsWith("appearance") == true })
        }
    }

    @Test
    fun `the service selector adds the widget and the strip style`() {
        val items = menu(WindowRegion.SERVICES, WindowLayout.DEFAULT).items()
        val names = items.names()
        assertEquals("slot:TOP", names[1])
        assertTrue(names.containsAll(listOf("widget:PICKER", "widget:STRIP", "appearance:ICONS_ONLY",
            "appearance:ICONS_AND_TEXT", "appearance:TEXT_ONLY")))
        assertEquals("Hide services", items.named("slot:HIDDEN").text, "Hide names the region it hides")
    }

    @Test
    fun `the current choices are the ones checked`() {
        val layout = WindowLayout(
            history = RegionSlot.BOTTOM, services = RegionSlot.TOP, serviceWidget = ServiceWidget.STRIP,
            stripAppearance = ServiceSelectorAppearance.TEXT_ONLY, shareRows = true, density = Density.COMPACT,
        )
        val history = menu(WindowRegion.HISTORY, layout).items()
        assertTrue(history.named("slot:BOTTOM").isSelected)
        assertFalse(history.named("slot:TOP").isSelected)
        assertTrue(history.named("share_rows").isSelected)
        assertTrue(history.named("density:COMPACT").isSelected)
        assertFalse(history.named("density:COMFORTABLE").isSelected)

        val services = menu(WindowRegion.SERVICES, layout).items()
        assertTrue(services.named("slot:TOP").isSelected)
        assertTrue(services.named("widget:STRIP").isSelected)
        assertTrue(services.named("appearance:TEXT_ONLY").isSelected)
    }

    @Test
    fun `Hide is one item per region and names where the commands go`() {
        val real = realStrings()
        val hide = WindowRegion.entries.associateWith { real.hide(it) }
        assertEquals(3, hide.values.toSet().size, "a different sentence for each region")
        assertTrue(hide.getValue(WindowRegion.HISTORY).contains("input pane"))
        assertTrue(hide.getValue(WindowRegion.LANGUAGES).contains("input pane"))
        assertTrue(hide.getValue(WindowRegion.SERVICES).contains("input pane"))
    }

    @Test
    fun `save layout as profile appears only when a host can save`() {
        assertTrue(menu(WindowRegion.HISTORY).items().none { it.name == RegionMenu.SAVE_PROFILE })
        var saved = 0
        val items = menu(WindowRegion.HISTORY, save = { saved++ }).items()
        assertEquals(RegionMenu.SAVE_PROFILE, items.last().name)
        (items.last() as JMenuItem).doClick(0)
        assertEquals(1, saved)
        assertEquals(emptyList(), changes, "saving a profile does not change the window")
    }

    // ---- Reasons ----

    @Test
    fun `under comparison every service control is disabled with its reason shown and in the tooltip`() {
        val layout = WindowLayout(panes = PaneLayout.COMPARISON, serviceWidget = ServiceWidget.STRIP)
        val items = menu(WindowRegion.SERVICES, layout).items()
        val choices = items.filter { it.isChoice() }.filterIsInstance<JMenuItem>()
            .filter { it.name !in setOf(RegionMenu.SAVE_PROFILE) && !it.name.startsWith("density") }
        assertTrue(choices.isNotEmpty())
        choices.forEach {
            assertFalse(it.isEnabled, "${it.name} is disabled under comparison")
            assertEquals("Not shown in comparison", it.toolTipText, "${it.name} says why")
        }
        val captions = items.filterIsInstance<JMenuItem>().filter { it.name == RegionMenu.REASON }
        assertEquals(listOf("Not shown in comparison"), captions.map { it.text }, "the reason is also written out, once")
        assertFalse(captions.single().isEnabled)
        assertTrue(items.named("density:COMFORTABLE").isEnabled, "density does not depend on the board")
    }

    @Test
    fun `a requested comparison that is not eligible leaves the controls enabled`() {
        val layout = WindowLayout(panes = PaneLayout.COMPARISON)
        val items = menu(WindowRegion.SERVICES, layout, effectivePanes = PaneLayout.STACKED).items()
        assertTrue(items.filter { it.isChoice() && (it.name.startsWith("slot") || it.name.startsWith("widget")) }.all { it.isEnabled })
    }

    @Test
    fun `the picker disables the strip style and the selector never shares a row`() {
        val items = menu(WindowRegion.SERVICES, WindowLayout(serviceWidget = ServiceWidget.PICKER)).items()
        ServiceSelectorAppearance.entries.forEach {
            val item = items.named("appearance:$it")
            assertFalse(item.isEnabled)
            assertEquals("Only the strip has labels", item.toolTipText)
        }
        val share = items.named("share_rows")
        assertFalse(share.isEnabled)
        assertEquals("Always a row of its own", share.toolTipText)
        val captions = items.filterIsInstance<JMenuItem>().filter { it.name == RegionMenu.REASON }.map { it.text }
        assertEquals(setOf("Only the strip has labels", "Always a row of its own"), captions.toSet())
        assertEquals(captions.size, captions.toSet().size, "each reason is written once")
        assertTrue(items.named("widget:STRIP").isEnabled)
    }

    @Test
    fun `the strip style is available once the strip is chosen`() {
        val items = menu(WindowRegion.SERVICES, WindowLayout(serviceWidget = ServiceWidget.STRIP)).items()
        ServiceSelectorAppearance.entries.forEach { assertTrue(items.named("appearance:$it").isEnabled) }
        assertNull(items.named("appearance:ICONS_ONLY").toolTipText)
    }

    // ---- What each choice writes ----

    @Test
    fun `every choice yields one whole layout that is stored through the single write path`() {
        val start = WindowLayout(serviceWidget = ServiceWidget.STRIP)
        val expected = mutableMapOf<String, WindowLayout>()
        RegionSlot.entries.forEach { expected["slot:$it"] = start.withSlot(WindowRegion.SERVICES, it) }
        expected["widget:PICKER"] = start.copy(serviceWidget = ServiceWidget.PICKER)
        ServiceSelectorAppearance.entries.forEach { expected["appearance:$it"] = start.copy(stripAppearance = it) }
        expected["density:COMPACT"] = start.copy(density = Density.COMPACT)

        expected.forEach { (name, layout) ->
            changes.clear()
            val items = menu(WindowRegion.SERVICES, start).items()
            val item = items.named(name)
            if (item.isSelected) {
                item.doClick(0)
                assertEquals(emptyList(), changes, "choosing what is already chosen writes nothing ($name)")
            } else {
                item.doClick(0)
                assertEquals(listOf(layout), changes, name)
                assertEquals(layout, Configuration.DEFAULT.withWindowLayout(changes.single()).windowLayout)
            }
        }
    }

    @Test
    fun `history and languages choices change only their own slot and the shared values`() {
        WindowRegion.entries.filter { it != WindowRegion.SERVICES }.forEach { region ->
            RegionSlot.entries.filter { it != WindowLayout.DEFAULT.slotOf(region) }.forEach { slot ->
                changes.clear()
                menu(region).items().named("slot:$slot").doClick(0)
                assertEquals(listOf(WindowLayout.DEFAULT.withSlot(region, slot)), changes)
            }
            changes.clear()
            val share = menu(region).items().filterIsInstance<JCheckBoxMenuItem>().single()
            share.doClick(0)
            assertEquals(listOf(WindowLayout.DEFAULT.copy(shareRows = true)), changes)
        }
    }

    @Test
    fun `the popup and the submenu hold the same entries`() {
        val items = menu(WindowRegion.SERVICES).items()
        val popup = menu(WindowRegion.SERVICES).popupMenu()
        val submenu: JMenu = menu(WindowRegion.SERVICES).menu()
        assertEquals(items.names(), popup.components.filterIsInstance<JMenuItem>().map { it.name })
        assertEquals(items.names(), submenu.menuComponents.filterIsInstance<JMenuItem>().map { it.name })
        assertEquals("services", submenu.text)
        assertTrue(popup.components.filterIsInstance<AbstractButton>().filterIsInstance<JRadioButtonMenuItem>().isNotEmpty())
    }

    @Test
    fun `the popup reads in the direction it is built for`() {
        val rtl = menu(WindowRegion.HISTORY).popupMenu(ComponentOrientation.RIGHT_TO_LEFT)
        assertFalse(rtl.componentOrientation.isLeftToRight)
        rtl.components.filterIsInstance<JMenuItem>().forEach {
            assertFalse(it.componentOrientation.isLeftToRight, "${it.name} reads right to left")
        }
        assertTrue(menu(WindowRegion.HISTORY).popupMenu(ComponentOrientation.LEFT_TO_RIGHT).componentOrientation.isLeftToRight)
    }

    // ---- Words ----

    @Test
    fun `every entry has real words, in English and in a right-to-left language`() {
        listOf(false, true).forEach { rtl ->
            val real = realStrings(rtl)
            WindowRegion.entries.forEach { region ->
                listOf(
                    real.regionName(region), real.hide(region),
                ).forEach { assertTrue(it.isNotBlank() && !it.contains('—'), it) }
            }
            LayoutDisabledReason.entries.forEach { assertTrue(real.reason(it).isNotBlank()) }
            RegionSlot.entries.forEach { assertTrue(real.slotName(it).isNotBlank()) }
            Density.entries.forEach { assertTrue(real.densityName(it).isNotBlank()) }
            listOf(real.moveTo, real.shareRow, real.selector, real.stripStyle, real.density, real.saveProfile).forEach {
                assertTrue(it.isNotBlank() && !it.startsWith("main_window_main_menu"), "a key was shown instead of its text: $it")
            }
            assertNotEquals(real.slotName(RegionSlot.TOP), real.slotName(RegionSlot.BOTTOM))
        }
    }

    private fun realStrings(rtl: Boolean = false): RegionMenuStrings {
        val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
        val localizer = LocalizationManager(Files.createTempDirectory("qtranslate-region").toFile(), LanguageTomlParser(), logger)
        if (rtl) {
            File(localizer.languagesDirectory, "ar-SA.toml").writeText(
                "[meta]\nname = \"Test\"\nnative_name = \"Test\"\nlocale = \"ar-SA\"\nrtl = true\n"
            )
            runBlocking { localizer.loadLanguage(LanguageCode("ar-SA")) }
        }
        return localizer.regionMenuStrings()
    }
}
