package com.github.ahatem.qtranslate.core.main.mvi

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
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.updater.Updater
import com.github.ahatem.qtranslate.core.workspace.lookup.FetchInlineDefinitionUseCase
import com.github.ahatem.qtranslate.core.workspace.lookup.LookupWordUseCase
import com.github.ahatem.qtranslate.core.workspace.lookup.SearchImagesUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.SummarizeUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.TranslateTextUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.RewriteUseCase
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files

/**
 * A real [MainStore] with recording Rewriter and Summarizer providers. Events are collected
 * once through the single-consumer [MainStore.events] channel.
 */
internal class WritingSelectionFixture(
    dispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    includeRewriter: Boolean = true,
    includeSummarizer: Boolean = true,
    /**
     * When false the single-consumer [MainStore.events] channel is left uncollected so a test can
     * first buffer deliveries and only then start accepting them, proving delivery-time gating.
     */
    collectEvents: Boolean = true,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + (dispatcher ?: Dispatchers.IO)
    )
    private val directory = Files.createTempDirectory("qtranslate-writing-selection").toFile()
    private val httpClient = HttpClient()
    val rewriter = RecordingRewriter()
    val summarizer = RecordingSummarizer()
    val services = mutableMapOf<String, Service>()
    init {
        if (includeRewriter) services[rewriter.key] = rewriter
        if (includeSummarizer) services[summarizer.key] = summarizer
    }
    val settings = MutableStateFlow(Configuration.DEFAULT.copy(autoCheckForUpdates = false))
    private val manager = ActiveServiceManager(MutableStateFlow(services), settings)
    private val historyRepository = HistoryRepository(directory, SilentLogger, Json)

    val events = mutableListOf<MainEvent>()
    val store: MainStore = buildStore()
    private val emitted = kotlinx.coroutines.channels.Channel<MainEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val emittedEvents = emitted.receiveAsFlow()

    init {
        if (collectEvents) {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                store.events.collect {
                    events += it
                    emitted.send(it)
                }
            }
        }
    }

    suspend fun awaitEvent(predicate: (MainEvent) -> Boolean): MainEvent =
        withTimeout(5_000) { emittedEvents.first(predicate) }

    fun cleanUp() {
        scope.cancel()
        emitted.close()
        httpClient.close()
        directory.deleteRecursively()
    }

    val scopeForTest: CoroutineScope
        get() = scope

    val historyRepositoryForTest: HistoryRepository
        get() = historyRepository

    val managerForTest: ActiveServiceManager
        get() = manager

    private fun buildStore(): MainStore = MainStore(
        scope = scope,
        settingsState = settings,
        historyRepository = historyRepository,
        checkForUpdatesUseCase = CheckForUpdatesUseCase(
            currentVersion = "0.0.0",
            settingsState = settings,
            updater = Updater("ahatem", "QTranslate", httpClient, SilentLogger),
            notificationBus = NotificationBus(),
            loggerFactory = SilentFactory,
        ),
        handleTextToSpeechUseCase = HandleTextToSpeechUseCase(manager, settings, SilentPlayer, SilentFactory),
        performSpellCheckUseCase = PerformSpellCheckUseCase(manager, SilentFactory),
        selectActiveServiceUseCase = SelectActiveServiceUseCase(
            activeServices = MutableStateFlow(services),
            settingsState = settings,
            activeServiceManager = manager,
            scope = scope,
            loggerFactory = SilentFactory,
        ),
        translateTextUseCase = TranslateTextUseCase(
            scope = scope,
            settingsState = settings,
            activeServiceManager = manager,
            historyRepository = historyRepository,
            summarizeUseCase = SummarizeUseCase(manager, SilentFactory),
            rewriteUseCase = RewriteUseCase(manager, SilentFactory),
            loggerFactory = SilentFactory,
        ),
        swapLanguagesUseCase = SwapLanguagesUseCase(),
        ocrExtractionUseCase = OcrExtractionUseCase(manager, SilentFactory),
        summarizeUseCase = SummarizeUseCase(manager, SilentFactory),
        rewriteUseCase = RewriteUseCase(manager, SilentFactory),
        lookupWordUseCase = LookupWordUseCase(manager, SilentFactory),
        searchImagesUseCase = SearchImagesUseCase(manager, SilentFactory),
        fetchInlineDefinitionUseCase = FetchInlineDefinitionUseCase(scope, manager, SilentFactory),
        documentTranslationUseCase = DocumentTranslationUseCase(manager, SilentFactory),
    )

    private object SilentPlayer : AudioPlayer {
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override fun play(audio: TTSAudio.Bytes) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    private object SilentLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private object SilentFactory : com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory {
        override fun getLogger(name: String): Logger = SilentLogger
    }
}

/** Recording Rewriter that can be scripted to succeed, fail, throw, or never answer. */
internal class RecordingRewriter : RecordingService("rewriter"), Rewriter {
    val calls = mutableListOf<RewriteRequest>()
    var started = kotlinx.coroutines.CompletableDeferred<Unit>()
    var release: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var throws: Boolean = false
    var result: Result<RewriteResponse, ServiceError> = Ok(RewriteResponse("rewritten text"))

    override suspend fun rewrite(request: RewriteRequest): Result<RewriteResponse, ServiceError> {
        calls += request
        started.complete(Unit)
        try {
            release?.await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
        if (throws) throw IllegalStateException("provider exploded")
        return result
    }
}

/** Recording Summarizer that can be scripted to succeed, fail, throw, or never answer. */
internal class RecordingSummarizer : RecordingService("summarizer"), Summarizer {
    val calls = mutableListOf<SummarizeRequest>()
    val started = kotlinx.coroutines.CompletableDeferred<Unit>()
    var release: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var throws: Boolean = false
    var result: Result<SummarizeResponse, ServiceError> = Ok(SummarizeResponse("summary text"))

    override suspend fun summarize(request: SummarizeRequest): Result<SummarizeResponse, ServiceError> {
        calls += request
        started.complete(Unit)
        try {
            release?.await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
        if (throws) throw IllegalStateException("provider exploded")
        return result
    }
}

internal abstract class RecordingService(override val key: String) : Service {
    override val name = key
    override val version = "test"
    override val supportedLanguages = SupportedLanguages.All
}
