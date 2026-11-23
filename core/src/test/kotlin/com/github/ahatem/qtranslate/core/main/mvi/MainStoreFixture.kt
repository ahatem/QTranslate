package com.github.ahatem.qtranslate.core.main.mvi

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.core.audio.AudioPlayer
import com.github.ahatem.qtranslate.core.document.DocumentTranslationUseCase
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.domain.usecase.CheckForUpdatesUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.HandleTextToSpeechUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.PerformSpellCheckUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SelectActiveServiceUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SwapLanguagesUseCase
import com.github.ahatem.qtranslate.core.ocr.OcrExtractionUseCase
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.testing.SilentLogger
import com.github.ahatem.qtranslate.core.testing.SilentLoggerFactory
import com.github.ahatem.qtranslate.core.updater.Updater
import com.github.ahatem.qtranslate.core.workspace.lookup.FetchInlineDefinitionUseCase
import com.github.ahatem.qtranslate.core.workspace.lookup.LookupWordUseCase
import com.github.ahatem.qtranslate.core.workspace.lookup.SearchImagesUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.RewriteUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.SummarizeUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.TranslateTextUseCase
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor

/**
 * A real [MainStore] over the given services, on a scope of its own that [cleanUp] cancels.
 * Update checks are off, so nothing reaches the network.
 *
 * Events are collected once, because [MainStore.events] is a channel-backed flow: a second
 * subscriber would take events from the first. Pass collectEvents = false to leave the channel
 * unread so a test can buffer deliveries first. The store runs on [dispatcher], else on the
 * [testScope] scheduler, else on [Dispatchers.IO].
 */
internal class MainStoreFixture(
    val services: Map<String, Service>,
    configuration: Configuration,
    private val testScope: TestScope? = null,
    dispatcher: CoroutineDispatcher? = null,
    historyData: DataStore<Preferences>? = null,
    collectEvents: Boolean = true,
) {
    val scope = CoroutineScope(
        SupervisorJob() + (dispatcher ?: testScope?.coroutineContext?.get(ContinuationInterceptor) ?: Dispatchers.IO)
    )
    private val directory = Files.createTempDirectory("qtranslate-main-store").toFile()
    private val httpClient = HttpClient()
    val settings = MutableStateFlow(configuration)
    val manager = ActiveServiceManager(MutableStateFlow(services), settings)
    val historyRepository = HistoryRepository(directory, SilentLogger, Json, historyData)

    val events = mutableListOf<MainEvent>()
    private val emitted = Channel<MainEvent>(Channel.UNLIMITED)
    private val emittedEvents = emitted.receiveAsFlow()

    val store: MainStore = MainStore(
        scope = scope,
        settingsState = settings,
        historyRepository = historyRepository,
        checkForUpdatesUseCase = CheckForUpdatesUseCase(
            currentVersion = "0.0.0",
            settingsState = settings,
            updater = Updater("ahatem", "QTranslate", httpClient, SilentLogger),
            notificationBus = NotificationBus(),
            loggerFactory = SilentLoggerFactory,
        ),
        handleTextToSpeechUseCase = HandleTextToSpeechUseCase(manager, settings, SilentPlayer, SilentLoggerFactory),
        performSpellCheckUseCase = PerformSpellCheckUseCase(manager, SilentLoggerFactory),
        selectActiveServiceUseCase = SelectActiveServiceUseCase(
            activeServices = MutableStateFlow(services),
            settingsState = settings,
            activeServiceManager = manager,
            scope = scope,
            loggerFactory = SilentLoggerFactory,
        ),
        translateTextUseCase = TranslateTextUseCase(
            scope = scope,
            settingsState = settings,
            activeServiceManager = manager,
            historyRepository = historyRepository,
            summarizeUseCase = SummarizeUseCase(manager, SilentLoggerFactory),
            rewriteUseCase = RewriteUseCase(manager, SilentLoggerFactory),
            loggerFactory = SilentLoggerFactory,
        ),
        swapLanguagesUseCase = SwapLanguagesUseCase(),
        ocrExtractionUseCase = OcrExtractionUseCase(manager, SilentLoggerFactory),
        summarizeUseCase = SummarizeUseCase(manager, SilentLoggerFactory),
        rewriteUseCase = RewriteUseCase(manager, SilentLoggerFactory),
        lookupWordUseCase = LookupWordUseCase(manager, SilentLoggerFactory),
        searchImagesUseCase = SearchImagesUseCase(manager, SilentLoggerFactory),
        fetchInlineDefinitionUseCase = FetchInlineDefinitionUseCase(scope, manager, SilentLoggerFactory),
        documentTranslationUseCase = DocumentTranslationUseCase(manager, SilentLoggerFactory),
    )

    init {
        // Subscribe before the first dispatch; the channel is only observed once this coroutine
        // is parked on it.
        if (collectEvents) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.events.collect {
                events += it
                emitted.send(it)
            }
        }
    }

    /** Waits for one event through the same single-consumer channel the collector feeds. */
    suspend fun awaitEvent(predicate: (MainEvent) -> Boolean): MainEvent =
        withTimeout(5_000) { emittedEvents.first(predicate) }

    /**
     * Runs [block] with a subscription already in place and returns the text of the first copy
     * event it produces. Needs a test scope.
     */
    suspend fun copiedAfter(block: () -> Unit): String {
        val testScope = checkNotNull(testScope) { "copiedAfter needs a TestScope" }
        val arrival = testScope.async {
            emittedEvents.filterIsInstance<MainEvent.CopyToClipboard>().first().text
        }
        testScope.runCurrent()
        block()
        return arrival.await()
    }

    /** As [copiedAfter], for the paste path. */
    suspend fun pastedAfter(block: () -> Unit) {
        val testScope = checkNotNull(testScope) { "pastedAfter needs a TestScope" }
        val arrival = testScope.async {
            emittedEvents.filterIsInstance<MainEvent.PasteTranslation>().first()
        }
        testScope.runCurrent()
        block()
        arrival.await()
        testScope.advanceUntilIdle()
    }

    fun recordedCopies(): List<String> =
        events.filterIsInstance<MainEvent.CopyToClipboard>().map { it.text }

    fun cleanUp() {
        // Stop producers before closing the relay channel so an in-flight send cannot surface as
        // an uncaught ClosedSendChannelException in the next test.
        scope.cancel()
        emitted.close()
        httpClient.close()
        directory.deleteRecursively()
    }

    companion object {
        /** Default settings, with a single preset selecting the given services when any are given. */
        fun configuration(
            selectedServices: Map<ServiceRole, String> = emptyMap(),
            tweak: Configuration.() -> Configuration = { this },
        ): Configuration {
            val base = Configuration.DEFAULT.copy(autoCheckForUpdates = false)
            if (selectedServices.isEmpty()) return base.tweak()
            val preset = ServicePreset(id = "preset", name = "preset", selectedServices = selectedServices)
            return base.copy(servicePresets = listOf(preset), activeServicePresetId = preset.id).tweak()
        }
    }

    private object SilentPlayer : AudioPlayer {
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override fun play(audio: TTSAudio.Bytes) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }
}