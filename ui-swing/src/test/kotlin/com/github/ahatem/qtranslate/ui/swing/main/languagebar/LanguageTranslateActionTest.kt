package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Translate action that stands in for the bar's button must behave as that button does. */
class LanguageTranslateActionTest {

    private fun state(loading: Boolean, canTranslate: Boolean) = LanguageSelectionBarState(
        isLoading = loading,
        canClear = true,
        canSwap = true,
        canTranslate = canTranslate,
        allSourceLanguages = listOf(LanguageCode.ENGLISH),
        allTargetLanguages = listOf(LanguageCode.GERMAN),
        selectedSourceLanguage = LanguageCode.ENGLISH,
        selectedTargetLanguage = LanguageCode.GERMAN,
        strings = LanguageSelectionBarStrings("Translate", "Cancel", "Clear", "Swap"),
    )

    @Test
    fun `idle it translates`() {
        var translated = 0
        var cancelled = 0
        val action = state(loading = false, canTranslate = true).translateAction({ translated++ }, { cancelled++ })
        assertEquals(TRANSLATE_ACTION_ID, action.id)
        assertEquals(Icons.TRANSLATE, action.iconPath)
        assertEquals("Translate", action.tooltip)
        assertTrue(action.isEnabled && action.isVisible)
        action.onClick()
        assertEquals(1 to 0, translated to cancelled)
    }

    @Test
    fun `blank input leaves it disabled`() {
        assertFalse(state(loading = false, canTranslate = false).translateAction({}, {}).isEnabled)
    }

    @Test
    fun `while loading it cancels, and stays pressable with blank input`() {
        var translated = 0
        var cancelled = 0
        val action = state(loading = true, canTranslate = false).translateAction({ translated++ }, { cancelled++ })
        assertEquals(CANCEL_ACTION_ID, action.id)
        assertEquals(Icons.CLOSE, action.iconPath)
        assertEquals("Cancel", action.tooltip)
        assertTrue(action.isEnabled, "a running translation must always be stoppable")
        action.onClick()
        assertEquals(0 to 1, translated to cancelled)
    }

    @Test
    fun `the enabled rule is the bar's rule`() {
        for (loading in listOf(false, true)) for (canTranslate in listOf(false, true)) {
            val s = state(loading, canTranslate)
            assertEquals(s.isTranslateActionable, s.translateAction({}, {}).isEnabled)
            assertEquals(loading || canTranslate, s.isTranslateActionable)
        }
    }
}
