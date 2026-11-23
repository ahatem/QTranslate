package com.github.ahatem.qtranslate.core.main.mvi

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
import com.github.ahatem.qtranslate.core.workspace.translation.ComparisonPolicy
import com.github.ahatem.qtranslate.core.workspace.translation.ParallelComparisonUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.RewriteUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.SummarizeUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.TranslateTextUseCase
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputRequest
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.testing.SilentLogger
import com.github.ahatem.qtranslate.core.testing.SilentLoggerFactory
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

    @Test
    fun `in flight work is classified as primary or comparison work, or extra output alone`() {
        class Case(val label: String, val state: MainState, val primaryOrComparison: Boolean, val any: Boolean)

        val failedPrimary = completed.copy(translatedText = "", translationFailed = true, comparisonResults = emptyList())
        val failedOnly = completed.copy(
            comparisonResults = listOf(result("bing", ComparisonStatus.FAILURE), result("deepl", ComparisonStatus.FAILURE))
        )
        val cases = listOf(
            Case("finished with finished comparisons", completed, primaryOrComparison = false, any = false),
            Case("failed primary", failedPrimary, primaryOrComparison = false, any = false),
            Case("terminal comparison failures count as complete", failedOnly, primaryOrComparison = false, any = false),
            Case("primary loading", completed.copy(isLoading = true), primaryOrComparison = true, any = true),
            Case(
                "loading comparison row",
                completed.copy(comparisonResults = listOf(result("bing", ComparisonStatus.LOADING))),
                primaryOrComparison = true,
                any = true,
            ),
            Case("extra output alone", completed.copy(isExtraOutputLoading = true), primaryOrComparison = false, any = true),
        )

        for (case in cases) {
            assertEquals(case.primaryOrComparison, case.state.hasPrimaryOrComparisonInFlight(), case.label)
            assertEquals(case.any, case.state.hasAnyTranslationWorkInFlight(), case.label)
        }
    }

    @Test
    fun `closing cancels running work and clears comparisons only when the primary or a comparison runs`() {
        class Case(val label: String, val state: MainState, val expected: QuickCloseActions)

        val mixed = completed.copy(
            isExtraOutputLoading = true,
            comparisonResults = listOf(
                result("bing", ComparisonStatus.SUCCESS, "bing:A"),
                result("deepl", ComparisonStatus.FAILURE),
                result("yandex", ComparisonStatus.SUCCESS, "yandex:A")
            )
        )
        val cancelAndClear = QuickCloseActions(cancelWork = true, clearComparisons = true)
        val cancelOnly = QuickCloseActions(cancelWork = true, clearComparisons = false)
        val cases = listOf(
            Case("everything complete", completed, QuickCloseActions(cancelWork = false, clearComparisons = false)),
            Case("only extra output running", completed.copy(isExtraOutputLoading = true), cancelOnly),
            Case("terminal rows with extra output running", mixed, cancelOnly),
            Case("primary running", completed.copy(isLoading = true), cancelAndClear),
            Case(
                "a comparison running",
                completed.copy(comparisonResults = listOf(result("bing", ComparisonStatus.LOADING))),
                cancelAndClear,
            ),
            Case("primary and extra output running", completed.copy(isLoading = true, isExtraOutputLoading = true), cancelAndClear),
        )

        for (case in cases) {
            assertEquals(case.expected, case.state.quickCloseActions(), case.label)
            if (!case.expected.clearComparisons) {
                val closed = case.state.afterQuickClose()
                assertEquals(case.state.comparisonResults, closed.comparisonResults, "${case.label}: comparisons kept")
                assertEquals(case.state.translatedText, closed.translatedText, "${case.label}: result kept")
            }
        }
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
        assertTrue(state.hasPrimaryOrComparisonInFlight(), "a comparison row is still loading")

        // What HideQuickTranslate does for work in flight.
        useCase.cancel()
        useCase.invalidateComparisons()
        state = state.copy(comparisonResults = emptyList()).afterQuickClose()

        comparison.release("A")
        runCurrent()
        assertTrue(state.comparisonResults.isEmpty(), "a late result cannot repopulate the cleared state")
        assertFalse(state.hasAnyTranslationWorkInFlight())
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
        assertFalse(state.hasAnyTranslationWorkInFlight())

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

    @Test
    fun `a late extra output from a closed quick translation cannot land`() = runTest {
        val primary = ForwardThenGatedTranslator("primary")
        val comparison = TaggedTranslator("bing")
        val useCase = useCase(this, primary, comparison)
        var state = MainState(inputText = "A", sourceLanguage = LanguageCode.ENGLISH, targetLanguage = LanguageCode.FRENCH)
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        launch {
            useCase(
                getState = { state }, updateState = update, onStatusUpdate = { _, _, _ -> },
                comparisonPolicy = ComparisonPolicy.ENABLED,
                extraOutputRequest = ExtraOutputRequest(ExtraOutputType.BackwardTranslate, ExtraOutputSource.Output, "medium", "neutral")
            )
        }
        runCurrent()
        assertEquals("primary:A", state.translatedText)
        assertEquals("bing:A", state.comparisonResults.single().text)
        assertTrue(state.isExtraOutputLoading, "only the extra output is still running")
        val close = state.quickCloseActions()
        assertEquals(QuickCloseActions(cancelWork = true, clearComparisons = false), close)

        // What HideQuickTranslate does: cancel, and leave the finished comparisons alone.
        useCase.cancel()
        state = state.afterQuickClose()

        primary.releaseBackward()
        runCurrent()
        assertEquals("", state.extraOutputText, "the cancelled request cannot write its answer")
        assertFalse(state.isExtraOutputLoading)
        assertEquals("primary:A", state.translatedText)
        assertEquals("bing:A", state.comparisonResults.single().text)
    }

    // ---- fixtures ----

    private fun useCase(scope: CoroutineScope, primary: Translator, comparison: Translator): TranslateTextUseCase {
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
            historyRepository = HistoryRepository(directory, SilentLogger, Json),
            summarizeUseCase = SummarizeUseCase(manager, SilentLoggerFactory),
            rewriteUseCase = RewriteUseCase(manager, SilentLoggerFactory),
            loggerFactory = SilentLoggerFactory,
            parallelComparisonUseCase = ParallelComparisonUseCase(scope, manager, SilentLoggerFactory)
        )
    }

    private class TaggedTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            Ok(TranslationResponse("$key:${request.text}"))
    }

    /** Answers the first request at once and holds every later one, such as the backward translation. */
    private class ForwardThenGatedTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        private val gate = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
            if (++calls > 1) gate.await()
            return Ok(TranslationResponse("$key:${request.text}"))
        }

        fun releaseBackward() {
            gate.complete(Unit)
        }
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
}
