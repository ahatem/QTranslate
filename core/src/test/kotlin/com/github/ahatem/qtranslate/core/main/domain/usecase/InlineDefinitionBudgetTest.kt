package com.github.ahatem.qtranslate.core.main.domain.usecase

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
import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules that keep the inline definition a glance.
 *
 * These are the parts most likely to drift: the length budget decides whether the strip is one
 * line or a paragraph, and the separator decides whether it reads as prose or as clutter. Both
 * are pure string decisions, so they are worth pinning rather than eyeballing on screen.
 */
class InlineDefinitionBudgetTest {

    private val longSense =
        "the earth, together with all of its countries, peoples, and natural features, " +
            "considered as a single place in which everybody lives"

    @Test
    fun `a long first sense is shortened and stands alone`() {
        val definition = define(longSense, "a region or group of countries")

        // Truncated rather than run on, and the second sense is dropped: after a sense this long
        // a second one is a paragraph, not a glance.
        assertTrue(definition.endsWith("…"), "expected an ellipsis, got: $definition")
        assertFalse(definition.contains("a region or group"))
        assertTrue(definition.length < longSense.length)
    }

    @Test
    fun `two short senses are both kept`() {
        val definition = define("a greeting", "an expression of surprise")

        assertTrue(definition.contains("a greeting"))
        assertTrue(definition.contains("an expression of surprise"))
    }

    @Test
    fun `a sense ending in a full stop is not followed by a bullet`() {
        val definition = define("a greeting.", "an expression of surprise")

        // The full stop already separates them; a bullet on top of it reads as clutter.
        assertFalse(definition.contains("·"), "unexpected bullet in: $definition")
        assertTrue(definition.contains("a greeting. an expression of surprise"))
    }

    @Test
    fun `a sense without punctuation is followed by a bullet`() {
        val definition = define("a greeting", "an expression of surprise")

        assertTrue(definition.contains("·"), "expected a separator in: $definition")
    }

    @Test
    fun `the elision falls on a word boundary`() {
        val definition = define(longSense)

        val body = definition.substringAfter("— ").removeSuffix("…").trimEnd()
        assertTrue(longSense.startsWith(body), "cut mid-phrase: $body")
        assertFalse(body.endsWith(","), "left dangling punctuation: $body")
    }

    @Test
    fun `the part of speech leads the line`() {
        val definition = define("a greeting")

        assertTrue(definition.startsWith("noun — "), "got: $definition")
    }

    // ── Harness ───────────────────────────────────────────────────────────────

    /**
     * Runs the fetch and waits for the definition to land.
     *
     * The use case launches its own coroutine, so the result is not available on return. Waited
     * for rather than assumed, with a ceiling so a mistake fails the test instead of hanging it.
     */
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

    private fun managerWith(dictionary: Dictionary) = ActiveServiceManager(
        activeServices = MutableStateFlow(mapOf("fake:default:dictionary" to dictionary)),
        configuration = MutableStateFlow(Configuration.DEFAULT)
    )

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

    private object SilentLoggerFactory : LoggerFactory {
        override fun getLogger(name: String): Logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
    }
}
