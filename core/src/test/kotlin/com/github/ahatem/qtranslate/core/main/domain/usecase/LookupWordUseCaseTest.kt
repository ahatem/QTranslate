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
 * What a full dictionary-panel lookup hands to the dictionary.
 *
 * A caller that knows the translation pair hands the other side over and a bilingual dictionary
 * is asked across the pair. A caller that does not keeps the plain single-language request the
 * panel has always made.
 */
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
        val state = MutableStateFlow(MainState())
        val useCase = LookupWordUseCase(
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
            targetLanguage = targetLanguage,
            updateState = { transform -> state.value = state.value.transform() },
            onStatusUpdate = { _, _, _ -> }
        )

        withTimeout(5_000) {
            while (state.value.dictionaryEntries.isEmpty() && !state.value.dictionaryFailed) delay(5)
        }
        assertTrue(state.value.dictionaryEntries.isNotEmpty(), "the lookup never produced entries")
    }

    private open class RecordingDictionary : Dictionary {
        override val key = "dictionary"
        override val name = "Fake"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        val plainRequests = mutableListOf<DictionaryRequest>()

        override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> {
            plainRequests += request
            return Ok(DictionaryResponse(listOf(DictionaryEntry(request.word, "noun", listOf(Definition("a grain"))))))
        }
    }

    private class RecordingBilingualDictionary : RecordingDictionary(), BilingualDictionary {
        val bilingualRequests = mutableListOf<BilingualDictionaryRequest>()

        override suspend fun lookupBilingual(
            request: BilingualDictionaryRequest
        ): Result<DictionaryResponse, ServiceError> {
            bilingualRequests += request
            return Ok(DictionaryResponse(listOf(DictionaryEntry(request.word, "noun", listOf(Definition("a grain"))))))
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
