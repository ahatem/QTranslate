package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.domain.usecase.ComparisonPolicy
import com.github.ahatem.qtranslate.core.main.domain.usecase.ParallelComparisonUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.RewriteUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SummarizeUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslateTextUseCase
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the translation use case hands over is what auto-copy has to work with: one completion per
 * logical translation, and nothing at all when the request did not produce a result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationCompletionAutoCopyTest {

    private val directories = mutableListOf<File>()

    @Test
    fun `a successful translation yields one completion`() = runTest {
        val useCase = useCase(this, EchoTranslator())
        var state = MainState(inputText = "hello")
        val completion = useCase.translate(update = { transform -> state = state.transform() })
        advanceUntilIdle()

        assertEquals(1, listOfNotNull(completion).size)
        assertEquals("t:hello", completion?.translatedText)
    }

    @Test
    fun `a failed translation yields no completion`() = runTest {
        val useCase = useCase(this, FailingTranslator())
        var state = MainState(inputText = "hello")
        val completion = useCase.translate(update = { transform -> state = state.transform() })
        advanceUntilIdle()

        assertNull(completion)
        assertTrue(state.translationFailed)
    }

    @Test
    fun `a provider that falls back across endpoints still yields one completion`() = runTest {
        val translator = RetryingTranslator()
        val useCase = useCase(this, translator)
        var state = MainState(inputText = "hello")
        val completion = useCase.translate(update = { transform -> state = state.transform() })
        advanceUntilIdle()

        assertEquals(3, translator.attempts)
        assertEquals("recovered:hello", completion?.translatedText, "one logical translation, one result")
    }

    @Test
    fun `a provider whose every endpoint fails yields no completion`() = runTest {
        val useCase = useCase(this, RetryingTranslator(succeedAt = Int.MAX_VALUE))
        var state = MainState(inputText = "hello")
        val completion = useCase.translate(update = { transform -> state = state.transform() })
        advanceUntilIdle()

        assertNull(completion)
    }

    @Test
    fun `comparison rows land beside the primary rather than replacing it`() = runTest {
        val useCase = useCase(this, EchoTranslator(), comparison = EchoTranslator("bing"))
        var state = MainState(inputText = "hello")
        val completion = useCase.translate(
            update = { transform -> state = state.transform() },
            comparisonPolicy = ComparisonPolicy.ENABLED,
        )
        advanceUntilIdle()

        assertEquals("t:hello", completion?.translatedText, "the primary is the completion")
        assertTrue(state.comparisonResults.isNotEmpty())
        assertTrue(
            state.comparisonResults.none { it.text == completion?.translatedText },
            "the secondary rows are separate from what the completion carries"
        )
    }

    @Test
    fun `only the current request leaves the use case with a completion`() = runTest {
        val translator = GatedTranslator(holdText = "hello")
        val useCase = useCase(this, translator)
        var state = MainState(inputText = "hello")
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        val first = async { useCase.translate(getState = { state }, update = update) }
        // Wait until the first request is inside the provider, so the second really does supersede it.
        translator.awaitHeldRequestStarted()

        useCase.cancel()
        state = state.copy(inputText = "again")

        val second = useCase.translate(getState = { state }, update = update)
        assertEquals("t:again", second?.translatedText, "the newer request answers")
        assertTrue(second != null && useCase.isCurrent(second.requestId))

        translator.release()
        assertNull(first.await(), "a request that no longer owns the translation returns nothing")
    }

    private fun useCase(
        scope: CoroutineScope,
        primary: Translator,
        comparison: Translator? = null
    ): TranslateTextUseCase {
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to primary.key),
            comparisonTranslatorIds = listOfNotNull(comparison?.key),
        )
        val settings = MutableStateFlow(
            Configuration.DEFAULT.copy(servicePresets = listOf(preset), activeServicePresetId = preset.id)
        )
        val services = buildMap<String, Service> {
            put(primary.key, primary)
            comparison?.let { put(it.key, it) }
        }
        val manager = ActiveServiceManager(MutableStateFlow(services), settings)
        val directory = Files.createTempDirectory("qtranslate-auto-copy-completion").toFile()
        directories += directory
        return TranslateTextUseCase(
            scope = scope,
            settingsState = settings,
            activeServiceManager = manager,
            historyRepository = HistoryRepository(directory, SilentLogger, Json),
            summarizeUseCase = SummarizeUseCase(manager, SilentFactory),
            rewriteUseCase = RewriteUseCase(manager, SilentFactory),
            loggerFactory = SilentFactory,
            parallelComparisonUseCase = comparison?.let { ParallelComparisonUseCase(scope, manager, SilentFactory) },
        )
    }

    private suspend fun TranslateTextUseCase.translate(
        getState: () -> MainState,
        update: (MainState.() -> MainState) -> Unit,
        comparisonPolicy: ComparisonPolicy = ComparisonPolicy.DISABLED
    ) = invoke(
        getState = getState,
        updateState = update,
        onStatusUpdate = { _, _, _ -> },
        comparisonPolicy = comparisonPolicy,
    )

    private suspend fun TranslateTextUseCase.translate(
        update: (MainState.() -> MainState) -> Unit,
        comparisonPolicy: ComparisonPolicy = ComparisonPolicy.DISABLED
    ) = translate(getState = { MainState(inputText = "hello") }, update = update, comparisonPolicy = comparisonPolicy)

    private class EchoTranslator(override val key: String = "t") : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            Ok(TranslationResponse("$key:${request.text}"))
    }

    private class FailingTranslator : Translator {
        override val key = "failing"
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            Err(ServiceError.NetworkError("down"))
    }

    private class RetryingTranslator(private val succeedAt: Int = 3) : Translator {
        override val key = "retry"
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        var attempts = 0
            private set

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
            for (attempt in 1..MAX_ATTEMPTS) {
                attempts = attempt
                if (attempt == succeedAt) return Ok(TranslationResponse("recovered:${request.text}"))
            }
            return Err(ServiceError.NetworkError("no endpoint answered"))
        }

        private companion object {
            const val MAX_ATTEMPTS = 4
        }
    }

    /** Holds the request for [holdText] at the provider until the test lets it go. */
    private class GatedTranslator(private val holdText: String) : Translator {
        override val key = "t"
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        private val started = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
            if (request.text == holdText) {
                started.complete(Unit)
                gate.await()
            }
            return Ok(TranslationResponse("$key:${request.text}"))
        }

        /** Suspends until the held request has actually reached the provider. */
        suspend fun awaitHeldRequestStarted() = started.await()

        fun release() = gate.complete(Unit)
    }

    private object SilentLogger : com.github.ahatem.qtranslate.api.core.Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private object SilentFactory : com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory {
        override fun getLogger(name: String) = SilentLogger
    }
}
