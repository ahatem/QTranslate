package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Languages page's two state defects: a list whose fixed height bisected a row, and an empty
 * rules grid that showed two column headings and nothing else.
 */
class LanguagesPanelLayoutTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-languages-test").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        UIScale.setZoomFactor(1f)
        scopes.forEach { it.cancel() }
    }

    /** Languages the "active translator" advertises for these tests. */
    private fun availableLanguages(): List<LanguageCode> =
        listOf("auto", "en", "fr", "de", "es", "it", "pt", "ru", "ja", "ko", "zh", "ar", "tr", "pl", "nl")
            .map(::LanguageCode)

    private fun store(): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-languages-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(Configuration.DEFAULT)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, Configuration.DEFAULT)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun panelAt(zoom: Float): Pair<LanguagesPanel, SettingsStore> {
        UIScale.setZoomFactor(zoom)
        val store = store()
        val panel = onEdt { LanguagesPanel(store, localizer) { availableLanguages() }.also { it.render(store.state.value) } }
        return panel to store
    }

    // ── Pinned Languages ─────────────────────────────────────────────────────

    @Test
    fun `the pinned list is sized to whole rows at every zoom`() {
        listOf(1f, 1.25f, 1.5f, 2f).forEach { zoom ->
            val (panel, _) = panelAt(zoom)
            val list = panel.pinnedListForTest()
            val pitch = panel.pinnedRowPitchForTest()
            val inset = panel.pinnedContentInsetForTest()

            assertTrue(pitch > 0, "zoom $zoom: the row pitch is measured")
            val body = list.preferredSize.height - inset
            assertTrue(body > 0, "zoom $zoom: the list has room for rows")
            assertEquals(
                0,
                body % pitch,
                "zoom $zoom: the preferred height ($body px) is a whole number of $pitch px rows",
            )
        }
    }

    @Test
    fun `no boundary falls inside a row at the minimum dialog size`() {
        val (panel, _) = panelAt(1f)
        val list = panel.pinnedListForTest()
        val pitch = panel.pinnedRowPitchForTest()
        val inset = panel.pinnedContentInsetForTest()

        // The page is taller than the minimum dialog's content area, so the dialog gives the page
        // its preferred height inside a scroll pane and scrolls that, rather than compressing the
        // rows. Reproduce that here instead of forcing the panel itself smaller.
        val pageScroll = onEdt {
            JScrollPane(panel).also {
                it.setSize(UIScale.scale(620), UIScale.scale(340))
                layoutTree(it)
            }
        }

        assertEquals(
            list.preferredSize.height,
            list.height,
            "the list keeps the whole-row height its construction chose while the page scrolls",
        )
        assertTrue(pageScroll.verticalScrollBar.isVisible, "the page is taller than the dialog's minimum")
        val chrome = list.insets.top + list.insets.bottom +
            list.viewport.insets.top + list.viewport.insets.bottom
        val viewTop = inset - chrome
        val visible = list.viewport.extentSize.height - viewTop
        assertEquals(0, visible % pitch, "the viewport ends between rows ($visible px, $pitch px rows)")
    }

    @Test
    fun `the pinned grid flows tiles across a row instead of stacking them`() {
        val (panel, _) = panelAt(1f)
        val list = panel.pinnedListForTest()
        val tiles = (list.viewport.view as java.awt.Container).components.filter { it.isVisible }

        assertTrue(panel.pinnedCodesForTest().size > 9, "the pinned grid holds more languages than a column did")

        // The redesign's whole point: a set to pick from is scanned across, so tiles share a row
        // rather than each taking a line of its own.
        val firstRow = tiles.firstOrNull()?.y ?: 0
        assertTrue(
            tiles.count { it.y == firstRow } > 1,
            "tiles share a row, so the set is scanned across rather than read down",
        )
    }

    // ── Translation Rules ────────────────────────────────────────────────────

    @Test
    fun `the empty rules area explains itself and gives way to the table`() {
        val (panel, store) = panelAt(1f)
        val key = "settings_languages.rules_empty"

        assertTrue(panel.isShowingRulesEmptyState(), "no rules yet, so the explanation is shown")
        assertEquals(
            localizer.getString(key),
            panel.rulesEmptyLabelForTest().text,
            "the explanation is the localized sentence, not a blank or a key",
        )
        assertTrue(panel.rulesEmptyLabelForTest().text.isNotBlank())
        assertNull(
            panel.rulesScrollForTest().columnHeader?.view,
            "the empty state is not two headings over nothing",
        )
        assertEquals(0, panel.rulesTableForTest().rowCount)

        val rule = TranslationRule("de", "fr")
        store.dispatch(SettingsIntent.AddTranslationRule(rule))
        onEdt { panel.render(store.state.value) }

        assertFalse(panel.isShowingRulesEmptyState(), "one rule is enough to bring the table back")
        assertTrue(
            panel.rulesScrollForTest().viewport.view === panel.rulesTableForTest(),
            "the table is the viewport's view again",
        )
        assertTrue(
            panel.rulesScrollForTest().columnHeader?.view === panel.rulesTableForTest().tableHeader,
            "the column headings return with the table",
        )
        assertEquals(1, panel.rulesTableForTest().rowCount)
        assertEquals("de", panel.rulesTableForTest().getValueAt(0, 0))

        store.dispatch(SettingsIntent.RemoveTranslationRule(rule))
        onEdt { panel.render(store.state.value) }

        assertTrue(panel.isShowingRulesEmptyState(), "removing the last rule restores the explanation")
        assertNull(panel.rulesScrollForTest().columnHeader?.view)
        assertEquals(0, panel.rulesTableForTest().rowCount)
    }

    @Test
    fun `the explanation does not change rule behaviour`() {
        val (panel, store) = panelAt(1f)
        val rule = TranslationRule("it", "de")
        store.dispatch(SettingsIntent.AddTranslationRule(rule))
        onEdt { panel.render(store.state.value) }

        assertEquals(
            listOf(rule),
            store.state.value.workingConfiguration.translationRules,
            "the page only renders the rules it is given; adding one still goes through the store",
        )
    }
}
