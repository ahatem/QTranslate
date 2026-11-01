package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.rewriter.RewriteRequest
import com.github.ahatem.qtranslate.api.rewriter.RewriteResponse
import com.github.ahatem.qtranslate.api.rewriter.Rewriter
import com.github.ahatem.qtranslate.api.summarizer.SummarizeRequest
import com.github.ahatem.qtranslate.api.summarizer.SummarizeResponse
import com.github.ahatem.qtranslate.api.summarizer.Summarizer
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputRequest
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Extra-output lifecycle: a request owns its own generation, so switching mode or clearing the
 * panel invalidates the answer and its cleanup; outcomes are typed so failures never become text.
 */
class ExtraOutputLifecycleTest {
    private val directories = mutableListOf<java.io.File>()

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun `switching to None cancels the in-flight request and keeps the panel clear`() = runTest {
        val fixture = fixture(this)
        fixture.summarizer.release = CompletableDeferred()
        var state = translatedState()
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.Summarize),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        runCurrent()
        assertTrue(fixture.summarizer.started.isCompleted)

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.None),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        advanceUntilIdle()

        assertEquals("", state.extraOutputText)
        assertFalse(state.isExtraOutputLoading)
        assertFalse(state.extraOutputFailed)
        assertNull(state.extraOutputError)
        assertTrue(state.history.isEmpty())
    }

    @Test
    fun `a stale completion cannot publish over a newer mode`() = runTest {
        val fixture = fixture(this)
        fixture.summarizer.release = CompletableDeferred()
        var state = translatedState()
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.Summarize),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        runCurrent()

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.Rewrite),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        advanceUntilIdle()

        assertEquals("rewrite", state.extraOutputText)
        assertFalse(state.extraOutputFailed)
        assertEquals(1, fixture.rewriter.calls.size)
    }

    @Test
    fun `cancelling the use case retires an in-flight secondary request`() = runTest {
        val fixture = fixture(this)
        fixture.summarizer.release = CompletableDeferred()
        var state = translatedState()
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.Summarize),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        runCurrent()
        fixture.useCase.cancel()
        fixture.summarizer.release?.complete(Unit)
        advanceUntilIdle()

        assertEquals("", state.extraOutputText)
        assertFalse(state.extraOutputFailed)
    }

    @Test
    fun `None wins over the secondary output started by a translation`() = runTest {
        val fixture = fixture(this)
        fixture.summarizer.release = CompletableDeferred()
        var state = MainState(
            inputText = "hello",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.ARABIC,
        )
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        launch {
            fixture.useCase(
                getState = { state },
                updateState = update,
                onStatusUpdate = { _, _, _ -> },
                extraOutputRequest = request(ExtraOutputType.Summarize),
            )
        }
        runCurrent()
        assertTrue(fixture.summarizer.started.isCompleted, "the translation already started its summary")

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.None),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        fixture.summarizer.release?.complete(Unit)
        advanceUntilIdle()

        assertEquals("", state.extraOutputText, "turning the panel off owns the answer")
        assertFalse(state.extraOutputFailed)
        assertNull(state.extraOutputError)
    }

    @Test
    fun `a mode change wins over the secondary output started by a translation`() = runTest {
        val fixture = fixture(this)
        fixture.summarizer.release = CompletableDeferred()
        var state = MainState(
            inputText = "hello",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.ARABIC,
        )
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        launch {
            fixture.useCase(
                getState = { state },
                updateState = update,
                onStatusUpdate = { _, _, _ -> },
                extraOutputRequest = request(ExtraOutputType.Summarize),
            )
        }
        runCurrent()
        assertTrue(fixture.summarizer.started.isCompleted)

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.Rewrite),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        fixture.summarizer.release?.complete(Unit)
        advanceUntilIdle()

        assertEquals("rewrite", state.extraOutputText)
        assertFalse(state.extraOutputFailed)
    }

    @Test
    fun `a failed secondary output is marked failed and never becomes text or history`() = runTest {
        val fixture = fixture(this)
        fixture.summarizer.result = Err(ServiceError.InvalidResponseError("provider exploded"))
        var state = translatedState()
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.Summarize),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        advanceUntilIdle()

        assertEquals("", state.extraOutputText, "the error text must not be copied or spoken")
        assertTrue(state.extraOutputFailed)
        assertEquals("provider exploded", state.extraOutputError)
        assertFalse(state.isExtraOutputLoading)
        assertTrue(state.history.isEmpty(), "a failed secondary output is not history content")
    }

    @Test
    fun `an empty success is distinguishable from a failure`() = runTest {
        val fixture = fixture(this)
        fixture.summarizer.result = Ok(SummarizeResponse(""))
        var state = translatedState()
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.Summarize),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        advanceUntilIdle()

        assertEquals("", state.extraOutputText)
        assertFalse(state.extraOutputFailed)
        assertNull(state.extraOutputError)
    }

    @Test
    fun `an early return normalizes the loading and failure flags`() = runTest {
        val fixture = fixture(this)
        var state = MainState(
            inputText = "input",
            translatedText = "",
            extraOutputFailed = true,
            extraOutputError = "old failure",
            isExtraOutputLoading = true,
        )
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        val refreshed = fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.Summarize),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        advanceUntilIdle()

        assertFalse(refreshed)
        assertFalse(state.isExtraOutputLoading)
        assertFalse(state.extraOutputFailed)
        assertNull(state.extraOutputError)
        assertTrue(fixture.summarizer.calls.isEmpty())
    }

    @Test
    fun `a missing translator is a typed failure, not an empty success`() = runTest {
        val fixture = fixture(this, includeTranslator = false)
        var state = translatedState()
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.Summarize),
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
        )
        advanceUntilIdle()

        assertTrue(state.extraOutputFailed)
        assertEquals("No translator service is active.", state.extraOutputError)
        assertFalse(state.isExtraOutputLoading)
    }

    private fun request(type: ExtraOutputType) =
        ExtraOutputRequest(type, ExtraOutputSource.Output, "medium", "neutral")

    private fun translatedState() = MainState(
        translatedText = "translated",
        sourceLanguage = LanguageCode.ENGLISH,
        targetLanguage = LanguageCode.ARABIC,
    )

    private fun fixture(scope: CoroutineScope, includeTranslator: Boolean = true): Fixture {
        val settings = MutableStateFlow(Configuration.DEFAULT)
        val translator = RecordingTranslator()
        val summarizer = RecordingSummarizer()
        val rewriter = RecordingRewriter()
        val services = buildMap {
            if (includeTranslator) put(translator.key, translator)
            put(summarizer.key, summarizer)
            put(rewriter.key, rewriter)
        }
        val activeServices = ActiveServiceManager(MutableStateFlow(services), settings)
        val directory = Files.createTempDirectory("qtranslate-extra-lifecycle").toFile()
        directories += directory
        val history = HistoryRepository(directory, SilentLogger, Json)

        return Fixture(
            useCase = TranslateTextUseCase(
                scope = scope,
                settingsState = settings,
                activeServiceManager = activeServices,
                historyRepository = history,
                summarizeUseCase = SummarizeUseCase(activeServices, SilentFactory),
                rewriteUseCase = RewriteUseCase(activeServices, SilentFactory),
                loggerFactory = SilentFactory,
            ),
            translator = translator,
            summarizer = summarizer,
            rewriter = rewriter,
        )
    }

    private data class Fixture(
        val useCase: TranslateTextUseCase,
        val translator: RecordingTranslator,
        val summarizer: RecordingSummarizer,
        val rewriter: RecordingRewriter,
    )

    private abstract class RecordingService(override val key: String) : Service {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
    }

    private class RecordingTranslator : RecordingService("translator"), Translator {
        val calls = mutableListOf<TranslationRequest>()

        override suspend fun translate(request: TranslationRequest) =
            Ok(TranslationResponse("backward")).also { calls += request }
    }

    private class RecordingSummarizer : RecordingService("summarizer"), Summarizer {
        val calls = mutableListOf<SummarizeRequest>()
        val started = CompletableDeferred<Unit>()
        var release: CompletableDeferred<Unit>? = null
        var result: Result<SummarizeResponse, ServiceError> = Ok(SummarizeResponse("summary"))

        override suspend fun summarize(request: SummarizeRequest): Result<SummarizeResponse, ServiceError> {
            calls += request
            started.complete(Unit)
            release?.await()
            return result
        }
    }

    private class RecordingRewriter : RecordingService("rewriter"), Rewriter {
        val calls = mutableListOf<RewriteRequest>()

        override suspend fun rewrite(request: RewriteRequest): Result<RewriteResponse, ServiceError> {
            calls += request
            return Ok(RewriteResponse("rewrite"))
        }
    }

    private object SilentLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private object SilentFactory : LoggerFactory {
        override fun getLogger(name: String): Logger = SilentLogger
    }
}
