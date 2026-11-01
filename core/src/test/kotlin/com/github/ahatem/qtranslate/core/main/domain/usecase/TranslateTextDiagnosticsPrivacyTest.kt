package com.github.ahatem.qtranslate.core.main.domain.usecase

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
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputRequest
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.shared.logging.CapturingLogger
import com.github.ahatem.qtranslate.core.shared.logging.CapturingLoggerFactory
import com.github.michaelbull.result.Err
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
import kotlin.test.assertTrue

/**
 * Interactive translation is the highest-frequency flow in the app, and its diagnostics used
 * to interpolate what was translated and why it failed. A provider error can carry the request
 * URL — including a key for providers that authenticate by query string — and a raw body that
 * echoes the request; a translated text snippet is the user's own content. The logs may only
 * carry metadata: the service, the error type, lengths.
 */
class TranslateTextDiagnosticsPrivacyTest {
    private val directories = mutableListOf<java.io.File>()

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun `translation success logs the service and length, not the translated text`() = runTest {
        val logger = CapturingLogger()
        val translator = ScriptedTranslator(
            listOf(Ok(TranslationResponse("SENTINEL-ZQ42-OUTPUT-1b7e is the translation")))
        )
        harness(this, logger, translator).invoke(
            getState = { state("SENTINEL-ZQ42-INPUT-94d0 hello") },
            updateState = { },
            onStatusUpdate = { _, _, _ -> }
        )
        advanceUntilIdle()

        assertNoSentinel(logger, "SENTINEL-ZQ42-OUTPUT-1b7e", "SENTINEL-ZQ42-INPUT-94d0")
        assertTrue(
            logger.messages.any { it.contains("Translation successful") && it.contains("chars=") },
            "the success diagnostic keeps its metadata: ${logger.messages}"
        )
    }

    @Test
    fun `translation failure logs the error type, not the provider message`() = runTest {
        val logger = CapturingLogger()
        // A provider failure whose message carries the request URL with a key and a body that
        // echoes the request — exactly what a real provider error can contain.
        val translator = ScriptedTranslator(
            listOf(
                Err(
                    ServiceError.ServiceUnavailableError(
                        "HTTP 500 for https://provider.invalid/translate?key=SENTINEL-ZQ42-KEY-3af1\n" +
                                "SENTINEL-ZQ42-BODY-58c2"
                    )
                )
            )
        )
        harness(this, logger, translator).invoke(
            getState = { state("SENTINEL-ZQ42-INPUT-94d0 hello") },
            updateState = { },
            onStatusUpdate = { _, _, _ -> }
        )
        advanceUntilIdle()

        assertNoSentinel(logger, "SENTINEL-ZQ42-KEY-3af1", "SENTINEL-ZQ42-BODY-58c2", "SENTINEL-ZQ42-INPUT-94d0")
        assertTrue(
            logger.messages.any { it.contains("Translation failed: ServiceUnavailableError") },
            "the failure diagnostic keeps the error type: ${logger.messages}"
        )
    }

    @Test
    fun `an unexpected translation exception logs its class, not its message`() = runTest {
        val logger = CapturingLogger()
        val translator = ThrowingTranslator(IllegalStateException("SENTINEL-ZQ42-EXC-71f0 blew up"))
        harness(this, logger, translator).invoke(
            getState = { state("SENTINEL-ZQ42-INPUT-94d0 hello") },
            updateState = { },
            onStatusUpdate = { _, _, _ -> }
        )
        advanceUntilIdle()

        assertNoSentinel(logger, "SENTINEL-ZQ42-EXC-71f0")
        assertTrue(
            logger.messages.any { it.contains("Unexpected error during translation: IllegalStateException") },
            "the diagnostic keeps the exception class: ${logger.messages}"
        )
    }

    @Test
    fun `rule-driven retranslation logs metadata, not the retranslated text`() = runTest {
        val logger = CapturingLogger()
        val translator = ScriptedTranslator(
            listOf(
                Ok(TranslationResponse("first pass", detectedLanguage = LanguageCode.GERMAN)),
                Ok(TranslationResponse("SENTINEL-ZQ42-RULE-6d33 retranslated"))
            )
        )
        val rules = listOf(TranslationRule("de", "fr"))
        harness(this, logger, translator, rules = rules).invoke(
            getState = { state("SENTINEL-ZQ42-INPUT-94d0 hallo", source = LanguageCode.AUTO, target = LanguageCode.ENGLISH) },
            updateState = { },
            onStatusUpdate = { _, _, _ -> }
        )
        advanceUntilIdle()

        assertNoSentinel(logger, "SENTINEL-ZQ42-RULE-6d33", "SENTINEL-ZQ42-INPUT-94d0")
        assertTrue(
            logger.messages.any { it.contains("Re-translation successful") && it.contains("chars=") },
            "the retranslation diagnostic keeps its metadata: ${logger.messages}"
        )
    }

    @Test
    fun `backward translation failure logs the error type, not the provider message`() = runTest {
        val logger = CapturingLogger()
        val translator = ScriptedTranslator(
            listOf(
                Ok(TranslationResponse("SENTINEL-ZQ42-OUTPUT-1b7e")),
                Err(
                    ServiceError.TimeoutError(
                        "Request timed out: https://provider.invalid/back?key=SENTINEL-ZQ42-KEY-3af1"
                    )
                )
            )
        )
        val extraOutput = ExtraOutputRequest(
            type = ExtraOutputType.BackwardTranslate,
            source = ExtraOutputSource.Output,
            summaryLength = "short",
            rewriteStyle = "casual"
        )
        harness(this, logger, translator).invoke(
            getState = { state("SENTINEL-ZQ42-INPUT-94d0 hello") },
            updateState = { },
            onStatusUpdate = { _, _, _ -> },
            extraOutputRequest = extraOutput
        )
        advanceUntilIdle()

        assertNoSentinel(logger, "SENTINEL-ZQ42-KEY-3af1", "SENTINEL-ZQ42-OUTPUT-1b7e", "SENTINEL-ZQ42-INPUT-94d0")
        assertTrue(
            logger.messages.any { it.contains("Backward translation failed: TimeoutError") },
            "the failure diagnostic keeps the error type: ${logger.messages}"
        )
    }

    private fun state(input: String, source: LanguageCode = LanguageCode.ENGLISH, target: LanguageCode = LanguageCode.FRENCH) =
        MainState(inputText = input, sourceLanguage = source, targetLanguage = target)

    private fun harness(
        scope: CoroutineScope,
        logger: CapturingLogger,
        translator: Translator,
        rules: List<TranslationRule> = emptyList()
    ): TranslateTextUseCase {
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to translator.key)
        )
        val settings = MutableStateFlow(
            Configuration.DEFAULT.copy(
                servicePresets = listOf(preset),
                activeServicePresetId = preset.id,
                translationRules = rules
            )
        )
        val manager = ActiveServiceManager(
            MutableStateFlow(mapOf<String, Service>(translator.key to translator)),
            settings
        )
        val directory = Files.createTempDirectory("qtranslate-diag").toFile()
        directories += directory
        val history = HistoryRepository(directory, logger, Json)
        val factory = CapturingLoggerFactory(logger)
        return TranslateTextUseCase(
            scope = scope,
            settingsState = settings,
            activeServiceManager = manager,
            historyRepository = history,
            summarizeUseCase = SummarizeUseCase(manager, factory),
            rewriteUseCase = RewriteUseCase(manager, factory),
            loggerFactory = factory
        )
    }

    private class ScriptedTranslator(
        responses: List<Result<TranslationResponse, ServiceError>>
    ) : Translator {
        private val queue = responses.toMutableList()
        override val key = "diag-translator"
        override val name = "Diagnostic translator"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest) =
            queue.removeFirstOrNull() ?: Ok(TranslationResponse(""))
    }

    private class ThrowingTranslator(private val failure: Throwable) : Translator {
        override val key = "diag-throwing-translator"
        override val name = key
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            throw failure
    }

    private fun assertNoSentinel(logger: CapturingLogger, vararg sentinels: String) {
        val captured = logger.messages + logger.loggedThrowables
        val leaked = captured.filter { line -> sentinels.any { line.contains(it) } }
        assertTrue(leaked.isEmpty(), "sentinel content leaked into logs: $leaked")
    }
}
