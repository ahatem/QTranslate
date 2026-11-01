package com.github.ahatem.qtranslate.core.workspace.translation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
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
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
        assertNull(state.extraOutputFailure)
        assertTrue(state.history.isEmpty())
        assertTrue(
            fixture.summarizer.cancelledSignal.isCompleted,
            "turning the panel off must cancel the provider call, not just suppress its result"
        )
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
        assertTrue(
            fixture.summarizer.cancelledSignal.isCompleted,
            "cancel must reach the live provider call"
        )
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
        assertNull(state.extraOutputFailure)
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
        assertTrue(
            fixture.summarizer.cancelledSignal.isCompleted,
            "a mode change must cancel the inline provider call it supersedes"
        )
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
        assertEquals(ExtraOutputFailure.Provider("provider exploded"), state.extraOutputFailure)
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
        assertNull(state.extraOutputFailure)
    }

    @Test
    fun `an early return normalizes the loading and failure flags`() = runTest {
        val fixture = fixture(this)
        var state = MainState(
            inputText = "input",
            translatedText = "",
            extraOutputFailed = true,
            extraOutputFailure = ExtraOutputFailure.Provider("old failure"),
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
        assertNull(state.extraOutputFailure)
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
        assertEquals(ExtraOutputFailure.NoTranslator, state.extraOutputFailure)
        assertFalse(state.isExtraOutputLoading)
    }

    @Test
    fun `a successful backward translation ends the temporary status`() = runTest {
        val fixture = fixture(this)
        var state = translatedState()
        val statuses = mutableListOf<Triple<StatusCode, NotificationType, Boolean>>()
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.BackwardTranslate),
            getState = { state },
            updateState = update,
            onStatusUpdate = { code, type, temporary -> statuses += Triple(code, type, temporary) },
        )
        advanceUntilIdle()

        assertEquals("backward", state.extraOutputText)
        assertEquals(StatusCode.PerformingBackwardTranslation, statuses.first().first)
        assertEquals(
            Triple(StatusCode.BackwardTranslationComplete, NotificationType.SUCCESS, true),
            statuses.last(),
            "the temporary progress status must be replaced by a temporary completion"
        )
    }

    @Test
    fun `a throwing inline provider fails only the secondary output`() = runTest {
        val fixture = fixture(this)
        fixture.summarizer.throws = true
        var state = MainState(
            inputText = "hello",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.ARABIC,
        )
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        fixture.useCase(
            getState = { state },
            updateState = update,
            onStatusUpdate = { _, _, _ -> },
            extraOutputRequest = request(ExtraOutputType.Summarize),
        )
        advanceUntilIdle()

        assertEquals("backward", state.translatedText, "the primary translation still succeeded")
        assertFalse(state.translationFailed)
        assertTrue(state.extraOutputFailed)
        assertEquals(ExtraOutputFailure.Unexpected, state.extraOutputFailure)
    }

    @Test
    fun `a backward failure is typed and reported as an error status`() = runTest {
        val fixture = fixture(this)
        fixture.translator.result = Err(ServiceError.InvalidResponseError("provider exploded"))
        var state = translatedState()
        val statuses = mutableListOf<StatusCode>()
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.BackwardTranslate),
            getState = { state },
            updateState = update,
            onStatusUpdate = { code, _, _ -> statuses += code },
        )
        advanceUntilIdle()

        assertEquals(ExtraOutputFailure.Provider("provider exploded"), state.extraOutputFailure)
        assertTrue(state.extraOutputFailed)
        assertEquals(StatusCode.BackwardTranslationFailed("provider exploded"), statuses.last())
        assertTrue(
            statuses.none { it == StatusCode.TranslationComplete },
            "a failed secondary request must not announce success"
        )
    }

    @Test
    fun `a backward timeout is typed and never announced as success`() = runTest {
        val fixture = fixture(this)
        fixture.translator.hold = CompletableDeferred()
        var state = translatedState()
        val statuses = mutableListOf<StatusCode>()
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        fixture.useCase.refreshExtraOutput(
            extraOutputRequest = request(ExtraOutputType.BackwardTranslate),
            getState = { state },
            updateState = update,
            onStatusUpdate = { code, _, _ -> statuses += code },
        )
        runCurrent()
        advanceTimeBy(AppConstants.TRANSLATION_TIMEOUT_MS + 1)
        runCurrent()

        assertEquals(ExtraOutputFailure.Timeout, state.extraOutputFailure)
        assertTrue(state.extraOutputFailed)
        assertEquals(StatusCode.BackwardTranslationTimeout, statuses.last())
        assertTrue(
            statuses.none { it == StatusCode.TranslationComplete },
            "a timed-out secondary request must not announce success"
        )
    }

    @Test
    fun `a superseding refresh cancels the provider call it replaces`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val fixture = fixture(scope)
            fixture.summarizer.release = CompletableDeferred()
            var state = translatedState()
            val rewritten = CompletableDeferred<Unit>()
            val update: (MainState.() -> MainState) -> Unit = { transform ->
                state = state.transform()
                if (state.extraOutputText == "rewrite") rewritten.complete(Unit)
            }

            fixture.useCase.refreshExtraOutput(
                extraOutputRequest = request(ExtraOutputType.Summarize),
                getState = { state },
                updateState = update,
                onStatusUpdate = { _, _, _ -> },
            )
            withTimeout(5_000) { fixture.summarizer.started.await() }

            // The newer request must take over the slot *and* cancel the request it supersedes,
            // not merely prevent its result from publishing.
            fixture.useCase.refreshExtraOutput(
                extraOutputRequest = request(ExtraOutputType.Rewrite),
                getState = { state },
                updateState = update,
                onStatusUpdate = { _, _, _ -> },
            )

            withTimeout(5_000) { fixture.summarizer.cancelledSignal.await() }
            withTimeout(5_000) { rewritten.await() }

            assertEquals("rewrite", state.extraOutputText)
            assertFalse(state.extraOutputFailed)
            assertEquals(1, fixture.summarizer.calls.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a superseded refresh cannot reopen the panel with its initial loading state`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val fixture = fixture(scope)
            fixture.summarizer.release = CompletableDeferred()
            val state = AtomicReference(translatedState())
            val initialWriteEntered = CountDownLatch(1)
            val releaseInitialWrite = CountDownLatch(1)
            val update: (MainState.() -> MainState) -> Unit = { transform ->
                val before = state.get()
                val after = before.transform()
                if (after.isExtraOutputLoading && !before.isExtraOutputLoading) {
                    // The stale refresh is about to write its initial loading state.
                    initialWriteEntered.countDown()
                    check(releaseInitialWrite.await(5, TimeUnit.SECONDS)) {
                        "the initial loading write was never released"
                    }
                }
                state.updateAndGet { it.transform() }
            }

            fixture.useCase.refreshExtraOutput(
                extraOutputRequest = request(ExtraOutputType.Summarize),
                getState = { state.get() },
                updateState = update,
                onStatusUpdate = { _, _, _ -> },
            )
            assertTrue(initialWriteEntered.await(5, TimeUnit.SECONDS), "the initial write never started")

            // The newer request clears the panel while the older one is still at its first write.
            // It either completes first (unguarded write) or waits for the panel lock (guarded).
            val switching = thread(name = "extra-output-none") {
                runBlocking {
                    fixture.useCase.refreshExtraOutput(
                        extraOutputRequest = request(ExtraOutputType.None),
                        getState = { state.get() },
                        updateState = { transform -> state.updateAndGet { it.transform() } },
                        onStatusUpdate = { _, _, _ -> },
                    )
                }
            }
            awaitBlockedOrFinished(switching)

            releaseInitialWrite.countDown()
            switching.join(5_000)
            assertFalse(switching.isAlive, "the newer request did not finish")
            withTimeout(5_000) {
                scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
            }

            assertEquals("", state.get().extraOutputText, "the stale write must not restore panel content")
            assertFalse(state.get().isExtraOutputLoading, "the stale write must not restore the spinner")
            assertFalse(state.get().extraOutputFailed)
            assertNull(state.get().extraOutputFailure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a stale primary completion cannot reopen the panel a newer mode cleared`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val fixture = fixture(scope)
            fixture.translator.result = Ok(TranslationResponse("translated"))
            val state = AtomicReference(
                MainState(
                    inputText = "hello",
                    sourceLanguage = LanguageCode.ENGLISH,
                    targetLanguage = LanguageCode.ARABIC,
                )
            )
            val primaryPublicationEntered = CountDownLatch(1)
            val releasePrimaryPublication = CountDownLatch(1)
            val update: (MainState.() -> MainState) -> Unit = { transform ->
                val before = state.get()
                val after = before.transform()
                if (after.translatedText == "translated" && !after.isLoading && before.translatedText.isEmpty()) {
                    // The translation reached its primary publication with its extra-output token
                    // still believed current.
                    primaryPublicationEntered.countDown()
                    check(releasePrimaryPublication.await(5, TimeUnit.SECONDS)) {
                        "the primary publication was never released"
                    }
                }
                state.updateAndGet { it.transform() }
            }

            val translation = scope.launch {
                fixture.useCase(
                    getState = { state.get() },
                    updateState = update,
                    onStatusUpdate = { _, _, _ -> },
                    extraOutputRequest = request(ExtraOutputType.Summarize),
                )
            }
            assertTrue(
                primaryPublicationEntered.await(5, TimeUnit.SECONDS),
                "the primary publication never started"
            )

            // Turning the panel off claims the newer token and clears it while the older
            // translation is paused at its primary completion.
            val switching = thread(name = "extra-output-none-primary") {
                runBlocking {
                    fixture.useCase.refreshExtraOutput(
                        extraOutputRequest = request(ExtraOutputType.None),
                        getState = { state.get() },
                        updateState = { transform -> state.updateAndGet { it.transform() } },
                        onStatusUpdate = { _, _, _ -> },
                    )
                }
            }
            switching.join(5_000)
            assertFalse(switching.isAlive, "turning the panel off must not wait for the primary publication")
            assertEquals("", state.get().extraOutputText)
            assertFalse(state.get().isExtraOutputLoading)

            releasePrimaryPublication.countDown()
            withTimeout(5_000) { translation.join() }
            withTimeout(5_000) {
                scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
            }

            assertEquals("translated", state.get().translatedText, "the primary result still publishes")
            assertEquals("", state.get().extraOutputText, "the stale completion must not restore panel content")
            assertFalse(state.get().isExtraOutputLoading, "the stale completion must not restore the spinner")
            assertFalse(state.get().extraOutputFailed)
            assertNull(state.get().extraOutputFailure)
            assertTrue(fixture.summarizer.calls.isEmpty(), "the superseded secondary request must never start")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a standalone refresh keeps the panel when a translation resumes its startup`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val fixture = fixture(scope)
            fixture.translator.hold = CompletableDeferred()
            val state = AtomicReference(
                MainState(
                    inputText = "hello",
                    translatedText = "old translation",
                    sourceLanguage = LanguageCode.ENGLISH,
                    targetLanguage = LanguageCode.ARABIC,
                )
            )
            val startupEntered = CountDownLatch(1)
            val releaseStartup = CountDownLatch(1)
            val secondaryPublished = CountDownLatch(1)
            val update: (MainState.() -> MainState) -> Unit = { transform ->
                val before = state.get()
                val after = before.transform()
                if (!before.isLoading && after.isLoading && after.translatedText.isEmpty()) {
                    // The translation is about to publish its startup state.
                    startupEntered.countDown()
                    check(releaseStartup.await(5, TimeUnit.SECONDS)) {
                        "the translation startup was never released"
                    }
                }
                val applied = state.updateAndGet { it.transform() }
                if (applied.extraOutputText == "summary" && !applied.isExtraOutputLoading) {
                    secondaryPublished.countDown()
                }
            }

            val translation = scope.launch {
                fixture.useCase(
                    getState = { state.get() },
                    updateState = update,
                    onStatusUpdate = { _, _, _ -> },
                    extraOutputRequest = request(ExtraOutputType.Summarize),
                )
            }
            assertTrue(startupEntered.await(5, TimeUnit.SECONDS), "the translation startup never started")

            // The standalone refresh claims the panel while the translation is paused before its
            // startup publication, and publishes a complete result.
            fixture.useCase.refreshExtraOutput(
                extraOutputRequest = request(ExtraOutputType.Summarize),
                getState = { state.get() },
                updateState = update,
                onStatusUpdate = { _, _, _ -> },
            )
            assertTrue(
                secondaryPublished.await(5, TimeUnit.SECONDS),
                "the standalone refresh never published its panel state"
            )

            releaseStartup.countDown()
            withTimeout(5_000) { fixture.translator.started.await() }

            assertTrue(state.get().isLoading, "the translation still publishes its primary startup")
            assertEquals("", state.get().translatedText)
            assertFalse(state.get().translationFailed)
            assertEquals("summary", state.get().extraOutputText, "the newer panel content survives")
            assertFalse(state.get().isExtraOutputLoading, "the translation must not reopen the newer panel")
            assertFalse(state.get().extraOutputFailed)
            assertNull(state.get().extraOutputFailure)

            translation.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a primary failure cannot lower a newer secondary request's spinner`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val fixture = fixture(scope)
            fixture.translator.result = Err(ServiceError.InvalidResponseError("primary exploded"))
            fixture.translator.hold = CompletableDeferred()
            fixture.summarizer.release = CompletableDeferred()
            val state = AtomicReference(
                MainState(
                    inputText = "hello",
                    translatedText = "old translation",
                    sourceLanguage = LanguageCode.ENGLISH,
                    targetLanguage = LanguageCode.ARABIC,
                )
            )
            val startupEntered = CountDownLatch(1)
            val releaseStartup = CountDownLatch(1)
            val secondaryPublished = CountDownLatch(1)
            val update: (MainState.() -> MainState) -> Unit = { transform ->
                val before = state.get()
                val after = before.transform()
                if (!before.isLoading && after.isLoading && after.translatedText.isEmpty()) {
                    startupEntered.countDown()
                    check(releaseStartup.await(5, TimeUnit.SECONDS)) {
                        "the translation startup was never released"
                    }
                }
                val applied = state.updateAndGet { it.transform() }
                if (applied.extraOutputText == "summary" && !applied.isExtraOutputLoading) {
                    secondaryPublished.countDown()
                }
            }

            val translation = scope.launch {
                fixture.useCase(
                    getState = { state.get() },
                    updateState = update,
                    onStatusUpdate = { _, _, _ -> },
                    extraOutputRequest = request(ExtraOutputType.Summarize),
                )
            }
            assertTrue(startupEntered.await(5, TimeUnit.SECONDS), "the translation startup never started")

            fixture.useCase.refreshExtraOutput(
                extraOutputRequest = request(ExtraOutputType.Summarize),
                getState = { state.get() },
                updateState = update,
                onStatusUpdate = { _, _, _ -> },
            )
            withTimeout(5_000) { fixture.summarizer.started.await() }
            assertTrue(state.get().isExtraOutputLoading, "the standalone refresh owns the spinner")

            releaseStartup.countDown()
            withTimeout(5_000) { fixture.translator.started.await() }

            fixture.translator.hold?.complete(Unit)
            withTimeout(5_000) { translation.join() }

            assertTrue(state.get().translationFailed)
            assertFalse(state.get().isLoading)
            assertTrue(
                state.get().isExtraOutputLoading,
                "the primary failure must not lower the newer spinner"
            )
            assertEquals("", state.get().extraOutputText)
            assertFalse(state.get().extraOutputFailed)
            assertNull(state.get().extraOutputFailure)

            fixture.summarizer.release?.complete(Unit)
            assertTrue(
                secondaryPublished.await(5, TimeUnit.SECONDS),
                "the newer refresh never published after the primary failure"
            )
            assertEquals("summary", state.get().extraOutputText)
            assertFalse(state.get().isExtraOutputLoading)
            assertFalse(state.get().extraOutputFailed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failed rule-driven retranslation cannot lower a newer secondary request's spinner`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val fixture = fixture(
                scope,
                config = Configuration.DEFAULT.copy(
                    translationRules = listOf(TranslationRule("en", "ar"))
                ),
            )
            fixture.translator.scriptedResults += Ok(
                TranslationResponse("first pass", detectedLanguage = LanguageCode.ENGLISH)
            )
            fixture.translator.scriptedResults += Err(
                ServiceError.InvalidResponseError("retry exploded")
            )
            fixture.translator.hold = CompletableDeferred()
            fixture.translator.holdOnCall = 2
            fixture.summarizer.release = CompletableDeferred()
            val state = AtomicReference(
                MainState(
                    inputText = "hello",
                    translatedText = "old translation",
                    sourceLanguage = LanguageCode.AUTO,
                    targetLanguage = LanguageCode.FRENCH,
                )
            )
            val startupEntered = CountDownLatch(1)
            val releaseStartup = CountDownLatch(1)
            val secondaryPublished = CountDownLatch(1)
            val update: (MainState.() -> MainState) -> Unit = { transform ->
                val before = state.get()
                val after = before.transform()
                if (!before.isLoading && after.isLoading && after.translatedText.isEmpty()) {
                    startupEntered.countDown()
                    check(releaseStartup.await(5, TimeUnit.SECONDS)) {
                        "the translation startup was never released"
                    }
                }
                val applied = state.updateAndGet { it.transform() }
                if (applied.extraOutputText == "summary" && !applied.isExtraOutputLoading) {
                    secondaryPublished.countDown()
                }
            }

            val translation = scope.launch {
                fixture.useCase(
                    getState = { state.get() },
                    updateState = update,
                    onStatusUpdate = { _, _, _ -> },
                    extraOutputRequest = request(ExtraOutputType.Summarize),
                    comparisonPolicy = ComparisonPolicy.DISABLED,
                )
            }
            assertTrue(startupEntered.await(5, TimeUnit.SECONDS), "the translation startup never started")

            fixture.useCase.refreshExtraOutput(
                extraOutputRequest = request(ExtraOutputType.Summarize),
                getState = { state.get() },
                updateState = update,
                onStatusUpdate = { _, _, _ -> },
            )
            withTimeout(5_000) { fixture.summarizer.started.await() }
            assertTrue(state.get().isExtraOutputLoading, "the standalone refresh owns the spinner")

            releaseStartup.countDown()
            withTimeout(5_000) { fixture.translator.secondCallStarted.await() }

            fixture.translator.hold?.complete(Unit)
            withTimeout(5_000) { translation.join() }

            assertTrue(state.get().translationFailed)
            assertFalse(state.get().isLoading)
            assertTrue(
                state.get().isExtraOutputLoading,
                "the retranslation failure must not lower the newer spinner"
            )
            assertFalse(state.get().extraOutputFailed)
            assertNull(state.get().extraOutputFailure)

            fixture.summarizer.release?.complete(Unit)
            assertTrue(
                secondaryPublished.await(5, TimeUnit.SECONDS),
                "the newer refresh never published after the retranslation failure"
            )
            assertEquals("summary", state.get().extraOutputText)
            assertFalse(state.get().isExtraOutputLoading)
            assertFalse(state.get().extraOutputFailed)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Waits until [thread] either finishes or is waiting for a monitor. The guarded write holds the
     * panel lock while it is paused, so the newer request can only be blocked there.
     */
    private fun awaitBlockedOrFinished(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.isAlive && thread.state != Thread.State.BLOCKED) {
            check(System.nanoTime() < deadline) {
                "the newer request neither finished nor waited for the panel owner"
            }
            Thread.yield()
        }
    }

    private fun request(type: ExtraOutputType) =
        ExtraOutputRequest(type, ExtraOutputSource.Output, "medium", "neutral")

    private fun translatedState() = MainState(
        translatedText = "translated",
        sourceLanguage = LanguageCode.ENGLISH,
        targetLanguage = LanguageCode.ARABIC,
    )

    private fun fixture(
        scope: CoroutineScope,
        includeTranslator: Boolean = true,
        config: Configuration = Configuration.DEFAULT,
    ): Fixture {
        val settings = MutableStateFlow(config)
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
        val started = CompletableDeferred<Unit>()
        val secondCallStarted = CompletableDeferred<Unit>()
        val scriptedResults = mutableListOf<Result<TranslationResponse, ServiceError>>()
        var result: Result<TranslationResponse, ServiceError>? = null
        var hold: CompletableDeferred<Unit>? = null
        var holdOnCall: Int? = null

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
            val call = calls.size + 1
            calls += request
            started.complete(Unit)
            if (call == 2) secondCallStarted.complete(Unit)
            if (holdOnCall == null || holdOnCall == call) hold?.await()
            return scriptedResults.removeFirstOrNull() ?: result ?: Ok(TranslationResponse("backward"))
        }
    }

    private class RecordingSummarizer : RecordingService("summarizer"), Summarizer {
        val calls = mutableListOf<SummarizeRequest>()
        val started = CompletableDeferred<Unit>()
        val cancelledSignal = CompletableDeferred<Unit>()
        var release: CompletableDeferred<Unit>? = null
        var throws: Boolean = false
        var result: Result<SummarizeResponse, ServiceError> = Ok(SummarizeResponse("summary"))

        override suspend fun summarize(request: SummarizeRequest): Result<SummarizeResponse, ServiceError> {
            calls += request
            started.complete(Unit)
            try {
                release?.await()
            } catch (cancellation: CancellationException) {
                cancelledSignal.complete(Unit)
                throw cancellation
            }
            if (throws) throw IllegalStateException("provider exploded")
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
