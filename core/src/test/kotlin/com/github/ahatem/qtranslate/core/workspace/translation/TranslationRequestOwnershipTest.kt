package com.github.ahatem.qtranslate.core.workspace.translation

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
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One translation run observes one request snapshot, and comparison/translation ownership never
 * acquires each other's locks in opposite orders.
 */
class TranslationRequestOwnershipTest {
    private val directories = mutableListOf<java.io.File>()

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun `a suspended translation keeps the source and target it started with`() = runTest {
        val fixture = fixture(this)
        fixture.primary.release = CompletableDeferred()
        var state = MainState(
            inputText = "hello",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.FRENCH,
        )
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        launch {
            fixture.useCase(
                getState = { state },
                updateState = update,
                onStatusUpdate = { _, _, _ -> },
            )
        }
        runCurrent()
        assertTrue(fixture.primary.started.isCompleted)

        // The user edits both the state and the configuration while the request is suspended.
        state = state.copy(sourceLanguage = LanguageCode.SPANISH, targetLanguage = LanguageCode.GERMAN)
        fixture.settings.value = fixture.settings.value.copy(isHistoryEnabled = false)
        fixture.primary.release?.complete(Unit)
        advanceUntilIdle()

        val request = fixture.primary.requests.single()
        assertEquals(LanguageCode.ENGLISH, request.sourceLanguage, "the run keeps its captured source")
        assertEquals(LanguageCode.FRENCH, request.targetLanguage, "the run keeps its captured target")
        assertEquals("fr", state.history.single().targetLanguage, "history records what executed")
        assertEquals("translated", state.translatedText)
    }

    @Test
    fun `comparison invalidation and cancellation do not deadlock`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fixture = fixture(scope, withComparison = true)
            fixture.comparison.release = CompletableDeferred()
            val state = java.util.concurrent.atomic.AtomicReference(
                MainState(
                    inputText = "hello",
                    sourceLanguage = LanguageCode.ENGLISH,
                    targetLanguage = LanguageCode.FRENCH,
                )
            )

            val translation = scope.launch {
                fixture.useCase(
                    getState = { state.get() },
                    updateState = { transform -> state.updateAndGet { it.transform() } },
                    onStatusUpdate = { _, _, _ -> },
                )
            }
            withTimeout(2_000) { fixture.comparison.started.await() }

            // Real threads: a lock-order inversion between translation publication and
            // comparison ownership would block here instead of being hidden by reentrancy.
            withTimeout(2_000) {
                fixture.useCase.invalidateComparisons()
                fixture.useCase.cancel()
            }
            fixture.comparison.release?.complete(Unit)
            withTimeout(2_000) { translation.cancelAndJoin() }
        } finally {
            scope.cancel()
        }
    }

    private fun fixture(
        scope: CoroutineScope,
        withComparison: Boolean = false,
    ): Fixture {
        val primary = ControllableTranslator("primary")
        val comparison = ControllableTranslator("comparison")
        val services = buildMap {
            put(primary.key, primary)
            if (withComparison) put(comparison.key, comparison)
        }
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to primary.key),
            comparisonTranslatorIds = if (withComparison) listOf(comparison.key) else emptyList(),
        )
        val settings = MutableStateFlow(
            Configuration.DEFAULT.copy(servicePresets = listOf(preset), activeServicePresetId = preset.id)
        )
        val manager = ActiveServiceManager(MutableStateFlow(services), settings)
        val directory = Files.createTempDirectory("qtranslate-request-ownership").toFile()
        directories += directory

        return Fixture(
            useCase = TranslateTextUseCase(
                scope = scope,
                settingsState = settings,
                activeServiceManager = manager,
                historyRepository = HistoryRepository(directory, SilentLogger, Json),
                summarizeUseCase = SummarizeUseCase(manager, SilentFactory),
                rewriteUseCase = RewriteUseCase(manager, SilentFactory),
                loggerFactory = SilentFactory,
                parallelComparisonUseCase = ParallelComparisonUseCase(scope, manager, SilentFactory),
            ),
            primary = primary,
            comparison = comparison,
            settings = settings,
        )
    }

    private data class Fixture(
        val useCase: TranslateTextUseCase,
        val primary: ControllableTranslator,
        val comparison: ControllableTranslator,
        val settings: MutableStateFlow<Configuration>,
    )

    private class ControllableTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        val requests = mutableListOf<TranslationRequest>()
        val started = CompletableDeferred<Unit>()
        var release: CompletableDeferred<Unit>? = null

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
            requests += request
            started.complete(Unit)
            release?.await()
            return Ok(TranslationResponse("translated"))
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
