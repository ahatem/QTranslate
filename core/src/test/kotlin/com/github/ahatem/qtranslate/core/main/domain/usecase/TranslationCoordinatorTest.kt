package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.main.mvi.TranslationOrigin
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputRequest
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Workflow policy around the translation use case: origins, fallback, cancellation. */
class TranslationCoordinatorTest {
    private val directories = mutableListOf<java.io.File>()

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun `auto copy follows the origin policy`() = runTest {
        val fixture = fixture(this, autoCopy = AutoCopyTranslation.ALL)
        fixture.state = fixture.state.copy(inputText = "hello")

        fixture.coordinator.translate(TranslationOrigin.MAIN)
        advanceUntilIdle()
        assertEquals(listOf("translated"), fixture.copies)

        fixture.copies.clear()
        fixture.coordinator.translate(TranslationOrigin.INTERNAL)
        advanceUntilIdle()
        assertTrue(fixture.copies.isEmpty(), "internal work is never auto-copied")
    }

    @Test
    fun `cancelling clears the secondary flags it owned`() = runTest {
        val fixture = fixture(this)
        fixture.state = fixture.state.copy(
            isExtraOutputLoading = true,
            extraOutputFailed = true,
            extraOutputFailure = ExtraOutputFailure.Provider("old"),
        )

        fixture.coordinator.cancel()

        assertFalse(fixture.state.isExtraOutputLoading)
        assertFalse(fixture.state.extraOutputFailed)
        assertNull(fixture.state.extraOutputFailure)
    }

    @Test
    fun `refresh without a translation falls back to a real translation`() = runTest {
        val fixture = fixture(this)
        fixture.state = fixture.state.copy(inputText = "hello", translatedText = "")

        fixture.coordinator.refreshExtraOutput(
            ExtraOutputRequest(ExtraOutputType.Summarize, ExtraOutputSource.Output, "short", "neutral")
        )
        advanceUntilIdle()

        assertEquals(1, fixture.translator.requests.size, "a blank workspace needs a primary first")
        assertEquals("translated", fixture.state.translatedText)
    }

    @Test
    fun `turning the panel off does not trigger a fallback translation`() = runTest {
        val fixture = fixture(this)
        fixture.state = fixture.state.copy(inputText = "hello", translatedText = "ready")

        fixture.coordinator.refreshExtraOutput(
            ExtraOutputRequest(ExtraOutputType.None, ExtraOutputSource.Output, "short", "neutral")
        )
        advanceUntilIdle()

        assertTrue(fixture.translator.requests.isEmpty())
        assertEquals("", fixture.state.extraOutputText)
    }

    private fun fixture(
        scope: CoroutineScope,
        autoCopy: AutoCopyTranslation = AutoCopyTranslation.OFF,
    ): Fixture {
        val translator = RecordingTranslator()
        val settings = MutableStateFlow(
            Configuration.DEFAULT.copy(
                autoCopyTranslation = autoCopy,
                servicePresets = listOf(
                    ServicePreset(
                        id = "preset",
                        name = "preset",
                        selectedServices = mapOf(ServiceRole.TRANSLATOR to translator.key)
                    )
                ),
                activeServicePresetId = "preset",
            )
        )
        val manager = ActiveServiceManager(
            MutableStateFlow(mapOf(translator.key to translator)),
            settings
        )
        val directory = Files.createTempDirectory("qtranslate-coordinator").toFile()
        directories += directory
        val holder = FixtureHolder(
            MainState(inputText = "hello", sourceLanguage = LanguageCode.ENGLISH, targetLanguage = LanguageCode.ARABIC)
        )
        val useCase = TranslateTextUseCase(
            scope = scope,
            settingsState = settings,
            activeServiceManager = manager,
            historyRepository = HistoryRepository(directory, SilentLogger, Json),
            summarizeUseCase = SummarizeUseCase(manager, SilentFactory),
            rewriteUseCase = RewriteUseCase(manager, SilentFactory),
            loggerFactory = SilentFactory,
        )
        val fixture = Fixture(
            translator = translator,
            holder = holder,
            coordinator = TranslationCoordinator(
                useCase = useCase,
                settingsState = settings,
                getState = { holder.state },
                updateState = { transform -> holder.state = holder.state.transform() },
                onStatusUpdate = { _, _, _ -> },
                emitCopy = { holder.copies += it },
            )
        )
        return fixture
    }

    private class FixtureHolder(var state: MainState) {
        val copies = mutableListOf<String>()
    }

    private class Fixture(
        val translator: RecordingTranslator,
        private val holder: FixtureHolder,
        val coordinator: TranslationCoordinator,
    ) {
        var state: MainState
            get() = holder.state
            set(value) { holder.state = value }
        val copies: MutableList<String> get() = holder.copies
    }

    private class RecordingTranslator : Translator {
        override val key = "translator"
        override val name = "translator"
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        val requests = mutableListOf<TranslationRequest>()

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
            requests += request
            return Ok(TranslationResponse("translated"))
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
