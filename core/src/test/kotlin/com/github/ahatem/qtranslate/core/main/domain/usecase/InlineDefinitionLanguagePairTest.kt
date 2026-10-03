package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionary
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.Definition
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryResponse
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pair a bilingual dictionary is asked across.
 *
 * The word being defined is one side of a translation and the definition should face the other
 * side: the translated English word of an Italian → English translation is asked about English
 * → Italian, never in the service's own default direction.
 */
class InlineDefinitionLanguagePairTest {

    @Test
    fun `the translated word is looked up toward the source language`() = runBlocking {
        val dictionary = RecordingBilingualDictionary()
        fetch(
            dictionary,
            word = "rice", language = LanguageCode.ENGLISH,
            alternateWord = "riso", alternateLanguage = LanguageCode.ITALIAN
        )

        assertEquals(
            listOf(BilingualDictionaryRequest("rice", LanguageCode.ENGLISH, LanguageCode.ITALIAN)),
            dictionary.bilingualRequests
        )
        assertTrue(dictionary.plainRequests.isEmpty())
    }

    @Test
    fun `the source word is looked up toward the target language`() = runBlocking {
        val dictionary = RecordingBilingualDictionary()
        fetch(
            dictionary,
            word = "riso", language = LanguageCode.ITALIAN,
            alternateWord = "rice", alternateLanguage = LanguageCode.ENGLISH
        )

        assertEquals(
            listOf(BilingualDictionaryRequest("riso", LanguageCode.ITALIAN, LanguageCode.ENGLISH)),
            dictionary.bilingualRequests
        )
        assertTrue(dictionary.plainRequests.isEmpty())
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

    /**
     * Runs the fetch and waits for the definition to land, as in [InlineDefinitionBudgetTest].
     * The requests recorded on the way are the assertion.
     */
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
            activeServiceManager = ActiveServiceManager(
                activeServices = MutableStateFlow(mapOf("fake:default:dictionary" to dictionary)),
                configuration = MutableStateFlow(Configuration.DEFAULT)
            ),
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

    private open class RecordingDictionary : Dictionary {
        override val key = "dictionary"
        override val name = "Fake"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        val plainRequests = mutableListOf<DictionaryRequest>()

        override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> {
            plainRequests += request
            return Ok(responseFor(request.word))
        }

        protected open fun responseFor(word: String): DictionaryResponse =
            DictionaryResponse(listOf(DictionaryEntry(word, "noun", listOf(Definition("a grain")))))
    }

    private class RecordingBilingualDictionary(
        private val defineWords: Set<String>? = null
    ) : RecordingDictionary(), BilingualDictionary {
        val bilingualRequests = mutableListOf<BilingualDictionaryRequest>()

        override suspend fun lookupBilingual(
            request: BilingualDictionaryRequest
        ): Result<DictionaryResponse, ServiceError> {
            bilingualRequests += request
            val defined = defineWords == null || request.word in defineWords
            return Ok(if (defined) responseFor(request.word) else DictionaryResponse(emptyList()))
        }
    }

    private object SilentLoggerFactory : LoggerFactory {
        override fun getLogger(name: String): Logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
    }
}
