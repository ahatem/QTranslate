package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.tts.TTSAudio
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
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.updater.Updater
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor

internal class QuickTranslateLineBreaksFixture(
    private val testScope: TestScope,
    removeLineBreaks: Boolean,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + testScope.coroutineContext[ContinuationInterceptor]!!
    )
    private val directory = Files.createTempDirectory("qtranslate-353").toFile()
    private val httpClient = HttpClient()
    val translator = RecordingTranslator()
    private val preset = ServicePreset(
        id = "preset",
        name = "preset",
        selectedServices = mapOf(ServiceRole.TRANSLATOR to translator.key),
    )
    private val settings = MutableStateFlow(
        Configuration.DEFAULT.copy(
            servicePresets = listOf(preset),
            activeServicePresetId = preset.id,
            autoCopyTranslation = AutoCopyTranslation.OFF,
            autoCheckForUpdates = false,
            isRemoveLineBreaksEnabled = removeLineBreaks,
        )
    )
    private val manager = ActiveServiceManager(
        MutableStateFlow(mapOf<String, Service>(translator.key to translator)),
        settings
    )

    val store: MainStore = buildStore()

    private fun buildStore(): MainStore = MainStore(
        scope = scope,
        settingsState = settings,
        historyRepository = HistoryRepository(directory, Silent353Logger, Json),
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
            activeServices = MutableStateFlow(mapOf<String, Service>(translator.key to translator)),
            settingsState = settings,
            activeServiceManager = manager,
            scope = scope,
            loggerFactory = Silent353Factory,
        ),
        translateTextUseCase = TranslateTextUseCase(
            scope = scope,
            settingsState = settings,
            activeServiceManager = manager,
            historyRepository = HistoryRepository(directory, Silent353Logger, Json),
            summarizeUseCase = SummarizeUseCase(manager, Silent353Factory),
            rewriteUseCase = RewriteUseCase(manager, Silent353Factory),
            loggerFactory = Silent353Factory,
        ),
        swapLanguagesUseCase = SwapLanguagesUseCase(),
        ocrAndTranslateUseCase = OcrAndTranslateUseCase(manager, Silent353Factory),
        summarizeUseCase = SummarizeUseCase(manager, Silent353Factory),
        rewriteUseCase = RewriteUseCase(manager, Silent353Factory),
        lookupWordUseCase = LookupWordUseCase(scope, manager, Silent353Factory),
        searchImagesUseCase = SearchImagesUseCase(scope, manager, Silent353Factory),
        fetchInlineDefinitionUseCase = FetchInlineDefinitionUseCase(scope, manager, Silent353Factory),
        documentTranslationUseCase = DocumentTranslationUseCase(manager, Silent353Factory),
    )

    fun cleanUp() {
        scope.cancel()
        httpClient.close()
        directory.deleteRecursively()
    }

    private object SilentPlayer : AudioPlayer {
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override fun play(audio: TTSAudio.Bytes) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }
}
