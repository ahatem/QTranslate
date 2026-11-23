package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.Density
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.LayoutProfile
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowPreset
import com.github.ahatem.qtranslate.core.settings.data.effectivePaneLayout
import com.github.ahatem.qtranslate.core.settings.data.withLayoutProfiles
import com.github.ahatem.qtranslate.core.settings.data.withWindowLayout
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.main.menus.ProfileManagementPrompts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.AbstractButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Layout page is an outline of the window: it must draw what the model says, mark a starting
 * point only when it is really in effect, and change the draft only through the one write path.
 */
class LayoutPanelTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-layout-test").toFile(), LanguageTomlParser(), logger)
    }

    /** Scripted answers, and a record of what was asked. */
    private class Prompts(
        var name: String? = null,
        var rename: String? = null,
        var delete: Boolean = true,
    ) : ProfileManagementPrompts {
        val deleteAsked = mutableListOf<String>()
        val nameTaken = mutableListOf<String>()
        override fun askName() = name
        override fun confirmReplace(name: String) = true
        override fun showFull(max: Int) = Unit
        override fun askRename(current: String) = rename
        override fun confirmDelete(name: String): Boolean {
            deleteAsked += name
            return delete
        }

        override fun showNameTaken(name: String) {
            nameTaken += name
        }
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(config: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-layout-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, config)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun named(root: Container, name: String): List<Component> = descendants(root).filter { it.name == name }

    private class Fixture(val store: SettingsStore, val panel: LayoutPanel, val prompts: Prompts) {
        val draft get() = store.state.value.workingConfiguration
        val layout get() = draft.windowLayout
    }

    private fun fixture(config: Configuration = Configuration.DEFAULT, prompts: Prompts = Prompts()): Fixture {
        val store = store(config)
        val panel = onEdt {
            LayoutPanel(store, localizer, prompts = prompts).also { it.render(store.state.value) }
        }
        return Fixture(store, panel, prompts)
    }

    private fun Fixture.rerender() = onEdt { panel.render(store.state.value) }

    private fun Fixture.click(name: String, index: Int = 0) {
        onEdt { (named(panel, name)[index] as AbstractButton).doClick() }
        rerender()
    }

    /** The blocks the outline draws, read off the page's components. */
    private fun Fixture.drawn(): Set<OutlineBlock> =
        OutlineBlock.entries.filter { named(panel, LayoutOutline.nameOf(it)).isNotEmpty() }.toSet()

    private fun Fixture.expected(): Set<OutlineBlock> =
        outlinePlan(layout, draft.effectivePaneLayout(emptyList()), draft.extraOutputType).blocks.toSet()

    private fun Fixture.selectedCards(): List<WindowPreset> =
        WindowPreset.entries.filter { (named(panel, "card:$it").single() as AbstractButton).isSelected }

    @Test
    fun `the outline draws what the model says for every starting point and a saved layout`() {
        val saved = LayoutProfile("Wide", WindowLayout(panes = PaneLayout.SIDE_BY_SIDE, showStatusBar = false))
        val config = Configuration.DEFAULT
            .copy(extraOutputType = ExtraOutputType.entries.first { it != ExtraOutputType.None })
            .withLayoutProfiles(listOf(saved))
        val f = fixture(config)

        assertEquals(f.expected(), f.drawn(), "the default window")
        WindowPreset.entries.forEach { preset ->
            f.click("card:$preset")
            assertEquals(f.expected(), f.drawn(), "after the $preset starting point")
        }
        f.click(LayoutPanel.APPLY)
        assertEquals(saved.layout, f.layout)
        assertEquals(f.expected(), f.drawn(), "after applying a saved layout")
        assertFalse(OutlineBlock.STATUS_BAR in f.drawn(), "a window without a status bar has no such block")
        assertTrue(OutlineBlock.SECOND_RESULT in f.drawn(), "a chosen second result type has a block")
    }

    @Test
    fun `a starting point is marked only while applying it would change nothing`() {
        val f = fixture()
        assertEquals(listOf(WindowPreset.MODERN_CLASSIC), f.selectedCards(), "the default is the modern classic window")

        f.click("card:${WindowPreset.COMPACT}")
        assertEquals(listOf(WindowPreset.COMPACT), f.selectedCards())

        f.store.dispatch(
            SettingsIntent.TransformDraft {
                it.withWindowLayout(WindowLayout.DEFAULT.copy(density = Density.COMPACT))
            }
        )
        f.rerender()
        assertTrue(f.selectedCards().isEmpty(), "a window that matches no starting point marks none")
    }

    @Test
    fun `the outline opens a menu that writes through the draft`() {
        val f = fixture()
        val menu = onEdt { f.panel.popupFor(OutlineBlock.STATUS_BAR) }
        val item = menu.components.filterIsInstance<JCheckBoxMenuItem>().single { it.name == LayoutPanel.STATUS_BAR_ITEM }
        assertTrue(item.isSelected)
        onEdt { item.doClick() }
        assertFalse(f.layout.showStatusBar, "the menu changed the draft's one window layout")
        assertNotNull(onEdt { f.panel.popupFor(OutlineBlock.HISTORY) })
    }

    @Test
    fun `saved layouts are renamed, deleted after confirmation and applied through the draft`() {
        val a = LayoutProfile("A", WindowLayout(showStatusBar = false))
        val b = LayoutProfile("B", WindowLayout(panes = PaneLayout.SIDE_BY_SIDE))
        val prompts = Prompts()
        val f = fixture(Configuration.DEFAULT.withLayoutProfiles(listOf(a, b)), prompts)

        prompts.rename = "B"
        onEdt { f.panel.renameProfile("A") }
        assertEquals(listOf("B"), prompts.nameTaken, "a taken name is reported, not written")
        assertEquals(listOf("A", "B"), f.draft.layoutProfiles.map { it.name })

        prompts.rename = "  Reading "
        onEdt { f.panel.renameProfile("A") }
        assertEquals(listOf("Reading", "B"), f.draft.layoutProfiles.map { it.name }, "the name is cleaned")
        assertEquals(a.layout, f.draft.layoutProfiles.first().layout)

        prompts.delete = false
        onEdt { f.panel.deleteProfile("B") }
        assertEquals(2, f.draft.layoutProfiles.size, "a declined confirmation deletes nothing")
        prompts.delete = true
        onEdt { f.panel.deleteProfile("B") }
        assertEquals(listOf("B", "B"), prompts.deleteAsked)
        assertEquals(listOf("Reading"), f.draft.layoutProfiles.map { it.name })

        onEdt { f.panel.applyProfile("Reading") }
        assertEquals(a.layout, f.layout)
        f.rerender()
        assertEquals(1, named(f.panel, LayoutPanel.PROFILE_CURRENT).size, "the applied layout is marked current")
        assertFalse((named(f.panel, LayoutPanel.APPLY).single() as AbstractButton).isEnabled)
    }

    @Test
    fun `saving adds a layout and a full list says why it cannot take another`() {
        val f = fixture(Configuration.DEFAULT, Prompts(name = "Mine"))
        onEdt { f.panel.saveCurrent() }
        assertEquals(listOf("Mine"), f.draft.layoutProfiles.map { it.name })

        val many = (1..LayoutProfile.MAX_PROFILES).map { LayoutProfile("L$it", WindowLayout.DEFAULT) }
        val full = fixture(Configuration.DEFAULT.withLayoutProfiles(many))
        val save = descendants(full.panel).filterIsInstance<AbstractButton>()
            .single { it.text == localizer.getString("main_window_main_menu.region_save_profile") }
        assertFalse(save.isEnabled)
        assertEquals(
            localizer.getString("settings_window.layout_profiles_full", LayoutProfile.MAX_PROFILES),
            save.toolTipText,
        )
    }

    @Test
    fun `the page registers its starting points for settings search`() {
        val labels = fixture().panel.searchEntries.map { it.label }
        listOf("modern_classic", "classic", "compact", "focus").forEach { key ->
            val title = localizer.getString("settings_window.layout_card_$key")
            assertTrue(title in labels, "$title is searchable")
        }
    }
}
