package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.core.audio.AudioPlayer
import com.github.ahatem.qtranslate.core.document.DocumentTranslationUseCase
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.domain.usecase.CheckForUpdatesUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.HandleTextToSpeechUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.OcrAndTranslateUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.PerformSpellCheckUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SelectActiveServiceUseCase
import com.github.ahatem.qtranslate.core.main.domain.usecase.SwapLanguagesUseCase
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.OcrSettings
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.updater.Updater
import com.github.ahatem.qtranslate.core.workspace.lookup.FetchInlineDefinitionUseCase
import com.github.ahatem.qtranslate.core.workspace.lookup.LookupWordUseCase
import com.github.ahatem.qtranslate.core.workspace.lookup.SearchImagesUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.RewriteUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.SummarizeUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.TranslateTextUseCase
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor

/** Test OCR that records requests and can be made to answer with any text or failure. */
internal class RecordingOcrService(
    override val key: String = "ocr",
    override val name: String = "Recording OCR",
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH)),
) : OCR {
    override val version = "test"

    val requests = mutableListOf<OCRRequest>()
    var responder: suspend (OCRRequest) -> Result<OCRResponse, ServiceError> = {
        Ok(OCRResponse("recognized text"))
    }

    override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> {
        requests += request
        return responder(request)
    }
}

/**
 * A real [MainStore] with a recording translator and OCR provider. Events are collected once,
 * because [MainStore.events] is a channel rather than a broadcast flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class OcrWorkflowFixture(
    private val testScope: TestScope,
    ocrSettings: OcrSettings = OcrSettings(),
    autoCopy: AutoCopyTranslation = AutoCopyTranslation.OFF,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + (dispatcher ?: testScope.coroutineContext[ContinuationInterceptor]!!)
    )
    private val directory = Files.createTempDirectory("qtranslate-ocr-workflow").toFile()
    private val httpClient = HttpClient()
    val translator = RecordingTranslator()
    val ocr = RecordingOcrService()
    val settings = MutableStateFlow(buildSettings(ocrSettings, autoCopy))
    private val services = mapOf<String, Service>(translator.key to translator, ocr.key to ocr)
    private val manager = ActiveServiceManager(MutableStateFlow(services), settings)
    private val historyRepository = HistoryRepository(directory, Silent353Logger, Json)

    val events = mutableListOf<MainEvent>()
    val store: MainStore = buildStore()
    private val emitted = Channel<MainEvent>(Channel.UNLIMITED)
    private val emittedEvents = emitted.receiveAsFlow()

    init {
        // Subscribe before the first dispatch; receiveAsFlow has one consumer, and the event
        // channel is only observed once this coroutine is actually parked on it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.events.collect {
                events += it
                emitted.send(it)
            }
        }
    }

    /** Mirrors the proven auto-copy harness: subscribe, then run the block and await the event. */
    suspend fun copiedAfter(block: () -> Unit): String {
        val arrival = testScope.async {
            emittedEvents.filterIsInstance<MainEvent.CopyToClipboard>().first().text
        }
        testScope.runCurrent()
        block()
        return arrival.await()
    }

    /** Waits for one event through the same single-consumer channel the UI collector uses. */
    suspend fun awaitEvent(predicate: (MainEvent) -> Boolean): MainEvent =
        withTimeout(5_000) { emittedEvents.first(predicate) }

    fun image(): ImageData = ImageData(byteArrayOf(1, 2, 3), "png", 8, 8)

    fun cleanUp() {
        // Stop producers before closing the relay channel: an in-flight event send must not
        // become an uncaught ClosedSendChannelException in the next test.
        scope.cancel()
        emitted.close()
        httpClient.close()
        directory.deleteRecursively()
    }

    private fun buildSettings(ocrSettings: OcrSettings, autoCopy: AutoCopyTranslation): Configuration {
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(
                ServiceRole.TRANSLATOR to translator.key,
                ServiceRole.OCR to ocr.key,
            ),
        )
        return Configuration.DEFAULT.copy(
            servicePresets = listOf(preset),
            activeServicePresetId = preset.id,
            autoCopyTranslation = autoCopy,
            autoCheckForUpdates = false,
            ocr = ocrSettings,
        )
    }

    private fun buildStore(): MainStore = MainStore(
        scope = scope,
        settingsState = settings,
        historyRepository = historyRepository,
        checkForUpdatesUseCase = CheckForUpdatesUseCase(
            currentVersion = "0.0.0",
            settingsState = settings,
            updater = Updater("ahatem", "QTranslate", httpClient, Silent353Logger),
            notificationBus = NotificationBus(),
            loggerFactory = Silent353Factory,
        ),
        handleTextToSpeechUseCase = HandleTextToSpeechUseCase(manager, settings, SilentPlayer, Silent353Factory),
        performSpellCheckUseCase = PerformSpellCheckUseCase(manager, Silent353Factory),
        selectActiveServiceUseCase = SelectActiveServiceUseCase(
            activeServices = MutableStateFlow(services),
            settingsState = settings,
            activeServiceManager = manager,
            scope = scope,
            loggerFactory = Silent353Factory,
        ),
        translateTextUseCase = TranslateTextUseCase(
            scope = scope,
            settingsState = settings,
            activeServiceManager = manager,
            historyRepository = historyRepository,
            summarizeUseCase = SummarizeUseCase(manager, Silent353Factory),
            rewriteUseCase = RewriteUseCase(manager, Silent353Factory),
            loggerFactory = Silent353Factory,
        ),
        swapLanguagesUseCase = SwapLanguagesUseCase(),
        ocrAndTranslateUseCase = OcrAndTranslateUseCase(manager, Silent353Factory),
        summarizeUseCase = SummarizeUseCase(manager, Silent353Factory),
        rewriteUseCase = RewriteUseCase(manager, Silent353Factory),
        lookupWordUseCase = LookupWordUseCase(manager, Silent353Factory),
        searchImagesUseCase = SearchImagesUseCase(manager, Silent353Factory),
        fetchInlineDefinitionUseCase = FetchInlineDefinitionUseCase(scope, manager, Silent353Factory),
        documentTranslationUseCase = DocumentTranslationUseCase(manager, Silent353Factory),
    )

    private object SilentPlayer : AudioPlayer {
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override fun play(audio: TTSAudio.Bytes) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }
}
