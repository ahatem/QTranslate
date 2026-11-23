package com.github.ahatem.qtranslate.core.workspace.lookup

import com.github.ahatem.qtranslate.core.testing.SilentLoggerFactory
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A caller that knows the translation pair gets a bilingual lookup; otherwise the plain single-language request. */
class LookupWordUseCaseTest {

    @Test
    fun `an explicit target reaches a bilingual dictionary as the pair`() = runBlocking {
        val dictionary = RecordingBilingualDictionary()
        lookup(dictionary, word = "rice", language = LanguageCode.ENGLISH, targetLanguage = LanguageCode.ITALIAN)

        assertEquals(
            listOf(BilingualDictionaryRequest("rice", LanguageCode.ENGLISH, LanguageCode.ITALIAN)),
            dictionary.bilingualRequests
        )
        assertTrue(dictionary.plainRequests.isEmpty())
    }

    @Test
    fun `without a target the request stays monolingual`() = runBlocking {
        val dictionary = RecordingBilingualDictionary()
        lookup(dictionary, word = "rice", language = LanguageCode.ENGLISH, targetLanguage = null)

        assertEquals(listOf(DictionaryRequest("rice", LanguageCode.ENGLISH)), dictionary.plainRequests)
        assertTrue(dictionary.bilingualRequests.isEmpty())
    }

    @Test
    fun `a dictionary without bilingual support ignores the target`() = runBlocking {
        val dictionary = RecordingDictionary()
        lookup(dictionary, word = "rice", language = LanguageCode.ENGLISH, targetLanguage = LanguageCode.ITALIAN)

        assertEquals(listOf(DictionaryRequest("rice", LanguageCode.ENGLISH)), dictionary.plainRequests)
    }

    // ── Harness ───────────────────────────────────────────────────────────────

    private suspend fun lookup(
        dictionary: Dictionary,
        word: String,
        language: LanguageCode,
        targetLanguage: LanguageCode?
    ) {
        val useCase = LookupWordUseCase(
            activeServiceManager = managerWith(dictionary),
            loggerFactory = SilentLoggerFactory
        )

        val outcome = useCase(DictionaryLookupRequest(word, language, targetLanguage)) { }
        assertTrue(outcome.entries.orEmpty().isNotEmpty(), "the lookup never produced entries")
    }
}
