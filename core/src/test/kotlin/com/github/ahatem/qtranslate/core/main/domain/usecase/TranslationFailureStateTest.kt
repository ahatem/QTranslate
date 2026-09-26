package com.github.ahatem.qtranslate.core.main.domain.usecase

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
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A blank translation can mean "nothing translated yet" or "the translation failed". The main
 * window shows a different thing for each, so the difference is state, not an inference.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationFailureStateTest {
    private val directories = mutableListOf<java.io.File>()

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun `an untouched state has not failed`() {
        val state = MainState()
        assertFalse(state.translationFailed)
        assertTrue(state.translatedText.isBlank())
    }

    @Test
    fun `a failed translation is recorded and leaves the output blank`() = runTest {
        val translator = SwitchTranslator(fail = true)
        val useCase = useCase(this, translator)
        var state = MainState(inputText = "hello", sourceLanguage = LanguageCode.ENGLISH, targetLanguage = LanguageCode.FRENCH)

        useCase(getState = { state }, updateState = { transform -> state = state.transform() }, onStatusUpdate = { _, _, _ -> })
        advanceUntilIdle()

        assertTrue(state.translationFailed)
        assertTrue(state.translatedText.isBlank())
        assertFalse(state.isLoading)
    }

    @Test
    fun `a new translation clears the failure while it loads and success keeps it clear`() = runTest {
        val translator = SwitchTranslator(fail = true)
        val useCase = useCase(this, translator)
        var state = MainState(inputText = "hello", sourceLanguage = LanguageCode.ENGLISH, targetLanguage = LanguageCode.FRENCH)
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        useCase(getState = { state }, updateState = update, onStatusUpdate = { _, _, _ -> })
        advanceUntilIdle()
        assertTrue(state.translationFailed)

        translator.fail = false
        translator.gate = CompletableDeferred()
        launch { useCase(getState = { state }, updateState = update, onStatusUpdate = { _, _, _ -> }) }
        runCurrent()
        assertTrue(state.isLoading)
        assertFalse(state.translationFailed, "retrying is loading, not failed")

        translator.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(state.translationFailed)
        assertEquals("bonjour", state.translatedText)
    }

    @Test
    fun `a retry that fails again is failed again`() = runTest {
        val translator = SwitchTranslator(fail = true)
        val useCase = useCase(this, translator)
        var state = MainState(inputText = "hello", sourceLanguage = LanguageCode.ENGLISH, targetLanguage = LanguageCode.FRENCH)
        val update: (MainState.() -> MainState) -> Unit = { transform -> state = state.transform() }

        repeat(2) {
            useCase(getState = { state }, updateState = update, onStatusUpdate = { _, _, _ -> })
            advanceUntilIdle()
            assertTrue(state.translationFailed)
        }
    }

    private fun useCase(scope: CoroutineScope, translator: Translator): TranslateTextUseCase {
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to translator.key)
        )
        val settings = MutableStateFlow(
            Configuration.DEFAULT.copy(servicePresets = listOf(preset), activeServicePresetId = preset.id)
        )
        val manager = ActiveServiceManager(MutableStateFlow(mapOf<String, Service>(translator.key to translator)), settings)
        val directory = Files.createTempDirectory("qtranslate-failure-state").toFile()
        directories += directory
        return TranslateTextUseCase(
            scope = scope,
            settingsState = settings,
            activeServiceManager = manager,
            historyRepository = HistoryRepository(directory, TestLoggerFactory.logger, Json),
            summarizeUseCase = SummarizeUseCase(manager, TestLoggerFactory),
            rewriteUseCase = RewriteUseCase(manager, TestLoggerFactory),
            loggerFactory = TestLoggerFactory
        )
    }

    private class SwitchTranslator(var fail: Boolean) : Translator {
        override val key = "switch"
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
            gate?.await()
            return if (fail) Err(ServiceError.NetworkError("down")) else Ok(TranslationResponse("bonjour"))
        }
    }

    private object TestLoggerFactory : LoggerFactory {
        val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }

        override fun getLogger(name: String): Logger = logger
    }
}
