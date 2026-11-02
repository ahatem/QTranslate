package com.github.ahatem.qtranslate.core.main.mvi

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
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor

/**
 * A [MainStore] wired to one translator and one OCR service. The translator echoes its input, so
 * the received text and the published result both show which path handled the OCR result.
 */
internal class OcrTranslationRoutingFixture(
    private val testScope: TestScope,
    ocrText: String,
    removeLineBreaks: Boolean = false,
    autoCopy: AutoCopyTranslation = AutoCopyTranslation.OFF,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + testScope.coroutineContext[ContinuationInterceptor]!!
    )
    private val directory = Files.createTempDirectory("qtranslate-ocr-routing").toFile()
    private val httpClient = HttpClient()
    val translator = RecordingTranslator()
    private val ocr = RecordingOcr(ocrText)
    private val services = MutableStateFlow(
        mapOf<String, Service>(translator.key to translator, ocr.key to ocr)
    )
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
            isRemoveLineBreaksEnabled = removeLineBreaks,
        )
    )
    private val manager = ActiveServiceManager(services, settings)
    private val historyRepository = HistoryRepository(directory, Silent353Logger, Json)

    val store: MainStore = MainStore(
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
            activeServices = services,
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

    private val emitted = Channel<MainEvent>(Channel.UNLIMITED)
    private val emittedEvents = emitted.receiveAsFlow()
    private val recorder = scope.launch {
        store.events.collect { emitted.send(it) }
    }

    /** Subscribes first, then runs [block] and waits for the copy event it must produce. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun copiedAfter(block: () -> Unit): String {
        val arrival = testScope.async {
            emittedEvents.filterIsInstance<MainEvent.CopyToClipboard>().first().text
        }
        testScope.runCurrent()
        block()
        return arrival.await()
    }

    fun cleanUp() {
        recorder.cancel()
        scope.cancel()
        httpClient.close()
        directory.deleteRecursively()
    }

    private class RecordingOcr(private val text: String) : OCR {
        override val key = "o"
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> =
            Ok(OCRResponse(text))
    }

    private object SilentPlayer : AudioPlayer {
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override fun play(audio: TTSAudio.Bytes) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }
}
