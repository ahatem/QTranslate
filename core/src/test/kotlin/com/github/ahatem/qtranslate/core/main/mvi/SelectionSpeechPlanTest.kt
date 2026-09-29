package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationCompletion
import com.github.ahatem.qtranslate.core.settings.data.SelectionReadSource
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionSpeechPlanTest {

    private val state = MainState(
        inputText = "mutable state input",
        translatedText = "mutable state translation",
        sourceLanguage = LanguageCode.AUTO,
        detectedSourceLanguage = LanguageCode("de"),
        targetLanguage = LanguageCode("fr")
    )

    @Test
    fun `source speaks exact captured text with resolved source language`() {
        val plan = selectionSpeechPlan(
            readSource = SelectionReadSource.SOURCE,
            selectedText = "captured selection",
            completion = null,
            state = state
        )

        requireNotNull(plan)
        assertEquals(TextSource.Input, plan.textSource)
        assertEquals("captured selection", plan.textOverride)
        assertEquals(LanguageCode("de"), plan.languageOverride)
    }

    @Test
    fun `source speech does not depend on translation success`() {
        assertEquals(
            "captured selection",
            selectionSpeechPlan(SelectionReadSource.SOURCE, "captured selection", null, state)?.textOverride
        )
    }

    @Test
    fun `translation speaks exact completed output with target language`() {
        val plan = selectionSpeechPlan(
            readSource = SelectionReadSource.TRANSLATION,
            selectedText = "captured selection",
            completion = TranslationCompletion(7L, "request translation"),
            state = state
        )

        requireNotNull(plan)
        assertEquals(TextSource.Output, plan.textSource)
        assertEquals("request translation", plan.textOverride)
        assertEquals(LanguageCode("fr"), plan.languageOverride)
    }

    @Test
    fun `translation speech is silent without a nonblank completion`() {
        assertNull(selectionSpeechPlan(SelectionReadSource.TRANSLATION, "captured selection", null, state))
        assertNull(
            selectionSpeechPlan(
                SelectionReadSource.TRANSLATION,
                "captured selection",
                TranslationCompletion(7L, "   "),
                state
            )
        )
    }

    @Test
    fun `source request becomes stale when a newer selection generation exists`() {
        assertTrue(SelectionTranslationReadGuard.shouldReadSource(true, 2L, 2L, "captured"))
        assertFalse(SelectionTranslationReadGuard.shouldReadSource(true, 1L, 2L, "captured"))
        assertFalse(SelectionTranslationReadGuard.shouldReadSource(true, 2L, 2L, ""))
    }
}
