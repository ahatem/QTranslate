package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.Density
import com.github.ahatem.qtranslate.core.settings.data.LayoutDisabledReason
import com.github.ahatem.qtranslate.core.settings.data.LayoutProfile
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import com.github.ahatem.qtranslate.core.settings.data.withLayoutProfiles
import com.github.ahatem.qtranslate.core.settings.data.withWindowLayout
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The View menu's layout entries: panes, one submenu per region, the status bar and the saved layouts. */
class LayoutMenuTest {

    private val compact = WindowLayout(history = RegionSlot.TOP, languages = RegionSlot.TOP, shareRows = true)
    private val focus = WindowLayout(services = RegionSlot.HIDDEN, showStatusBar = false, density = Density.COMPACT)

    private val applied = mutableListOf<WindowLayout>()
    private var saves = 0
    private var manages = 0
    private var statusBar: Boolean? = null

    private val regionStrings = RegionMenuStrings(
        regionName = { "region ${it.name}" }, moveTo = "Move to", slotName = { it.name }, hide = { "Hide ${it.name}" },
        shareRow = "Share row", selector = "Selector", widgetName = { it.name }, stripStyle = "Strip style",
        appearanceName = { it.name }, density = "Density", densityName = { it.name },
        saveProfile = "Save layout as profile...", reason = { "reason ${it.name}" },
    )

    private fun options(config: Configuration, comparisonAvailable: Boolean = true, layoutMenu: Boolean = true): JMenu {
        val popup = MainMenuPopup(
            config = config,
            actions = MenuActions(
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onToggleStatusBar = { statusBar = it },
                onOpenServicesSettings = {},
                onChangeLayout = { applied += it },
                onSaveLayoutProfile = { saves++ },
                onManageLayouts = { manages++ },
            ),
            strings = strings(comparisonAvailable, layoutMenu),
            availableLayouts = listOf(
                LayoutPresetInfo("classic", "Classic"),
                LayoutPresetInfo("side_by_side", "Side By Side"),
                LayoutPresetInfo("comparison", "Comparison"),
            ),
        )
        return popup.components.filterIsInstance<JMenu>().single { it.text == "Options" }
    }

    private fun strings(comparisonAvailable: Boolean, layoutMenu: Boolean) = MenuStrings(
        spellCheck = "Spell", instantTranslation = "Instant", extraOutput = "Extra", extraOutputNone = "None",
        extraOutputBackward = "Backward", extraOutputSummarize = "Summary", extraOutputRewrite = "Rewrite",
        viewOptions = "Options", dictionary = "Dictionary", isDictionaryPanelOpen = false, imageSearch = "Image",
        recognizeText = "OCR", history = "History", translateDocument = "Document", settings = "Settings",
        help = "Help", howToUse = "How", aboutQTranslate = "About", contactUs = "Contact",
        autoCheckForUpdates = "Auto", checkForUpdates = "Check", exit = "Exit", layoutPresets = "Layout Presets",
        layoutComparisonAvailable = comparisonAvailable, showStatusBar = "Show Status Bar",
        layoutMenu = if (layoutMenu) {
            LayoutMenuStrings(regionStrings, "Saved layouts", "Save layout as profile...", "Manage layouts...")
        } else {
            null
        },
    )

    private fun JMenu.items() = menuComponents.filterIsInstance<JMenuItem>()
    private fun JMenu.item(name: String) = items().single { it.name == name }
    private fun JMenu.region(region: WindowRegion) = items().filterIsInstance<JMenu>().single { it.name == "region:$region" }
    private fun JMenu.positions() = items().filter { it.name?.startsWith("slot:") == true }

    private fun profileItems(menu: JMenu) = menu.items().filterIsInstance<JRadioButtonMenuItem>()
        .filter { it.name?.startsWith(ViewOptionsMenu.PROFILE_PREFIX) == true }

    @Test
    fun `the menu offers panes, a submenu per region, the status bar, saved layouts and Manage`() {
        val menu = options(Configuration.DEFAULT.withLayoutProfiles(listOf(LayoutProfile("Work", compact))))
        assertEquals(
            listOf(
                "Layout Presets", "region HISTORY", "region LANGUAGES", "region SERVICES", "Show Status Bar",
                "Saved layouts", "Work", "Save layout as profile...", "Manage layouts...",
            ),
            menu.items().map { it.text },
        )
        WindowRegion.entries.forEach { region ->
            assertTrue(menu.region(region).items().any { it.name == "slot:TOP" }, "$region has its position choices")
        }
    }

    @Test
    fun `without layout strings the menu is the panes and the status bar only`() {
        val menu = options(Configuration.DEFAULT, layoutMenu = false)
        assertEquals(listOf("Layout Presets", "Show Status Bar"), menu.items().map { it.text })
    }

    @Test
    fun `a region submenu writes the whole layout it was built from`() {
        val menu = options(Configuration.DEFAULT.withWindowLayout(compact))
        menu.region(WindowRegion.LANGUAGES).item("slot:HIDDEN").doClick(0)
        assertEquals(listOf(compact.copy(languages = RegionSlot.HIDDEN)), applied)
    }

    @Test
    fun `under comparison the service submenu is disabled with its reason`() {
        val menu = options(Configuration.DEFAULT.withWindowLayout(WindowLayout(panes = PaneLayout.COMPARISON)))
        val position = menu.region(WindowRegion.SERVICES).positions()
        assertEquals(4, position.size)
        position.forEach {
            assertFalse(it.isEnabled)
            assertEquals("reason ${LayoutDisabledReason.COMPARISON_OWNS_SERVICES.name}", it.toolTipText)
        }
        assertTrue(menu.region(WindowRegion.HISTORY).positions().all { it.isEnabled })
    }

    @Test
    fun `an ineligible comparison does not disable the service submenu`() {
        val config = Configuration.DEFAULT.withWindowLayout(WindowLayout(panes = PaneLayout.COMPARISON))
        val services = options(config, comparisonAvailable = false).region(WindowRegion.SERVICES)
        assertTrue(services.positions().all { it.isEnabled })
    }

    @Test
    fun `the saved layout equal to the window is checked and any difference checks none`() {
        val profiles = listOf(LayoutProfile("Work", compact), LayoutProfile("Focus", focus))
        val config = Configuration.DEFAULT.withLayoutProfiles(profiles)

        val onCompact = profileItems(options(config.withWindowLayout(compact)))
        assertEquals(listOf("Work", "Focus"), onCompact.map { it.text })
        assertEquals(listOf(true, false), onCompact.map { it.isSelected })

        val onFocus = profileItems(options(config.withWindowLayout(focus)))
        assertEquals(listOf(false, true), onFocus.map { it.isSelected })

        val drifted = profileItems(options(config.withWindowLayout(compact.copy(density = Density.COMPACT))))
        assertEquals(listOf(false, false), drifted.map { it.isSelected }, "a window that differs in density matches none")
    }

    @Test
    fun `choosing a saved layout applies that layout and the other entries call their hosts`() {
        val profiles = listOf(LayoutProfile("Work", compact), LayoutProfile("Focus", focus))
        val menu = options(Configuration.DEFAULT.withLayoutProfiles(profiles))

        profileItems(menu).single { it.text == "Focus" }.doClick(0)
        assertEquals(listOf(focus), applied)
        assertEquals(focus, Configuration.DEFAULT.withWindowLayout(applied.single()).windowLayout)

        menu.item("save_profile").doClick(0)
        menu.item("manage_layouts").doClick(0)
        (menu.items().single { it.text == "Show Status Bar" } as JCheckBoxMenuItem).doClick(0)
        assertEquals(1, saves)
        assertEquals(1, manages)
        assertEquals(false, statusBar)
    }

    @Test
    fun `with no saved layouts there is no heading and Manage is still offered`() {
        val menu = options(Configuration.DEFAULT)
        assertEquals(
            listOf(
                "Layout Presets", "region HISTORY", "region LANGUAGES", "region SERVICES", "Show Status Bar",
                "Save layout as profile...", "Manage layouts...",
            ),
            menu.items().map { it.text },
        )
    }
}
