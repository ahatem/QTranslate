package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ParallelComparisonTranslationTest {
    private val directories = mutableListOf<java.io.File>()

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun `explicit source rule gives comparisons the effective target`() = runTest {
        val primary = ScriptedTranslator("primary")
        val comparison = ScriptedTranslator("comparison")
        val fixture = fixture(
            scope = this,
            primary = primary,
            comparison = comparison,
            rules = listOf(TranslationRule("en", "ar"))
        )
        var state = MainState(
            inputText = "hello",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.FRENCH
        )

        fixture.useCase(
            getState = { state },
            updateState = { transform -> state = state.transform() },
            onStatusUpdate = { _, _, _ -> }
        )
        advanceUntilIdle()

        assertEquals(LanguageCode.ARABIC, primary.calls.single().targetLanguage)
        assertEquals(LanguageCode.ARABIC, comparison.calls.single().targetLanguage)
        assertEquals(ComparisonStatus.SUCCESS, state.comparisonResults.single().status)
    }

    @Test
    fun `auto detect rule starts comparisons only for the final target`() = runTest {
        val primary = ScriptedTranslator("primary", detectsGermanOnFirstCall = true)
        val comparison = ScriptedTranslator("comparison")
        val fixture = fixture(
            scope = this,
            primary = primary,
            comparison = comparison,
            rules = listOf(TranslationRule("de", "ar"))
        )
        var state = MainState(
            inputText = "hallo",
            sourceLanguage = LanguageCode.AUTO,
            targetLanguage = LanguageCode.ENGLISH
        )

        fixture.useCase(
            getState = { state },
            updateState = { transform -> state = state.transform() },
            onStatusUpdate = { _, _, _ -> }
        )
        advanceUntilIdle()

        assertEquals(listOf(LanguageCode.ENGLISH, LanguageCode.ARABIC), primary.calls.map { it.targetLanguage })
        assertEquals(listOf(LanguageCode.ARABIC), comparison.calls.map { it.targetLanguage })
        assertEquals("final", state.translatedText)
    }

    @Test
    fun `auto detect rule retranslation failure leaves comparisons empty`() = runTest {
        val primary = ScriptedTranslator("primary", detectsGermanOnFirstCall = true, failSecondCall = true)
        val comparison = ScriptedTranslator("comparison")
        val fixture = fixture(
            scope = this,
            primary = primary,
            comparison = comparison,
            rules = listOf(TranslationRule("de", "ar"))
        )
        var state = MainState(
            inputText = "hallo",
            sourceLanguage = LanguageCode.AUTO,
            targetLanguage = LanguageCode.ENGLISH
        )

        fixture.useCase(
            getState = { state },
            updateState = { transform -> state = state.transform() },
            onStatusUpdate = { _, _, _ -> }
        )
        advanceUntilIdle()

        assertTrue(comparison.calls.isEmpty())
        assertTrue(state.comparisonResults.isEmpty())
        assertTrue(state.translatedText.isEmpty())
    }

    @Test
    fun `disabled comparison policy keeps the primary-only path`() = runTest {
        val primary = ScriptedTranslator("primary")
        val comparison = ScriptedTranslator("comparison")
        val fixture = fixture(this, primary, comparison, emptyList())
        var state = MainState(
            inputText = "hello",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.ARABIC
        )

        fixture.useCase(
            getState = { state },
            updateState = { transform -> state = state.transform() },
            onStatusUpdate = { _, _, _ -> },
            comparisonPolicy = ComparisonPolicy.DISABLED
        )
        advanceUntilIdle()

        assertTrue(comparison.calls.isEmpty())
        assertTrue(state.comparisonResults.isEmpty())
    }

    @Test
    fun `quick-style retranslation rejects late comparison results from the previous text`() = runTest {
        val primary = TaggedTranslator("primary")
        val comparison = GatedTranslator("comparison")
        val fixture = gatedFixture(this, primary, comparison)
        var state = MainState(
            inputText = "A",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.ARABIC
        )
        val update: ((MainState.() -> MainState)) -> Unit = { transform -> state = state.transform() }

        launch {
            fixture.useCase(
                getState = { state }, updateState = update, onStatusUpdate = { _, _, _ -> },
                textOverride = "A", comparisonPolicy = ComparisonPolicy.ENABLED
            )
        }
        runCurrent()
        assertEquals("primary:A", state.translatedText)

        launch {
            fixture.useCase(
                getState = { state }, updateState = update, onStatusUpdate = { _, _, _ -> },
                textOverride = "B", comparisonPolicy = ComparisonPolicy.ENABLED
            )
        }
        runCurrent()
        assertEquals("primary:B", state.translatedText)
        assertTrue(comparison.started("B"))

        comparison.release("A")
        runCurrent()
        assertTrue(state.comparisonResults.none { it.text.contains("A") })

        comparison.release("B")
        runCurrent()
        assertEquals("comparison:B", state.comparisonResults.single().text)
    }

    private fun fixture(
        scope: CoroutineScope,
        primary: ScriptedTranslator,
        comparison: ScriptedTranslator,
        rules: List<TranslationRule>
    ): Fixture {
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to primary.key),
            comparisonTranslatorIds = listOf(comparison.key)
        )
        val config = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset),
            activeServicePresetId = preset.id,
            translationRules = rules
        )
        val settings = MutableStateFlow(config)
        val services = mapOf<String, Service>(primary.key to primary, comparison.key to comparison)
        val manager = ActiveServiceManager(MutableStateFlow(services), settings)
        val loggerFactory = TestLoggerFactory
        val directory = Files.createTempDirectory("qtranslate-comparison").toFile()
        directories += directory
        val history = HistoryRepository(directory, loggerFactory.logger, Json)
        val comparisonUseCase = ParallelComparisonUseCase(scope, manager, loggerFactory)

        return Fixture(
            useCase = TranslateTextUseCase(
                scope = scope,
                settingsState = settings,
                activeServiceManager = manager,
                historyRepository = history,
                summarizeUseCase = SummarizeUseCase(manager, loggerFactory),
                rewriteUseCase = RewriteUseCase(manager, loggerFactory),
                loggerFactory = loggerFactory,
                parallelComparisonUseCase = comparisonUseCase
            )
        )
    }

    private fun gatedFixture(
        scope: CoroutineScope,
        primary: Translator,
        comparison: GatedTranslator
    ): GatedFixture {
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to primary.key),
            comparisonTranslatorIds = listOf(comparison.key)
        )
        val config = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset),
            activeServicePresetId = preset.id
        )
        val settings = MutableStateFlow(config)
        val manager = ActiveServiceManager(
            MutableStateFlow(mapOf<String, Service>(primary.key to primary, comparison.key to comparison)),
            settings
        )
        val loggerFactory = TestLoggerFactory
        val directory = Files.createTempDirectory("qtranslate-quick-comparison").toFile()
        directories += directory
        val history = HistoryRepository(directory, loggerFactory.logger, Json)
        val comparisonUseCase = ParallelComparisonUseCase(scope, manager, loggerFactory)
        return GatedFixture(
            TranslateTextUseCase(
                scope = scope,
                settingsState = settings,
                activeServiceManager = manager,
                historyRepository = history,
                summarizeUseCase = SummarizeUseCase(manager, loggerFactory),
                rewriteUseCase = RewriteUseCase(manager, loggerFactory),
                loggerFactory = loggerFactory,
                parallelComparisonUseCase = comparisonUseCase
            )
        )
    }

    private data class Fixture(val useCase: TranslateTextUseCase)
    private data class GatedFixture(val useCase: TranslateTextUseCase)

    private class ScriptedTranslator(
        override val key: String,
        private val detectsGermanOnFirstCall: Boolean = false,
        private val failSecondCall: Boolean = false
    ) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        val calls = mutableListOf<TranslationRequest>()

        override suspend fun translate(request: TranslationRequest): com.github.michaelbull.result.Result<TranslationResponse, ServiceError> {
            calls += request
            if (failSecondCall && calls.size == 2) return Err(ServiceError.NetworkError("failed"))
            return Ok(
                TranslationResponse(
                    translatedText = if (calls.size == 2) "final" else key,
                    detectedLanguage = if (detectsGermanOnFirstCall && calls.size == 1) LanguageCode.GERMAN else null
                )
            )
        }
    }

    private class GatedTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun translate(request: TranslationRequest): com.github.michaelbull.result.Result<TranslationResponse, ServiceError> {
            pending.getOrPut(request.text) { CompletableDeferred() }.await()
            return Ok(TranslationResponse("$key:${request.text}"))
        }

        fun started(text: String): Boolean = pending.containsKey(text)

        fun release(text: String) { pending[text]?.complete(Unit) }
    }

    private class TaggedTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest): com.github.michaelbull.result.Result<TranslationResponse, ServiceError> =
            Ok(TranslationResponse("$key:${request.text}"))
    }

    private object TestLoggerFactory : LoggerFactory {
        val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }

        override fun getLogger(name: String): Logger = logger
    }
}
