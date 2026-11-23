package com.github.ahatem.qtranslate.core.workspace.lookup

import com.github.ahatem.qtranslate.core.testing.SilentLoggerFactory
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A bilingual dictionary is asked across the translation pair, facing the other side of the word being defined. */
class InlineDefinitionLanguagePairTest {

    @Test
    fun `each word is looked up toward the other side of the translation`() = runBlocking {
        val cases = listOf(
            Triple("translated word toward the source", Pair("rice", LanguageCode.ENGLISH), Pair("riso", LanguageCode.ITALIAN)),
            Triple("source word toward the target", Pair("riso", LanguageCode.ITALIAN), Pair("rice", LanguageCode.ENGLISH))
        )
        for ((label, word, alternate) in cases) {
            val dictionary = RecordingBilingualDictionary()
            fetch(
                dictionary,
                word = word.first, language = word.second,
                alternateWord = alternate.first, alternateLanguage = alternate.second
            )

            assertEquals(
                listOf(BilingualDictionaryRequest(word.first, word.second, alternate.second)),
                dictionary.bilingualRequests,
                label
            )
            assertTrue(dictionary.plainRequests.isEmpty(), label)
        }
    }

    @Test
    fun `the alternate candidate reverses the pair`() = runBlocking {
        val dictionary = RecordingBilingualDictionary(defineWords = setOf("riso"))
        fetch(
            dictionary,
            word = "rice", language = LanguageCode.ENGLISH,
            alternateWord = "riso", alternateLanguage = LanguageCode.ITALIAN
        )

        assertEquals(
            listOf(
                BilingualDictionaryRequest("rice", LanguageCode.ENGLISH, LanguageCode.ITALIAN),
                BilingualDictionaryRequest("riso", LanguageCode.ITALIAN, LanguageCode.ENGLISH)
            ),
            dictionary.bilingualRequests
        )
    }

    @Test
    fun `a dictionary without bilingual support keeps the plain request`() = runBlocking {
        val dictionary = RecordingDictionary()
        fetch(
            dictionary,
            word = "rice", language = LanguageCode.ENGLISH,
            alternateWord = "riso", alternateLanguage = LanguageCode.ITALIAN
        )

        assertEquals(listOf(DictionaryRequest("rice", LanguageCode.ENGLISH)), dictionary.plainRequests)
    }

    @Test
    fun `a pair that does not cross languages keeps the plain request`() = runBlocking {
        val dictionary = RecordingBilingualDictionary()
        fetch(
            dictionary,
            word = "rice", language = LanguageCode.ENGLISH,
            alternateWord = "", alternateLanguage = LanguageCode.ENGLISH
        )

        assertTrue(dictionary.bilingualRequests.isEmpty())
        assertEquals(listOf(DictionaryRequest("rice", LanguageCode.ENGLISH)), dictionary.plainRequests)
    }

    // ── Harness ───────────────────────────────────────────────────────────────

    /** Runs the fetch and waits for the definition to land; the requests recorded on the way are the assertion. */
    private suspend fun fetch(
        dictionary: Dictionary,
        word: String,
        language: LanguageCode,
        alternateWord: String,
        alternateLanguage: LanguageCode
    ) {
        val state = MutableStateFlow(MainState())
        val useCase = FetchInlineDefinitionUseCase(
            scope = CoroutineScope(Dispatchers.Default),
            activeServiceManager = managerWith(dictionary),
            loggerFactory = SilentLoggerFactory
        )

        useCase(
            word = word,
            language = language,
            alternateWord = alternateWord,
            alternateLanguage = alternateLanguage,
            updateState = { transform -> state.value = state.value.transform() }
        )

        withTimeout(5_000) {
            while (state.value.inlineDefinition.isBlank()) delay(5)
        }
    }
}
