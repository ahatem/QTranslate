package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.audio.AudioPlayer
import com.github.ahatem.qtranslate.core.document.DocumentTranslationUseCase
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.domain.usecase.CheckForUpdatesUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.FetchInlineDefinitionUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.HandleTextToSpeechUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.LookupWordUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.OcrAndTranslateUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.PerformSpellCheckUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.RewriteUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SearchImagesUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SelectActiveServiceUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SummarizeUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SwapLanguagesUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslateTextUseCase
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.updater.Updater
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The store decides; the UI owns the clipboard. These assert the events the store actually emits,
 * through the real dispatch path.
 *
 * Request ownership is proved in TranslationCompletionAutoCopyTest; what matters here is that a
 * result the store is handed turns into the right event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainStoreAutoCopyTest {

    @Test
    fun `an eligible main translation emits one copy event carrying the result`() = runTest {
        val fixture = Fixture(this, AutoCopyTranslation.ALL)
        try {
            val copied = fixture.copiedAfter {
                fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
                fixture.store.dispatch(MainIntent.Translate())
            }

            assertEquals("t:hello", copied)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a quick translation emits one copy event`() = runTest {
        val fixture = Fixture(this, AutoCopyTranslation.QUICK_TRANSLATE_ONLY)
        try {
            val copied = fixture.copiedAfter {
                fixture.store.dispatch(MainIntent.ShowQuickTranslate("hello"))
            }

            assertEquals("t:hello", copied)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `an internal refresh emits nothing under all`() = runTest {
        val fixture = Fixture(this, AutoCopyTranslation.ALL)
        try {
            // The extra-output fallback has no translated result to work from, so it falls back to
            // a full translation: internal support work, not something to be handed over.
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            fixture.store.dispatch(MainIntent.RefreshExtraOutput(null))
            advanceUntilIdle()

            assertEquals(emptyList(), fixture.recordedCopies())
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a failed translation emits nothing`() = runTest {
        val fixture = Fixture(this, AutoCopyTranslation.ALL, failing = true)
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            fixture.store.dispatch(MainIntent.Translate())
            advanceUntilIdle()

            assertEquals(emptyList(), fixture.recordedCopies())
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a blank result emits nothing`() = runTest {
        val fixture = Fixture(this, AutoCopyTranslation.ALL, blank = true)
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            fixture.store.dispatch(MainIntent.Translate())
            advanceUntilIdle()

            assertEquals(emptyList(), fixture.recordedCopies())
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `instant translation emits nothing even under all`() = runTest {
        val fixture = Fixture(this, AutoCopyTranslation.ALL, instant = true)
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            advanceUntilIdle()

            assertEquals(emptyList(), fixture.recordedCopies())
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `the setting off emits nothing`() = runTest {
        val fixture = Fixture(this, AutoCopyTranslation.OFF)
        try {
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            fixture.store.dispatch(MainIntent.Translate())
            advanceUntilIdle()

            assertEquals(emptyList(), fixture.recordedCopies())
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `replacing the selection pastes and does not copy`() = runTest {
        val fixture = Fixture(this, AutoCopyTranslation.ALL)
        try {
            fixture.pastedAfter { fixture.store.dispatch(MainIntent.ReplaceWithTranslation("hello")) }

            assertEquals(
                emptyList(),
                fixture.recordedCopies(),
                "the result is delivered by pasting rather than copied"
            )
        } finally {
            fixture.cleanUp()
        }
    }

    /**
     * A MainStore wired to one translator. Auto-update checks are off and no TTS or OCR service
     * is registered, so nothing reaches the network.
     *
     * The store observes several flows for the life of the application, so the fixture gives it a
     * scope of its own and cancels that at the end.
     */
    private class Fixture(
        private val testScope: TestScope,
        autoCopy: AutoCopyTranslation,
        failing: Boolean = false,
        blank: Boolean = false,
        instant: Boolean = false
    ) {
        private val scope = CoroutineScope(
            SupervisorJob() + testScope.coroutineContext[ContinuationInterceptor]!!
        )
        private val directory = Files.createTempDirectory("qtranslate-mainstore-autocopy").toFile()
        private val httpClient = HttpClient()
        private val translator = EchoTranslator(failing, blank)
        private val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to translator.key),
        )
        private val settings = MutableStateFlow(
            Configuration.DEFAULT.copy(
                servicePresets = listOf(preset),
                activeServicePresetId = preset.id,
                autoCopyTranslation = autoCopy,
                autoCheckForUpdates = false,
                isInstantTranslationEnabled = instant,
            )
        )
        private val manager = ActiveServiceManager(
            MutableStateFlow(mapOf<String, Service>(translator.key to translator)),
            settings
        )

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
                activeServices = MutableStateFlow(mapOf<String, Service>(translator.key to translator)),
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

        private val seen = mutableListOf<MainEvent>()

        /**
         * Events as they are emitted. One collector feeds both the recorded history and the awaits
         * below: [MainStore.events] is a channel-backed flow, so a second subscriber would take
         * events away from the first rather than see a copy of them.
         */
        private val emitted = Channel<MainEvent>(Channel.UNLIMITED)
        private val emittedEvents = emitted.receiveAsFlow()
        private val recorder = scope.launch {
            store.events.collect {
                seen += it
                emitted.send(it)
            }
        }

        /**
         * Runs [block] with a subscription already in place and waits for the copy event it is
         * expected to produce. Subscribing first is what makes this deterministic: an event emitted
         * before the wait is still buffered, so nothing is lost and no scheduler ordering is relied
         * upon.
         *
         * @return the text of the first copy event.
         */
        suspend fun copiedAfter(block: () -> Unit): String {
            val arrival = testScope.async {
                emittedEvents.filterIsInstance<MainEvent.CopyToClipboard>().first().text
            }
            testScope.runCurrent()
            block()
            return arrival.await()
        }

        /** As [copiedAfter], for the paste path. */
        suspend fun pastedAfter(block: () -> Unit) {
            val arrival = testScope.async {
                emittedEvents.filterIsInstance<MainEvent.PasteTranslation>().first()
            }
            testScope.runCurrent()
            block()
            arrival.await()
            testScope.advanceUntilIdle()
        }

        /** Copy events observed so far, for flows that are expected to produce none. */
        fun recordedCopies(): List<String> =
            seen.filterIsInstance<MainEvent.CopyToClipboard>().map { it.text }

        fun cleanUp() {
            recorder.cancel()
            emitted.cancel()
            scope.cancel()
            httpClient.close()
            directory.deleteRecursively()
        }
    }

    private class EchoTranslator(
        private val failing: Boolean,
        private val blank: Boolean
    ) : Translator {
        override val key = "t"
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            when {
                failing -> Err(ServiceError.NetworkError("down"))
                blank -> Ok(TranslationResponse("   "))
                else -> Ok(TranslationResponse("$key:${request.text}"))
            }
    }

    private object SilentAudioPlayer : AudioPlayer {
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override fun play(audio: TTSAudio.Bytes) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    private companion object {
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
}
