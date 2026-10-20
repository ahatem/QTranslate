package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.core.audio.AudioPlayer
import com.github.ahatem.qtranslate.core.document.DocumentTranslationUseCase
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.domain.usecase.CheckForUpdatesUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.FetchInlineDefinitionUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.HandleTextToSpeechUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.LookupWordUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.OcrAndTranslateUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.ParallelComparisonUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.PerformSpellCheckUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.RewriteUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SearchImagesUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SelectActiveServiceUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SummarizeUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SwapLanguagesUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslateTextUseCase
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.updater.Updater
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Instant Translation runs in the main window, so it follows the main window's Comparison policy.
 *
 * Before the fix it always ran with the Comparison policy disabled: every instant translation
 * wiped the comparison rows of the layout and never put them back, so the Comparison board showed
 * the primary translator alone (issue #326).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstantComparisonResultsTest {

    @Test
    fun `an instant translation fills the comparison board when the comparison layout is active`() = runTest {
        val fixture = Fixture(this, LayoutPresetIds.COMPARISON)
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            advanceUntilIdle()

            val state = fixture.store.state.value
            assertEquals("t:hello", state.translatedText)
            val row = state.comparisonResults.single()
            assertEquals("bing", row.serviceId)
            assertEquals(ComparisonStatus.SUCCESS, row.status)
            assertEquals("bing:hello", row.text)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `an instant translation leaves the comparison board empty when the layout is classic`() = runTest {
        val comparison = EchoTranslator("bing")
        val fixture = Fixture(this, LayoutPresetIds.CLASSIC, comparisonTranslators = listOf(comparison))
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            advanceUntilIdle()

            val state = fixture.store.state.value
            assertEquals("t:hello", state.translatedText)
            assertTrue(state.comparisonResults.isEmpty(), "the classic layout has no comparison rows")
            assertTrue(comparison.requested.isEmpty(), "no comparison translator may run")
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `an instant translation falls back to the classic layout when only one translator is effective`() = runTest {
        // The comparison translator is loaded, but disabled for translation: the saved Comparison
        // layout has no second effective translator, so the layout falls back to Classic and the
        // instant translation must not run a comparison.
        val comparison = EchoTranslator("bing")
        val fixture = Fixture(
            testScope = this,
            layoutPresetId = LayoutPresetIds.COMPARISON,
            comparisonTranslators = listOf(comparison),
            disabledServices = setOf(comparison.key),
        )
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            advanceUntilIdle()

            val state = fixture.store.state.value
            assertEquals("t:hello", state.translatedText)
            assertTrue(state.comparisonResults.isEmpty(), "an ineligible comparison layout has no rows")
            assertTrue(comparison.requested.isEmpty(), "the comparison translator was never asked to translate")
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `clearing the input clears the instant comparison results`() = runTest {
        val fixture = Fixture(this, LayoutPresetIds.COMPARISON)
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            advanceUntilIdle()
            assertEquals(1, fixture.store.state.value.comparisonResults.size)

            fixture.store.dispatch(MainIntent.UpdateInputText(""))
            advanceUntilIdle()

            val state = fixture.store.state.value
            assertTrue(state.comparisonResults.isEmpty())
            assertEquals("", state.translatedText)
            assertFalse(state.isLoading)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a stale instant comparison cannot publish over the newer one`() = runTest {
        val comparison = GatedTranslator("bing")
        val fixture = Fixture(this, LayoutPresetIds.COMPARISON, comparisonTranslators = listOf(comparison))
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            awaitState("the first comparison is in flight") { comparison.started("hello") }
            assertTrue(comparison.started("hello"), "the first comparison is in flight")

            // A newer input supersedes the in-flight comparison.
            fixture.store.dispatch(MainIntent.UpdateInputText("world"))
            awaitState("the second comparison is in flight") { comparison.started("world") }
            assertTrue(comparison.started("world"), "the second comparison is in flight")

            // The superseded provider answers late. It is deliberately non-cooperative, so it
            // really produces its answer after the newer generation took ownership: the
            // publish-side generation guard is what has to reject it.
            comparison.release("hello")
            awaitState("the stale answer has been produced") { comparison.answered("hello") }
            val afterStale = fixture.store.state.value.comparisonResults
            assertTrue(
                afterStale.none { it.text == "bing:hello" },
                "the stale result must not publish"
            )
            assertEquals(1, afterStale.size, "the stale result must not add a row")
            assertEquals(
                ComparisonStatus.LOADING,
                afterStale.single().status,
                "the stale answer must not complete the row it would have landed in"
            )
            assertEquals("", afterStale.single().text)

            comparison.release("world")
            awaitState("the fresh answer has been published") {
                fixture.store.state.value.comparisonResults.singleOrNull()?.status == ComparisonStatus.SUCCESS
            }
            val row = fixture.store.state.value.comparisonResults.single()
            assertEquals(ComparisonStatus.SUCCESS, row.status)
            assertEquals("bing:world", row.text)
        } finally {
            comparison.releaseAll()
            fixture.cleanUp()
        }
    }

    /**
     * Advances virtual time until [condition] holds.
     *
     * The instant translation is debounced, and the debounce restarts whenever the store emits a
     * new state, so the number of virtual-time steps it takes to reach a comparison is not fixed:
     * one `advanceTimeBy` of the debounce interval is not always enough. Advancing in
     * debounce-sized steps until the expected state is actually observed keeps the test
     * independent of that scheduling detail. The bound keeps a genuine failure from hanging.
     */
    private fun TestScope.awaitState(description: String, condition: () -> Boolean) {
        repeat(MAX_AWAIT_STEPS) {
            if (condition()) return
            advanceTimeBy(AppConstants.INSTANT_TRANSLATION_DEBOUNCE_MS)
            runCurrent()
        }
    }

    @Test
    fun `a failed primary keeps the instant comparison results`() = runTest {
        val fixture = Fixture(this, LayoutPresetIds.COMPARISON, primaryFails = true)
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            advanceUntilIdle()

            val state = fixture.store.state.value
            assertTrue(state.translationFailed, "the primary failed")
            assertEquals("", state.translatedText)
            // #367: the comparison results survive a primary failure, so the secondary result
            // that did work stays visible next to the primary error.
            val row = state.comparisonResults.single()
            assertEquals(ComparisonStatus.SUCCESS, row.status)
            assertEquals("bing:hello", row.text)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `the instant comparison rows keep the configured order`() = runTest {
        val zeta = EchoTranslator("zeta")
        val alpha = EchoTranslator("alpha")
        val fixture = Fixture(
            testScope = this,
            layoutPresetId = LayoutPresetIds.COMPARISON,
            comparisonTranslators = listOf(zeta, alpha),
        )
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            advanceUntilIdle()

            val rows = fixture.store.state.value.comparisonResults
            assertEquals(
                listOf("zeta", "alpha"),
                rows.map { it.serviceId },
                "the board follows the configured comparison order, not the registry order"
            )
            assertEquals(listOf(ComparisonStatus.SUCCESS, ComparisonStatus.SUCCESS), rows.map { it.status })
            assertEquals(listOf("zeta:hello", "alpha:hello"), rows.map { it.text })
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a comparison configuration change invalidates the instant comparison board`() = runTest {
        val fixture = Fixture(this, LayoutPresetIds.COMPARISON)
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            advanceUntilIdle()
            assertEquals(1, fixture.store.state.value.comparisonResults.size)

            fixture.updateConfiguration { config ->
                config.copy(
                    servicePresets = config.servicePresets.map { preset ->
                        preset.copy(comparisonTranslatorIds = preset.comparisonTranslatorIds + "second")
                    }
                )
            }
            advanceUntilIdle()

            assertTrue(
                fixture.store.state.value.comparisonResults.isEmpty(),
                "a changed comparison configuration invalidates the board"
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a manual translation clears and repopulates the instant comparison board`() = runTest {
        val comparison = EchoTranslator("bing")
        val fixture = Fixture(this, LayoutPresetIds.COMPARISON, comparisonTranslators = listOf(comparison))
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            advanceUntilIdle()
            assertEquals(1, fixture.store.state.value.comparisonResults.size)

            fixture.store.dispatch(MainIntent.Translate())
            // The manual path clears the board before it starts its own translation.
            assertTrue(
                fixture.store.state.value.comparisonResults.isEmpty(),
                "the manual translation clears the board it inherited"
            )

            advanceUntilIdle()
            val rows = fixture.store.state.value.comparisonResults
            assertEquals(1, rows.size, "the board holds one row per comparison translator")
            assertEquals(ComparisonStatus.SUCCESS, rows.single().status)
            assertEquals("bing:hello", rows.single().text)
            assertEquals(
                listOf("hello", "hello"),
                comparison.requested,
                "the manual translation runs the comparison again"
            )
        } finally {
            fixture.cleanUp()
        }
    }

    private companion object {
        /**
         * How many debounce-sized virtual-time steps an awaited state may take.
         *
         * Twenty steps of the instant-translation debounce is well under the translation
         * timeout, and it only ever exists to turn a scheduling detail into a bounded wait.
         */
        const val MAX_AWAIT_STEPS = 20

        val SILENT_LOGGER = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }

        val SILENT_FACTORY = object : LoggerFactory {
            override fun getLogger(name: String): Logger = SILENT_LOGGER
        }
    }

    /**
     * A main window with one primary translator and the given comparison translators.
     *
     * Auto-update checks are off and no TTS or OCR service is registered, so nothing reaches
     * the network. The store observes several flows for the life of the application, so the
     * fixture gives it a scope of its own and cancels that at the end.
     */
    private class Fixture(
        private val testScope: TestScope,
        layoutPresetId: String,
        primaryFails: Boolean = false,
        val comparisonTranslators: List<Translator> = listOf(EchoTranslator("bing")),
        disabledServices: Set<String> = emptySet(),
    ) {
        private val scope = CoroutineScope(
            SupervisorJob() + testScope.coroutineContext[ContinuationInterceptor]!!
        )
        private val directory = Files.createTempDirectory("qtranslate-instant-comparison").toFile()
        private val httpClient = HttpClient()
        private val primary = EchoTranslator("t", fail = primaryFails)
        private val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to primary.key),
            comparisonTranslatorIds = comparisonTranslators.map { it.key }
        )
        private val settings = MutableStateFlow(
            Configuration.DEFAULT.copy(
                servicePresets = listOf(preset),
                activeServicePresetId = preset.id,
                layoutPresetId = layoutPresetId,
                disabledServices = disabledServices,
                autoCheckForUpdates = false,
                isInstantTranslationEnabled = true,
            )
        )
        private val services: Map<String, Service> = mapOf(primary.key to primary) +
            comparisonTranslators.associateBy { it.key }
        private val manager = ActiveServiceManager(MutableStateFlow(services), settings)

        val store = MainStore(
            scope = scope,
            settingsState = settings,
            historyRepository = HistoryRepository(directory, SILENT_LOGGER, Json),
            checkForUpdatesUseCase = CheckForUpdatesUseCase(
                currentVersion = "0.0.0",
                settingsState = settings,
                updater = Updater("ahatem", "QTranslate", httpClient, SILENT_LOGGER),
                notificationBus = NotificationBus(),
                loggerFactory = SILENT_FACTORY,
            ),
            handleTextToSpeechUseCase = HandleTextToSpeechUseCase(manager, settings, SilentAudioPlayer, SILENT_FACTORY),
            performSpellCheckUseCase = PerformSpellCheckUseCase(manager, SILENT_FACTORY),
            selectActiveServiceUseCase = SelectActiveServiceUseCase(
                activeServices = MutableStateFlow(services),
                settingsState = settings,
                activeServiceManager = manager,
                scope = scope,
                loggerFactory = SILENT_FACTORY,
            ),
            translateTextUseCase = TranslateTextUseCase(
                scope = scope,
                settingsState = settings,
                activeServiceManager = manager,
                historyRepository = HistoryRepository(directory, SILENT_LOGGER, Json),
                summarizeUseCase = SummarizeUseCase(manager, SILENT_FACTORY),
                rewriteUseCase = RewriteUseCase(manager, SILENT_FACTORY),
                loggerFactory = SILENT_FACTORY,
                parallelComparisonUseCase = ParallelComparisonUseCase(scope, manager, SILENT_FACTORY),
            ),
            swapLanguagesUseCase = SwapLanguagesUseCase(),
            ocrAndTranslateUseCase = OcrAndTranslateUseCase(manager, SILENT_FACTORY),
            summarizeUseCase = SummarizeUseCase(manager, SILENT_FACTORY),
            rewriteUseCase = RewriteUseCase(manager, SILENT_FACTORY),
            lookupWordUseCase = LookupWordUseCase(scope, manager, SILENT_FACTORY),
            searchImagesUseCase = SearchImagesUseCase(scope, manager, SILENT_FACTORY),
            fetchInlineDefinitionUseCase = FetchInlineDefinitionUseCase(scope, manager, SILENT_FACTORY),
            documentTranslationUseCase = DocumentTranslationUseCase(manager, SILENT_FACTORY),
        )

        /** Applies a configuration change the way the Settings store would. */
        fun updateConfiguration(transform: (Configuration) -> Configuration) {
            settings.value = transform(settings.value)
        }

        fun cleanUp() {
            scope.cancel()
            httpClient.close()
            directory.deleteRecursively()
        }
    }

    private class EchoTranslator(
        override val key: String,
        private val fail: Boolean = false
    ) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        val requested = mutableListOf<String>()

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
            requested += request.text
            return if (fail) Err(ServiceError.NetworkError("down"))
            else Ok(TranslationResponse("$key:${request.text}"))
        }
    }

    /**
     * Holds every request until it is released, so a late answer can be arranged.
     *
     * The release is awaited inside [NonCancellable] on purpose: this fake is deliberately
     * non-cooperative, so a superseded request still produces its answer after the newer
     * generation took ownership. The board must then be protected by the generation guard
     * rather than by the request simply never coming back.
     */
    private class GatedTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val answered = mutableSetOf<String>()

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
            withContext(NonCancellable) {
                pending.getOrPut(request.text) { CompletableDeferred() }.await()
                // Recorded inside the non-cancellable block: the answer really was produced, even
                // when the caller was cancelled while waiting for it.
                answered.add(request.text)
            }
            return Ok(TranslationResponse("$key:${request.text}"))
        }

        fun started(text: String): Boolean = pending.containsKey(text)

        /** True once the provider has produced its answer for [text]. */
        fun answered(text: String): Boolean = text in answered

        fun release(text: String) {
            pending[text]?.complete(Unit)
        }

        fun releaseAll() {
            pending.values.forEach { it.complete(Unit) }
        }
    }

    private object SilentAudioPlayer : AudioPlayer {
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override fun play(audio: TTSAudio.Bytes) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }
}
