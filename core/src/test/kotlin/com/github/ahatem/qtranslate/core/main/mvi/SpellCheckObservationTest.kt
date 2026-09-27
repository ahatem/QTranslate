package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SpellCheckObservationTest {
    @Test fun `detection arrival and language selection produce new requests for existing text`() = runTest {
        val state = MutableStateFlow(MainState(inputText = "Ths", sourceLanguage = LanguageCode.AUTO))
        val enabled = MutableStateFlow(true)
        val inputs = mutableListOf<SpellCheckInput>()
        val job = launch { spellCheckInputs(state, enabled).collect { inputs += it } }
        runCurrent()
        state.value = state.value.copy(detectedSourceLanguage = LanguageCode.ENGLISH)
        runCurrent()
        state.value = state.value.copy(sourceLanguage = LanguageCode.ARABIC)
        runCurrent()
        state.value = state.value.copy(detectedSourceLanguage = LanguageCode.FRENCH)
        runCurrent()
        assertEquals(3, inputs.size)
        assertEquals(listOf(null, LanguageCode.ENGLISH, null), inputs.map { it.detectedSourceLanguage })
        assertEquals(LanguageCode.ARABIC, inputs.last().sourceLanguage)
        job.cancel()
    }

    @Test fun `changed detection fences a running result before the next debounce`() = runTest {
        val state = MutableStateFlow(MainState(inputText = "Ths", sourceLanguage = LanguageCode.AUTO,
            detectedSourceLanguage = LanguageCode.ENGLISH))
        val enabled = MutableStateFlow(true)
        val committed = mutableListOf<LanguageCode?>()
        val job = launch {
            spellCheckInputs(state, enabled).debounce(750).collectLatest { input ->
                delay(500)
                if (input.matches(state.value, enabled.value)) committed += input.detectedSourceLanguage
            }
        }
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        state.value = state.value.copy(detectedSourceLanguage = LanguageCode.ARABIC)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(emptyList(), committed)
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf<LanguageCode?>(LanguageCode.ARABIC), committed)
        job.cancel()
    }

    @Test fun `text change with cleared detection invalidates previous correction context`() = runTest {
        val old = MainState(inputText = "teh", sourceLanguage = LanguageCode.AUTO,
            detectedSourceLanguage = LanguageCode.ENGLISH,
            spellCheckCorrections = listOf(Correction("teh", 0, 3, listOf("the"))))
        val input = SpellCheckInput.from(old, true)
        val changed = old.copy(inputText = "teh again", detectedSourceLanguage = null,
            spellCheckCorrections = emptyList())
        assertFalse(input.matches(changed, true))
        assertEquals(emptyList(), changed.spellCheckCorrections)
    }
}
