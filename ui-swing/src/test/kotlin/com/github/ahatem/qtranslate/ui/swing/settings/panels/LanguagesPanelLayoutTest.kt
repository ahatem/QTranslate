package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguagesPanelLayoutTest {
    private val fixture = SettingsPanelFixture()

    @AfterTest
    fun tearDown() = fixture.close()

    /** Languages the "active translator" advertises for these tests. */
    private fun availableLanguages(): List<LanguageCode> =
        listOf("auto", "en", "fr", "de", "es", "it", "pt", "ru", "ja", "ko", "zh", "ar", "tr", "pl", "nl")
            .map(::LanguageCode)

    private fun panelAt(): Pair<LanguagesPanel, SettingsStore> {
        val store = fixture.store()
        val panel = onEdt {
            LanguagesPanel(store, fixture.localizer) { availableLanguages() }.also { it.render(store.state.value) }
        }
        return panel to store
    }

    @Test
    fun `the empty rules area explains itself and gives way to the table`() {
        val (panel, store) = panelAt()
        val key = "settings_languages.rules_empty"

        assertTrue(panel.isShowingRulesEmptyState(), "no rules yet, so the explanation is shown")
        assertEquals(
            fixture.localizer.getString(key),
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
}
