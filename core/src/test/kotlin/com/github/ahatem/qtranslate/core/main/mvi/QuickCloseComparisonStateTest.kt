package com.github.ahatem.qtranslate.core.main.mvi

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
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonTranslationResult
import com.github.ahatem.qtranslate.core.main.domain.usecase.ComparisonPolicy
import com.github.ahatem.qtranslate.core.main.domain.usecase.ParallelComparisonUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.RewriteUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SummarizeUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslateTextUseCase
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Quick Translate writes the same canonical translation and comparison state the main window
 * shows, so closing the popup must not throw away a finished result set, while work that is
 * still running must be cancelled and fenced off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickCloseComparisonStateTest {
    private val directories = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.deleteRecursively() }
    }

    private fun result(id: String, status: ComparisonStatus, text: String = "") =
        ComparisonTranslationResult(id, id, status, text)

    private val completed = MainState(
        translatedText = "primary:A",
        detectedSourceLanguage = LanguageCode.ENGLISH,
        sourceLanguage = LanguageCode.AUTO,
        targetLanguage = LanguageCode.FRENCH,
        inputText = "A",
        comparisonResults = listOf(
            result("bing", ComparisonStatus.SUCCESS, "bing:A"),
            result("deepl", ComparisonStatus.FAILURE)
        ),
        isQuickTranslateDialogVisible = true,
        isQuickTranslateDialogPinned = true
    )

    // ---- what counts as in flight ----

    @Test
    fun `a finished translation with finished comparisons is not in flight`() {
        assertFalse(completed.hasTranslationInFlight())
        assertFalse(completed.copy(translatedText = "", translationFailed = true, comparisonResults = emptyList()).hasTranslationInFlight())
    }

    @Test
    fun `primary loading extra output loading and loading comparison rows are in flight`() {
        assertTrue(completed.copy(isLoading = true).hasTranslationInFlight())
        assertTrue(completed.copy(isExtraOutputLoading = true).hasTranslationInFlight())
        assertTrue(
            completed.copy(comparisonResults = listOf(result("bing", ComparisonStatus.LOADING))).hasTranslationInFlight()
        )
    }

    // ---- closing a finished Quick translation ----

    @Test
    fun `closing after completion hides the popup resets the pin and keeps the whole result set`() {
        val closed = completed.afterQuickClose()

        assertFalse(closed.isQuickTranslateDialogVisible)
        assertFalse(closed.isQuickTranslateDialogPinned)
        assertEquals(completed.translatedText, closed.translatedText)
        assertEquals(completed.comparisonResults, closed.comparisonResults)
        assertEquals(completed.detectedSourceLanguage, closed.detectedSourceLanguage)
        assertEquals(completed.targetLanguage, closed.targetLanguage)
        assertEquals(completed.inputText, closed.inputText)
    }

    @Test
    fun `a failed primary survives closing`() {
        val failed = completed.copy(translatedText = "", translationFailed = true, comparisonResults = emptyList())
        assertTrue(failed.afterQuickClose().translationFailed)
    }

    @Test
    fun `the preserved state is what the main comparison board needs`() {
        val closed = completed.afterQuickClose()
        assertTrue(closed.translatedText.isNotBlank())
        assertEquals(listOf("bing", "deepl"), closed.comparisonResults.map { it.serviceId })
        assertFalse(closed.hasTranslationInFlight())
    }

    @Test
    fun `closing while running only lowers the loading flags`() {
        val running = completed.copy(isLoading = true, isExtraOutputLoading = true)
        val closed = running.afterQuickClose()
        assertFalse(closed.isLoading)
        assertFalse(closed.isExtraOutputLoading)
        assertFalse(closed.isQuickTranslateDialogVisible)
    }

    // ---- ownership across a close ----

    @Test
    fun `closing while comparisons run fences off the late result and leaves no loading rows`() = runTest {
        val primary = TaggedTranslator("primary")
        val comparison = GatedTranslator("bing")
        val useCase = useCase(this, primary, comparison)
        var state = MainState(inputText = "A", sourceLanguage = LanguageCode.ENGLISH, targetLanguage = LanguageCode.FRENCH)
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        launch {
            useCase(getState = { state }, updateState = update, onStatusUpdate = { _, _, _ -> }, comparisonPolicy = ComparisonPolicy.ENABLED)
        }
        runCurrent()
        assertEquals("primary:A", state.translatedText)
        assertTrue(state.hasTranslationInFlight(), "a comparison row is still loading")

        // What HideQuickTranslate does for work in flight.
        useCase.cancel()
        useCase.invalidateComparisons()
        state = state.copy(comparisonResults = emptyList()).afterQuickClose()

        comparison.release("A")
        runCurrent()
        assertTrue(state.comparisonResults.isEmpty(), "a late result cannot repopulate the cleared state")
        assertFalse(state.hasTranslationInFlight())
    }

    @Test
    fun `a completed set stays put while nothing is cancelled on close`() = runTest {
        val primary = TaggedTranslator("primary")
        val comparison = GatedTranslator("bing")
        val useCase = useCase(this, primary, comparison)
        var state = MainState(inputText = "A", sourceLanguage = LanguageCode.ENGLISH, targetLanguage = LanguageCode.FRENCH)
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        launch {
            useCase(getState = { state }, updateState = update, onStatusUpdate = { _, _, _ -> }, comparisonPolicy = ComparisonPolicy.ENABLED)
        }
        runCurrent()
        comparison.release("A")
        runCurrent()
        assertEquals(ComparisonStatus.SUCCESS, state.comparisonResults.single().status)
        assertFalse(state.hasTranslationInFlight())

        // Close: nothing to cancel, so the store touches only the popup flags.
        state = state.afterQuickClose()
        assertEquals("primary:A", state.translatedText)
        assertEquals("bing:A", state.comparisonResults.single().text)
    }

    @Test
    fun `a new quick translation starts from an empty comparison set`() = runTest {
        val primary = TaggedTranslator("primary")
        val comparison = GatedTranslator("bing")
        val useCase = useCase(this, primary, comparison)
        var state = MainState(inputText = "A", sourceLanguage = LanguageCode.ENGLISH, targetLanguage = LanguageCode.FRENCH)
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        launch { useCase(getState = { state }, updateState = update, onStatusUpdate = { _, _, _ -> }, comparisonPolicy = ComparisonPolicy.ENABLED) }
        runCurrent()
        comparison.release("A")
        runCurrent()
        assertEquals("bing:A", state.comparisonResults.single().text)

        launch { useCase(getState = { state }, updateState = update, onStatusUpdate = { _, _, _ -> }, textOverride = "B", comparisonPolicy = ComparisonPolicy.ENABLED) }
        runCurrent()
        assertTrue(state.comparisonResults.none { it.text == "bing:A" }, "the previous set is gone before the new one arrives")
        comparison.release("B")
        runCurrent()
        assertEquals("bing:B", state.comparisonResults.single().text)
    }

    // ---- wiring ----

    @Test
    fun `the store cancels on close only for work in flight`() {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/core/main/mvi/MainStore.kt").readText()
        val hide = source.substringAfter("MainIntent.HideQuickTranslate ->").substringBefore("MainIntent.ToggleQuickTranslateDialogPin")
        assertTrue(hide.contains("if (_state.value.hasTranslationInFlight())"))
        assertTrue(hide.contains("translateTextUseCase.cancel()") && hide.contains("clearComparisonState()"))
        assertTrue(hide.contains("_state.update { it.afterQuickClose() }"))
        assertTrue(
            hide.indexOf("hasTranslationInFlight") < hide.indexOf("cancel()"),
            "the cancellation sits inside the in-flight guard"
        )
    }

    @Test
    fun `other events keep clearing comparisons`() {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/core/main/mvi/MainStore.kt").readText()
        fun block(start: String, end: String) = source.substringAfter(start).substringBefore(end)
        assertTrue(block("is MainIntent.UpdateInputText ->", "is MainIntent.SelectSourceLanguage").contains("clearComparisonState()"))
        assertTrue(block("is MainIntent.SelectSourceLanguage ->", "is MainIntent.SelectTargetLanguage").contains("clearComparisonState()"))
        assertTrue(block("is MainIntent.SelectTargetLanguage ->", "is MainIntent.ApplyCorrection").contains("clearComparisonState()"))
        assertTrue(block("MainIntent.RetranslateQuickTranslate ->", "MainIntent.UndoTranslation").contains("clearComparisonState()"))
        assertTrue(source.contains(".collect { clearComparisonState() }"), "configuration changes still clear")
    }

    // ---- fixtures ----

    private fun useCase(scope: CoroutineScope, primary: Translator, comparison: GatedTranslator): TranslateTextUseCase {
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to primary.key),
            comparisonTranslatorIds = listOf(comparison.key)
        )
        val settings = MutableStateFlow(Configuration.DEFAULT.copy(servicePresets = listOf(preset), activeServicePresetId = preset.id))
        val manager = ActiveServiceManager(
            MutableStateFlow(mapOf<String, Service>(primary.key to primary, comparison.key to comparison)),
            settings
        )
        val directory = Files.createTempDirectory("qtranslate-quick-close").toFile()
        directories += directory
        return TranslateTextUseCase(
            scope = scope,
            settingsState = settings,
            activeServiceManager = manager,
            historyRepository = HistoryRepository(directory, TestLoggerFactory.logger, Json),
            summarizeUseCase = SummarizeUseCase(manager, TestLoggerFactory),
            rewriteUseCase = RewriteUseCase(manager, TestLoggerFactory),
            loggerFactory = TestLoggerFactory,
            parallelComparisonUseCase = ParallelComparisonUseCase(scope, manager, TestLoggerFactory)
        )
    }

    private class TaggedTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            Ok(TranslationResponse("$key:${request.text}"))
    }

    private class GatedTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
            pending.getOrPut(request.text) { CompletableDeferred() }.await()
            return Ok(TranslationResponse("$key:${request.text}"))
        }

        fun release(text: String) {
            pending.getOrPut(text) { CompletableDeferred() }.complete(Unit)
        }
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
