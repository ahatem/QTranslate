package com.github.ahatem.qtranslate.core.workspace.lookup

import com.github.ahatem.qtranslate.core.testing.SilentLoggerFactory
import com.github.ahatem.qtranslate.api.dictionary.Definition
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryResponse
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules that keep the inline definition a glance: length budget, word-boundary elision and separators. */
class InlineDefinitionBudgetTest {

    private val longSense =
        "the earth, together with all of its countries, peoples, and natural features, " +
            "considered as a single place in which everybody lives"

    @Test
    fun `a long first sense is shortened and stands alone`() {
        val definition = define(longSense, "a region or group of countries")

        // After a sense this long a second one is a paragraph, not a glance.
        assertTrue(definition.endsWith("…"), "expected an ellipsis, got: $definition")
        assertFalse(definition.contains("a region or group"))
        assertTrue(definition.length < longSense.length)
    }

    @Test
    fun `a sense ending in a full stop is not followed by a bullet`() {
        // The full stop already separates them; a bullet on top of it reads as clutter.
        val stopped = define("a greeting.", "an expression of surprise")
        assertFalse(stopped.contains("·"), "unexpected bullet in: $stopped")
        assertTrue(stopped.contains("a greeting. an expression of surprise"))

        val bare = define("a greeting", "an expression of surprise")
        assertTrue(bare.contains("·"), "expected a separator in: $bare")
    }

    @Test
    fun `the elision falls on a word boundary`() {
        val definition = define(longSense)

        val body = definition.substringAfter("— ").removeSuffix("…").trimEnd()
        assertTrue(longSense.startsWith(body), "cut mid-phrase: $body")
        assertFalse(body.endsWith(","), "left dangling punctuation: $body")
    }

    // ── Harness ───────────────────────────────────────────────────────────────

    /** Runs the fetch and waits for the definition to land, since the use case launches its own coroutine. */
    private fun define(vararg senses: String): String = runBlocking {
        val state = MutableStateFlow(MainState())
        val useCase = FetchInlineDefinitionUseCase(
            scope = CoroutineScope(Dispatchers.Default),
            activeServiceManager = managerWith(FakeDictionary(senses.toList())),
            loggerFactory = SilentLoggerFactory
        )

        useCase(
            word = "world",
            language = LanguageCode.ENGLISH,
            alternateWord = "",
            alternateLanguage = LanguageCode.ENGLISH,
            updateState = { transform -> state.value = state.value.transform() }
        )

        withTimeout(5_000) {
            while (state.value.inlineDefinition.isBlank()) delay(5)
        }
        state.value.inlineDefinition
    }

    private class FakeDictionary(private val senses: List<String>) : Dictionary {
        override val key = "dictionary"
        override val name = "Fake"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> =
            Ok(
                DictionaryResponse(
                    listOf(DictionaryEntry(request.word, "noun", senses.map { Definition(it) }))
                )
            )
    }
}
