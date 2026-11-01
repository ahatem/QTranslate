package com.github.ahatem.qtranslate.core.workspace.translation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonTranslationResult
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


@OptIn(ExperimentalCoroutinesApi::class)
class ParallelComparisonUseCaseTest {

    @Test
    fun `providers start concurrently and results keep configured order`() = runTest {
        val first = ControlledTranslator("first")
        val second = ControlledTranslator("second")
        val useCase = useCase(this, listOf(first, second))
        var state = emptyList<ComparisonTranslationResult>()

        useCase.start(request(listOf("second", "first", "second")), 1) { state = it }
        runCurrent()

        assertTrue(first.started.isCompleted)
        assertTrue(second.started.isCompleted)
        second.release.complete(Unit)
        first.release.complete(Unit)
        runCurrent()

        assertEquals(listOf("second", "first"), state.map { it.serviceId })
        assertEquals(listOf(ComparisonStatus.SUCCESS, ComparisonStatus.SUCCESS), state.map { it.status })
    }

    @Test
    fun `one provider failure leaves its sibling successful`() = runTest {
        val failed = ControlledTranslator("failed", failure = true)
        val successful = ControlledTranslator("successful")
        val useCase = useCase(this, listOf(failed, successful))
        var state = emptyList<ComparisonTranslationResult>()

        useCase.start(request(listOf("failed", "successful")), 1) { state = it }
        runCurrent()
        failed.release.complete(Unit)
        successful.release.complete(Unit)
        runCurrent()

        assertEquals(ComparisonStatus.FAILURE, state[0].status)
        assertEquals(ComparisonStatus.SUCCESS, state[1].status)
    }

    @Test
    fun `unresolvable configured id is skipped instead of rendered as a fake card`() = runTest {
        val live = ControlledTranslator("live")
        val useCase = useCase(this, listOf(live))
        var state = emptyList<ComparisonTranslationResult>()

        useCase.start(request(listOf("live", "ghost", "live")), 1) { state = it }
        runCurrent()
        live.release.complete(Unit)
        runCurrent()

        // "ghost" stays configured but never resolves: no card, no execution.
        assertEquals(listOf("live"), state.map { it.serviceId })
        assertEquals(ComparisonStatus.SUCCESS, state.single().status)
    }

    @Test
    fun `independent provider cancellation becomes failure while sibling succeeds`() = runTest {
        val cancelled = ControlledTranslator("cancelled", cancelIndependently = true)
        val successful = ControlledTranslator("successful")
        val useCase = useCase(this, listOf(cancelled, successful))
        var state = emptyList<ComparisonTranslationResult>()

        useCase.start(request(listOf("cancelled", "successful")), 1) { state = it }
        runCurrent()
        cancelled.release.complete(Unit)
        successful.release.complete(Unit)
        runCurrent()

        assertEquals(ComparisonStatus.FAILURE, state[0].status)
        assertEquals(ComparisonStatus.SUCCESS, state[1].status)
        assertEquals(0, state.count { it.status == ComparisonStatus.LOADING })
    }

    @Test
    fun `stale non cooperative completion cannot publish after invalidation`() = runTest {
        val stale = ControlledTranslator("stale", ignoreCancellation = true)
        val useCase = useCase(this, listOf(stale))
        var state = emptyList<ComparisonTranslationResult>()

        useCase.start(request(listOf("stale")), 1) { state = it }
        runCurrent()
        useCase.invalidate()
        stale.release.complete(Unit)
        runCurrent()

        assertEquals(ComparisonStatus.LOADING, state.single().status)
    }

    @Test
    fun `stale delayed AUTO-rule start cannot replace newer comparison ownership`() = runTest {
        val newer = ControlledTranslator("newer")
        val stale = ControlledTranslator("stale")
        val useCase = useCase(this, listOf(newer, stale))
        var state = emptyList<ComparisonTranslationResult>()

        // Generation 2 represents B after its primary translation established the screen.
        useCase.begin(2)
        useCase.start(request(listOf("newer")), 2) { state = it }
        runCurrent()
        assertTrue(newer.started.isCompleted)

        // This is A's delayed AUTO/rule startup arriving after B owns the executor.
        useCase.start(request(listOf("stale")), 1) { state = it }
        runCurrent()
        assertTrue(!stale.started.isCompleted)

        newer.release.complete(Unit)
        runCurrent()

        assertTrue(!newer.cancelled)
        assertEquals(listOf("newer"), state.map { it.serviceId })
        assertEquals(ComparisonStatus.SUCCESS, state.single().status)
    }

    @Test
    fun `stale rule-retranslation completion cannot cancel newer comparison`() = runTest {
        val newer = ControlledTranslator("newer")
        val stale = ControlledTranslator("stale")
        val useCase = useCase(this, listOf(newer, stale))
        var state = emptyList<ComparisonTranslationResult>()

        useCase.begin(2)
        useCase.start(request(listOf("newer")), 2) { state = it }
        runCurrent()
        assertTrue(newer.started.isCompleted)

        // A's non-cooperative rule retranslation completes late and attempts its delayed start.
        val staleCompletion = launch {
            withContext(NonCancellable) { stale.release.await() }
            useCase.start(request(listOf("stale")), 1) { state = it }
        }
        stale.release.complete(Unit)
        runCurrent()
        staleCompletion.join()
        assertTrue(!stale.started.isCompleted)

        newer.release.complete(Unit)
        runCurrent()

        assertTrue(!newer.cancelled)
        assertEquals(listOf("newer"), state.map { it.serviceId })
        assertEquals(ComparisonStatus.SUCCESS, state.single().status)
    }

    @Test
    fun `stale start cannot publish loading rows after invalidation`() = runBlocking {
        val live = ControlledTranslator("live")
        val services = MutableStateFlow<Map<String, Service>>(mapOf("live" to live))
        val resolutionEntered = CountDownLatch(1)
        val resolutionRelease = CountDownLatch(1)

        // Resolving services happens after start() has claimed the generation but before it
        // publishes its initial rows. Parking that read forces the exact interleaving: the
        // generation is invalidated and the comparison state cleared while start is in flight.
        val gatedServices = object : StateFlow<Map<String, Service>> {
            override val value: Map<String, Service>
                get() {
                    resolutionEntered.countDown()
                    resolutionRelease.await()
                    return services.value
                }

            override val replayCache: List<Map<String, Service>>
                get() = services.replayCache

            override suspend fun collect(collector: FlowCollector<Map<String, Service>>): Nothing =
                services.collect(collector)
        }

        val manager = ActiveServiceManager(gatedServices, MutableStateFlow(Configuration.DEFAULT))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val useCase = ParallelComparisonUseCase(scope, manager, TestLoggerFactory)
            val state = AtomicReference<List<ComparisonTranslationResult>>(emptyList())

            val start = scope.launch {
                useCase.start(request(listOf("live")), 1) { state.set(it) }
            }
            assertTrue(resolutionEntered.await(5, TimeUnit.SECONDS))

            useCase.invalidate(1)
            state.set(emptyList())
            resolutionRelease.countDown()
            start.join()

            assertTrue(
                state.get().isEmpty(),
                "a stale start must not resurrect LOADING rows after the state was cleared"
            )
        } finally {
            resolutionRelease.countDown()
            scope.cancel()
        }
    }

    private fun request(ids: List<String>) = ParallelComparisonRequest(
        text = "hello",
        sourceLanguage = LanguageCode.ENGLISH,
        targetLanguage = LanguageCode.ARABIC,
        primaryTranslatorId = "primary",
        translatorIds = ids
    )

    private fun useCase(
        scope: CoroutineScope,
        translators: List<ControlledTranslator>,
    ): ParallelComparisonUseCase {
        val services = translators.associateBy { it.key } + ("primary" to ControlledTranslator("primary"))
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(com.github.ahatem.qtranslate.api.plugin.ServiceRole.TRANSLATOR to "primary")
        )
        val config = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset),
            activeServicePresetId = preset.id
        )
        return ParallelComparisonUseCase(
            scope = scope,
            activeServiceManager = ActiveServiceManager(
                MutableStateFlow(services),
                MutableStateFlow(config)
            ),
            loggerFactory = TestLoggerFactory
        )
    }

    private class ControlledTranslator(
        override val key: String,
        private val failure: Boolean = false,
        private val ignoreCancellation: Boolean = false,
        private val cancelIndependently: Boolean = false
    ) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false

        override suspend fun translate(request: TranslationRequest): com.github.michaelbull.result.Result<TranslationResponse, ServiceError> {
            started.complete(Unit)
            if (ignoreCancellation) {
                withContext(NonCancellable) { release.await() }
            } else {
                try {
                    release.await()
                } catch (cancellation: CancellationException) {
                    cancelled = true
                    throw cancellation
                }
            }
            if (cancelIndependently) throw CancellationException("provider aborted")
            return if (failure) Err(ServiceError.NetworkError("failed")) else Ok(TranslationResponse(key))
        }
    }

    private object TestLoggerFactory : LoggerFactory {
        private val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }

        override fun getLogger(name: String): Logger = logger
    }
}
