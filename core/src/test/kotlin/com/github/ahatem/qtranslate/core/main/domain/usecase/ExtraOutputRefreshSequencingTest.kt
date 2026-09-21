package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
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
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExtraOutputRefreshSequencingTest {
    private val directories = mutableListOf<java.io.File>()

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun `explicit summarize mode is used even when settings flow still has backward translation`() = runTest {
        val fixture = fixture(Configuration.DEFAULT.copy(extraOutputType = ExtraOutputType.BackwardTranslate), this)
        var state = translatedState()

        fixture.useCase.refreshExtraOutput(
            extraOutputType = ExtraOutputType.Summarize,
            getState = { state },
            updateState = { transform -> state = state.transform() },
            onStatusUpdate = { _, _, _ -> },
        )
        advanceUntilIdle()

        assertEquals("summary", state.extraOutputText)
        assertEquals(1, fixture.summarizer.calls.size)
        assertTrue(fixture.translator.calls.isEmpty())
    }

    @Test
    fun `explicit rewrite mode is used when settings flow still has summarize`() = runTest {
        val fixture = fixture(Configuration.DEFAULT.copy(extraOutputType = ExtraOutputType.Summarize), this)
        var state = translatedState()

        fixture.useCase.refreshExtraOutput(
            extraOutputType = ExtraOutputType.Rewrite,
            getState = { state },
            updateState = { transform -> state = state.transform() },
            onStatusUpdate = { _, _, _ -> },
        )
        advanceUntilIdle()

        assertEquals("rewrite", state.extraOutputText)
        assertEquals(1, fixture.rewriter.calls.size)
        assertTrue(fixture.summarizer.calls.isEmpty())
    }

    @Test
    fun `explicit none mode clears the existing extra output`() = runTest {
        val fixture = fixture(Configuration.DEFAULT.copy(extraOutputType = ExtraOutputType.Rewrite), this)
        var state = translatedState().copy(extraOutputText = "stale")

        val refreshed = fixture.useCase.refreshExtraOutput(
            extraOutputType = ExtraOutputType.None,
            getState = { state },
            updateState = { transform -> state = state.transform() },
            onStatusUpdate = { _, _, _ -> },
        )

        assertTrue(refreshed)
        assertEquals("", state.extraOutputText)
        assertTrue(fixture.rewriter.calls.isEmpty())
    }

    @Test
    fun `rapid mode changes leave the latest refresh result in place`() = runTest {
        val fixture = fixture(Configuration.DEFAULT.copy(extraOutputType = ExtraOutputType.BackwardTranslate), this)
        fixture.summarizer.release = CompletableDeferred()
        var state = translatedState()

        fixture.useCase.refreshExtraOutput(
            extraOutputType = ExtraOutputType.Summarize,
            getState = { state },
            updateState = { transform -> state = state.transform() },
            onStatusUpdate = { _, _, _ -> },
        )
        runCurrent()
        assertTrue(fixture.summarizer.started.isCompleted)

        fixture.useCase.refreshExtraOutput(
            extraOutputType = ExtraOutputType.Rewrite,
            getState = { state },
            updateState = { transform -> state = state.transform() },
            onStatusUpdate = { _, _, _ -> },
        )
        advanceUntilIdle()

        assertEquals("rewrite", state.extraOutputText)
        assertEquals(1, fixture.rewriter.calls.size)
    }

    private fun translatedState() = MainState(
        translatedText = "translated",
        sourceLanguage = LanguageCode.ENGLISH,
        targetLanguage = LanguageCode.ARABIC,
    )

    private fun fixture(config: Configuration, scope: CoroutineScope): Fixture {
        val settings = MutableStateFlow(config)
        val translator = RecordingTranslator()
        val summarizer = RecordingSummarizer()
        val rewriter = RecordingRewriter()
        val services = mapOf<String, Service>(
            translator.key to translator,
            summarizer.key to summarizer,
            rewriter.key to rewriter,
        )
        val activeServices = ActiveServiceManager(MutableStateFlow(services), settings)
        val loggerFactory = TestLoggerFactory
        val directory = Files.createTempDirectory("qtranslate-extra-output").toFile()
        directories += directory
        val history = HistoryRepository(directory, loggerFactory.logger, Json)

        return Fixture(
            useCase = TranslateTextUseCase(
                scope = scope,
                settingsState = settings,
                activeServiceManager = activeServices,
                historyRepository = history,
                summarizeUseCase = SummarizeUseCase(activeServices, loggerFactory),
                rewriteUseCase = RewriteUseCase(activeServices, loggerFactory),
                loggerFactory = loggerFactory,
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

    private abstract class RecordingService(
        override val key: String,
    ) : Service {
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

        override suspend fun summarize(request: SummarizeRequest): Result<SummarizeResponse, ServiceError> {
            calls += request
            started.complete(Unit)
            release?.await()
            return Ok(SummarizeResponse("summary"))
        }
    }

    private class RecordingRewriter : RecordingService("rewriter"), Rewriter {
        val calls = mutableListOf<RewriteRequest>()

        override suspend fun rewrite(request: RewriteRequest): Result<RewriteResponse, ServiceError> {
            calls += request
            return Ok(RewriteResponse("rewrite"))
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
