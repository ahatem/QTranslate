package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.dictionary.Definition
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryResponse
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.rewriter.RewriteRequest
import com.github.ahatem.qtranslate.api.rewriter.RewriteResponse
import com.github.ahatem.qtranslate.api.rewriter.Rewriter
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckResponse
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.api.summarizer.SummarizeRequest
import com.github.ahatem.qtranslate.api.summarizer.SummarizeResponse
import com.github.ahatem.qtranslate.api.summarizer.Summarizer
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.api.tts.AudioFormat
import com.github.ahatem.qtranslate.api.tts.TTSRequest
import com.github.ahatem.qtranslate.api.tts.TTSResponse
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.core.audio.AudioPlayer
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.logging.CapturingLogger
import com.github.ahatem.qtranslate.core.shared.logging.CapturingLoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.updater.Updater
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Sentinel-based privacy coverage for the remaining interactive flows.
 *
 * Each test drives one flow through a scripted provider whose responses, error messages and
 * endpoints carry a unique sentinel, then asserts the sentinel never reaches a captured log
 * line — directly, or inside a throwable a call site hands to the logger. The diagnostics that
 * remain are counts, lengths, language tags, service ids and error types.
 */
class InteractiveDiagnosticsPrivacyTest {

    // ── Inline definitions ─────────────────────────────────────────────────

    @Test
    fun `inline definition logs the language, not the word or the definition`() = runBlocking {
        val logger = CapturingLogger()
        val dictionary = FakeDictionary(
            Ok(DictionaryResponse(listOf(DictionaryEntry("word", "noun", listOf(Definition("SENTINEL-ZQ42-DEF-4c81"))))))
        )
        val useCase = FetchInlineDefinitionUseCase(
            this, manager(dictionary), CapturingLoggerFactory(logger)
        )
        useCase.invoke(
            word = "SENTINEL-ZQ42-WORD-2e97",
            language = LanguageCode.ENGLISH,
            alternateWord = "SENTINEL-ZQ42-ALT-6b30",
            alternateLanguage = LanguageCode.FRENCH,
            updateState = { }
        )
        coroutineContext[Job]!!.children.forEach { it.join() }

        assertNoSentinel(logger, "SENTINEL-ZQ42-WORD-2e97", "SENTINEL-ZQ42-ALT-6b30", "SENTINEL-ZQ42-DEF-4c81")
        assertTrue(
            logger.messages.any { it.contains("Inline definition ready") && it.contains("language=en") },
            "the ready diagnostic keeps the language tag: ${logger.messages}"
        )
    }

    @Test
    fun `a word with no definition logs the candidate count, not the words`() = runBlocking {
        val logger = CapturingLogger()
        val dictionary = FakeDictionary(Ok(DictionaryResponse(emptyList())))
        val useCase = FetchInlineDefinitionUseCase(
            this, manager(dictionary), CapturingLoggerFactory(logger)
        )
        useCase.invoke(
            word = "SENTINEL-ZQ42-WORD-2e97",
            language = LanguageCode.ENGLISH,
            alternateWord = "SENTINEL-ZQ42-ALT-6b30",
            alternateLanguage = LanguageCode.FRENCH,
            updateState = { }
        )
        coroutineContext[Job]!!.children.forEach { it.join() }

        assertNoSentinel(logger, "SENTINEL-ZQ42-WORD-2e97", "SENTINEL-ZQ42-ALT-6b30")
        assertTrue(
            logger.messages.any { it.contains("No inline definition") && it.contains("2 candidate(s)") },
            "the empty diagnostic keeps the count: ${logger.messages}"
        )
    }

    @Test
    fun `a cancelled inline definition lookup propagates cancellation instead of reporting nothing`() = runTest {
        val dictionary = object : Dictionary {
            override val key = "cancelling-dictionary"
            override val name = key
            override val version = "1.0.0"
            override val supportedLanguages = SupportedLanguages.All
            override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> =
                throw CancellationException("SENTINEL-ZQ42-CANCELLED")
        }
        val useCase = FetchInlineDefinitionUseCase(
            this, manager(dictionary), CapturingLoggerFactory(CapturingLogger())
        )
        useCase.invoke(
            word = "term",
            language = LanguageCode.ENGLISH,
            alternateWord = "alternate",
            alternateLanguage = LanguageCode.FRENCH,
            updateState = { }
        )
        val lookup = coroutineContext[Job]!!.children.first()
        advanceUntilIdle()

        assertTrue(
            lookup.isCancelled,
            "a cancelled dictionary lookup must propagate: a swallowed cancellation reports 'no definition' for a request that was abandoned"
        )
    }

    // ── OCR ────────────────────────────────────────────────────────────────

    @Test
    fun `OCR logs counts on success and the error type on failure`() = runBlocking {
        val logger = CapturingLogger()
        val image = ImageData(byteArrayOf(1, 2, 3), "png", 8, 8)

        val okUseCase = OcrAndTranslateUseCase(
            manager(FakeOCR(Ok(OCRResponse("SENTINEL-ZQ42-OCRTEXT-9d14")))),
            CapturingLoggerFactory(logger)
        )
        okUseCase.invoke(image, MainState(), { _, _, _ -> })
        coroutineContext[Job]!!.children.forEach { it.join() }

        assertTrue(
            logger.messages.any { it.contains("OCR successful") && it.contains("characters") },
            "the success diagnostic keeps the character count: ${logger.messages}"
        )

        val failingUseCase = OcrAndTranslateUseCase(
            manager(FakeOCR(Err(ServiceError.InvalidResponseError("SENTINEL-ZQ42-OCRERR-77aa")))),
            CapturingLoggerFactory(logger)
        )
        failingUseCase.invoke(image, MainState(), { _, _, _ -> })
        coroutineContext[Job]!!.children.forEach { it.join() }

        assertNoSentinel(logger, "SENTINEL-ZQ42-OCRTEXT-9d14", "SENTINEL-ZQ42-OCRERR-77aa")
        assertTrue(
            logger.messages.any { it.contains("OCR failed: InvalidResponseError") },
            "the failure diagnostic keeps the error type: ${logger.messages}"
        )
    }

    // ── Text to speech ─────────────────────────────────────────────────────

    @Test
    fun `a failed audio stream download logs no audio URL`() = runBlocking {
        val logger = CapturingLogger()
        // A raw space makes URI parsing fail immediately and deterministically, with no DNS
        // round trip; the sentinel rides inside the URL so the assertion covers the whole string.
        val streamUrl = "https://audio.invalid/stream?token=SENTINEL-ZQ42-TTSURL-3fe6 x"
        val tts = FakeTTS(Ok(TTSResponse(TTSAudio.StreamUrl(streamUrl, AudioFormat.MP3))))
        val useCase = HandleTextToSpeechUseCase(
            manager(tts), MutableStateFlow(Configuration.DEFAULT), FakeAudioPlayer(),
            CapturingLoggerFactory(logger)
        )
        useCase.invoke(
            currentState = MainState(inputText = "SENTINEL-ZQ42-TTSINPUT-5cd2", sourceLanguage = LanguageCode.ENGLISH),
            textSource = TextSource.Input,
            textOverride = null,
            languageOverride = LanguageCode.ENGLISH,
            onStatusUpdate = { _, _, _ -> }
        )

        assertNoSentinel(logger, "SENTINEL-ZQ42-TTSURL-3fe6", "SENTINEL-ZQ42-TTSINPUT-5cd2")
        assertTrue(
            logger.messages.any { it.contains("Audio stream download failed") },
            "the failure is still diagnosed: ${logger.messages}"
        )
    }

    @Test
    fun `a failed synthesis logs the error type, not the provider message`() = runBlocking {
        val logger = CapturingLogger()
        val tts = FakeTTS(Err(ServiceError.AuthenticationError("Rejected key SENTINEL-ZQ42-TTSKEY-91bd")))
        val useCase = HandleTextToSpeechUseCase(
            manager(tts), MutableStateFlow(Configuration.DEFAULT), FakeAudioPlayer(),
            CapturingLoggerFactory(logger)
        )
        useCase.invoke(
            currentState = MainState(inputText = "SENTINEL-ZQ42-TTSINPUT-5cd2", sourceLanguage = LanguageCode.ENGLISH),
            textSource = TextSource.Input,
            textOverride = null,
            languageOverride = LanguageCode.ENGLISH,
            onStatusUpdate = { _, _, _ -> }
        )

        assertNoSentinel(logger, "SENTINEL-ZQ42-TTSKEY-91bd", "SENTINEL-ZQ42-TTSINPUT-5cd2")
        assertTrue(
            logger.messages.any { it.contains("TTS failed: AuthenticationError") },
            "the failure diagnostic keeps the error type: ${logger.messages}"
        )
    }

    // ── Spell check, rewrite, summarize ────────────────────────────────────

    @Test
    fun `spell check failure logs the error type, not the provider message`() = runBlocking {
        val logger = CapturingLogger()
        val checker = FakeSpellChecker(Err(ServiceError.InvalidResponseError("SENTINEL-ZQ42-SPELLERR-b42e")))
        val useCase = PerformSpellCheckUseCase(manager(checker), CapturingLoggerFactory(logger))
        useCase.invoke(
            MainState(sourceLanguage = LanguageCode.ENGLISH, inputText = "SENTINEL-ZQ42-SPELLINPUT-1aa7"),
            "SENTINEL-ZQ42-SPELLTEXT-80f3",
            { _, _, _ -> }
        )

        assertNoSentinel(logger, "SENTINEL-ZQ42-SPELLERR-b42e", "SENTINEL-ZQ42-SPELLTEXT-80f3", "SENTINEL-ZQ42-SPELLINPUT-1aa7")
        assertTrue(
            logger.messages.any { it.contains("Spell check failed: InvalidResponseError") },
            "the failure diagnostic keeps the error type: ${logger.messages}"
        )
    }

    @Test
    fun `rewrite failure logs the error type, not the provider message`() = runBlocking {
        val logger = CapturingLogger()
        val rewriter = FakeRewriter(Err(ServiceError.UnknownError("SENTINEL-ZQ42-REWRITEERR-6c51")))
        val useCase = RewriteUseCase(manager(rewriter), CapturingLoggerFactory(logger))
        useCase.invoke("SENTINEL-ZQ42-REWRITETEXT-2d98", "casual", { _, _, _ -> })

        assertNoSentinel(logger, "SENTINEL-ZQ42-REWRITEERR-6c51", "SENTINEL-ZQ42-REWRITETEXT-2d98")
        assertTrue(
            logger.messages.any { it.contains("Rewrite failed: UnknownError") },
            "the failure diagnostic keeps the error type: ${logger.messages}"
        )
    }

    @Test
    fun `summarize failure logs the error type, not the provider message`() = runBlocking {
        val logger = CapturingLogger()
        val summarizer = FakeSummarizer(Err(ServiceError.TimeoutError("SENTINEL-ZQ42-SUMMARIZEERR-0e77")))
        val useCase = SummarizeUseCase(manager(summarizer), CapturingLoggerFactory(logger))
        useCase.invoke("SENTINEL-ZQ42-SUMMARIZETEXT-4b65", "short", { _, _, _ -> })

        assertNoSentinel(logger, "SENTINEL-ZQ42-SUMMARIZEERR-0e77", "SENTINEL-ZQ42-SUMMARIZETEXT-4b65")
        assertTrue(
            logger.messages.any { it.contains("Summarize failed: TimeoutError") },
            "the failure diagnostic keeps the error type: ${logger.messages}"
        )
    }

    // ── Comparison ─────────────────────────────────────────────────────────

    @Test
    fun `comparison failure logs the service id and exception class, not the exception text`() = runBlocking {
        val logger = CapturingLogger()
        val comparison = ThrowingTranslator(RuntimeException("SENTINEL-ZQ42-CMP-5a29 exploded"))
        val useCase = ParallelComparisonUseCase(
            this, manager(comparison), CapturingLoggerFactory(logger)
        )
        useCase.start(
            ParallelComparisonRequest(
                text = "SENTINEL-ZQ42-CMPINPUT-77dd",
                sourceLanguage = LanguageCode.ENGLISH,
                targetLanguage = LanguageCode.FRENCH,
                primaryTranslatorId = "primary",
                translatorIds = listOf(comparison.key)
            ),
            generation = 1,
            updateState = { }
        )
        coroutineContext[Job]!!.children.forEach { it.join() }

        assertNoSentinel(logger, "SENTINEL-ZQ42-CMP-5a29", "SENTINEL-ZQ42-CMPINPUT-77dd")
        assertTrue(
            logger.messages.any { it.contains("Comparison translation failed for 'cmp'") && it.contains("RuntimeException") },
            "the failure diagnostic keeps the service id and exception class: ${logger.messages}"
        )
    }

    // ── Update check ───────────────────────────────────────────────────────

    @Test
    fun `a failed update check logs the error type, not the transport exception`() = runTest {
        val logger = CapturingLogger()
        val engine = MockEngine { throw RuntimeException("SENTINEL-ZQ42-UPDATE-8e44 unreachable") }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val updater = Updater("ahatem", "qtranslate", http, logger)
        val settings = MutableStateFlow(Configuration.DEFAULT.copy(autoCheckForUpdates = true))
        val useCase = CheckForUpdatesUseCase(
            currentVersion = "1.0.0",
            settingsState = settings,
            updater = updater,
            notificationBus = NotificationBus(),
            loggerFactory = CapturingLoggerFactory(logger)
        )

        useCase.invoke(onStatusUpdate = { _, _, _ -> }, force = true)
        advanceUntilIdle()

        assertNoSentinel(logger, "SENTINEL-ZQ42-UPDATE-8e44")
        assertTrue(
            logger.messages.any { it.contains("Update check failed: NetworkError") },
            "the failure diagnostic keeps the error type: ${logger.messages}"
        )
    }

    // ── Harness ────────────────────────────────────────────────────────────

    private fun manager(vararg services: Service) = ActiveServiceManager(
        MutableStateFlow(services.associateBy { it.key }),
        MutableStateFlow(Configuration.DEFAULT)
    )

    private class FakeDictionary(
        private val response: Result<DictionaryResponse, ServiceError>
    ) : Dictionary {
        override val key = "diag-dictionary"
        override val name = key
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun lookup(request: DictionaryRequest) = response
    }

    private class FakeOCR(
        private val response: Result<OCRResponse, ServiceError>
    ) : OCR {
        override val key = "diag-ocr"
        override val name = key
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun extractText(request: OCRRequest) = response
    }

    private class FakeTTS(
        private val audio: Result<TTSResponse, ServiceError>
    ) : TextToSpeech {
        override val key = "diag-tts"
        override val name = key
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun synthesize(request: TTSRequest) = audio
    }

    private class FakeSpellChecker(
        private val response: Result<SpellCheckResponse, ServiceError>
    ) : SpellChecker {
        override val key = "diag-spell"
        override val name = key
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun check(request: SpellCheckRequest) = response
    }

    private class FakeRewriter(
        private val response: Result<RewriteResponse, ServiceError>
    ) : Rewriter {
        override val key = "diag-rewriter"
        override val name = key
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun rewrite(request: RewriteRequest) = response
    }

    private class FakeSummarizer(
        private val response: Result<SummarizeResponse, ServiceError>
    ) : Summarizer {
        override val key = "diag-summarizer"
        override val name = key
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun summarize(request: SummarizeRequest) = response
    }

    private class ThrowingTranslator(private val failure: Throwable) : Translator {
        override val key = "cmp"
        override val name = "Comparison translator"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            throw failure
    }

    private class FakeAudioPlayer : AudioPlayer {
        override val isPlaying = MutableStateFlow(false)
        override fun play(audio: TTSAudio.Bytes) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    private fun assertNoSentinel(logger: CapturingLogger, vararg sentinels: String) {
        val captured = logger.messages + logger.loggedThrowables
        val leaked = captured.filter { line -> sentinels.any { line.contains(it) } }
        assertTrue(leaked.isEmpty(), "sentinel content leaked into logs: $leaked")
    }
}
