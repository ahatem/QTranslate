package com.github.ahatem.qtranslate.plugins.systemocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.systemocr.backend.BackendLanguages
import com.github.ahatem.qtranslate.plugins.systemocr.backend.LinuxTesseractBackend
import com.github.ahatem.qtranslate.plugins.systemocr.backend.ProcessOutcome
import com.github.ahatem.qtranslate.plugins.systemocr.backend.RealProcessRunner
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemOcrServiceTest {

    private val logger = FakePluginContext.SilentLogger

    @Test
    fun `extractText returns the recognized text`() = runBlocking {
        val service = SystemOcrService(FakeBackend(onRecognize = { _, _ -> Ok("Hello\nWorld") }), logger)

        val response = service.extractText(OCRRequest(imageData())).unwrap()

        assertEquals("Hello\nWorld", response.text)
        assertNull(response.confidence)
        assertNull(response.detectedLanguage)
    }

    @Test
    fun `extractText returns an empty response when the engine finds no text`() = runBlocking {
        val service = SystemOcrService(FakeBackend(onRecognize = { _, _ -> Ok("") }), logger)

        assertEquals("", service.extractText(OCRRequest(imageData())).unwrap().text)
    }

    @Test
    fun `extractText forwards the image and language to the backend`() = runBlocking {
        var seenImage: com.github.ahatem.qtranslate.api.ocr.ImageData? = null
        var seenLanguage: LanguageCode? = null
        val service = SystemOcrService(
            FakeBackend(onRecognize = { image, language -> seenImage = image; seenLanguage = language; Ok("x") }),
            logger,
        )
        val image = imageData()

        service.extractText(OCRRequest(image, LanguageCode.JAPANESE)).unwrap()

        assertEquals(image, seenImage)
        assertEquals(LanguageCode.JAPANESE, seenLanguage)
    }

    @Test
    fun `a real engine failure is an error, not an empty success`() = runBlocking {
        val service = SystemOcrService(
            FakeBackend(onRecognize = { _, _ -> Err(ServiceError.UnknownError("engine exploded")) }),
            logger,
        )

        val error = service.extractText(OCRRequest(imageData())).unwrapError()

        assertTrue(error is ServiceError.UnknownError)
    }

    @Test
    fun `an unsupported language is reported as such`() = runBlocking {
        val service = SystemOcrService(
            FakeBackend(
                onRecognize = { _, language ->
                    Err(ServiceError.UnsupportedLanguageError(language, "no data for ${language.tag}"))
                }
            ),
            logger,
        )

        val error = service.extractText(OCRRequest(imageData(), LanguageCode.KOREAN)).unwrapError()

        assertTrue(error is ServiceError.UnsupportedLanguageError)
        assertEquals(LanguageCode.KOREAN, error.language)
    }

    @Test
    fun `fetchSupportedLanguages offers AUTO only when the backend really detects language`() = runBlocking {
        val detecting = SystemOcrService(
            FakeBackend(onLanguages = { Ok(BackendLanguages(setOf(LanguageCode.ENGLISH), detectsLanguage = true)) }),
            logger,
        )

        assertEquals(setOf(LanguageCode.AUTO, LanguageCode.ENGLISH), detecting.fetchSupportedLanguages().unwrap())
    }

    @Test
    fun `a backend that only defaults never advertises AUTO`() = runBlocking {
        val defaulting = SystemOcrService(
            FakeBackend(
                onLanguages = {
                    Ok(BackendLanguages(setOf(LanguageCode.ENGLISH, LanguageCode.GERMAN), detectsLanguage = false))
                }
            ),
            logger,
        )

        // AUTO means "detect the image language" in QTranslate's vocabulary. A backend that falls
        // back to a default language must not be offered as if it detected one.
        assertEquals(
            setOf(LanguageCode.ENGLISH, LanguageCode.GERMAN),
            defaulting.fetchSupportedLanguages().unwrap(),
        )
    }

    @Test
    fun `fetchSupportedLanguages propagates a discovery failure`() = runBlocking {
        val service = SystemOcrService(
            FakeBackend(onLanguages = { Err(ServiceError.ConfigurationError("no engine")) }),
            logger,
        )

        assertTrue(service.fetchSupportedLanguages().unwrapError() is ServiceError.ConfigurationError)
    }

    @Test
    fun `validate delegates to the backend`() = runBlocking {
        val failing = SystemOcrService(
            FakeBackend(onValidate = { Err(ServiceError.ConfigurationError("not installed")) }),
            logger,
        )

        assertTrue(failing.validate().unwrapError() is ServiceError.ConfigurationError)
        assertTrue(SystemOcrService(FakeBackend(), logger).validate().unwrap() == Unit)
    }

    @Test
    fun `identity is stable and the service holds the OCR role`() {
        val service = SystemOcrService(FakeBackend(), logger)

        assertEquals("system-ocr", service.key)
        assertEquals("System OCR (Offline)", service.name)
        assertEquals("1.0.0", service.version)
        assertEquals("assets/system-ocr-icon.svg", service.iconPath)
        assertTrue(service.supportedLanguages is SupportedLanguages.Dynamic)
        assertTrue(ServiceRole.OCR in ServiceRole.of(service))
    }

    @Test
    fun `the service needs no configuration and is free`() {
        val service = SystemOcrService(FakeBackend(), logger)

        assertEquals(false, service.metadata.requiresConfiguration)
        assertEquals(true, service.metadata.isFree)
        assertTrue(service.metadata.notes!!.fallback.contains("Fake Engine"))
    }

    @Test
    fun `an empty successful result is not confused with an error`() = runBlocking {
        val service = SystemOcrService(FakeBackend(onRecognize = { _, _ -> Ok("") }), logger)

        assertTrue(service.extractText(OCRRequest(imageData())).unwrap().text.isEmpty())
    }

    @Test
    fun `a program that cannot be launched is an Err from the public call, not an exception`() = runBlocking {
        // Exercises the real process boundary end to end: an executable that is not there must come
        // back through OCR.extractText as a ServiceError, never as a thrown IOException.
        val absent = File(Files.createTempDirectory("system-ocr-absent").toFile(), "no-such-tesseract").absolutePath
        val service = SystemOcrService(LinuxTesseractBackend(RealProcessRunner(), absent), logger)

        val error = service.extractText(OCRRequest(imageData(), LanguageCode.ENGLISH)).unwrapError()

        assertTrue(error is ServiceError.ConfigurationError, "was ${error::class.simpleName}")
    }

    @Test
    fun `AUTO reaches a backend that detects language unchanged`() = runBlocking {
        var seen: LanguageCode? = null
        val service = SystemOcrService(
            FakeBackend(
                onRecognize = { _, language -> seen = language; Ok("x") },
                onLanguages = { Ok(BackendLanguages(setOf(LanguageCode.ENGLISH), detectsLanguage = true)) },
            ),
            logger,
        )

        service.extractText(OCRRequest(imageData(), LanguageCode.AUTO)).unwrap()

        assertEquals(LanguageCode.AUTO, seen)
    }

    @Test
    fun `AUTO resolves to an installed language when the backend cannot detect`() = runBlocking {
        var seen: LanguageCode? = null
        val service = SystemOcrService(
            FakeBackend(
                onRecognize = { _, language -> seen = language; Ok("x") },
                onLanguages = { Ok(BackendLanguages(setOf(LanguageCode.GERMAN), detectsLanguage = false)) },
            ),
            logger,
        )

        service.extractText(OCRRequest(imageData(), LanguageCode.AUTO)).unwrap()

        assertEquals(LanguageCode.GERMAN, seen)
    }

    @Test
    fun `AUTO with nothing installed is a clear error`() = runBlocking {
        val service = SystemOcrService(
            FakeBackend(onLanguages = { Ok(BackendLanguages(emptySet(), detectsLanguage = false)) }),
            logger,
        )

        val error = service.extractText(OCRRequest(imageData(), LanguageCode.AUTO)).unwrapError()

        assertTrue(error is ServiceError.UnsupportedLanguageError)
        assertEquals(LanguageCode.AUTO, error.language)
        assertTrue(error.message.contains("install language data"), "was '${error.message}'")
    }

    @Test
    fun `AUTO reports a capability failure rather than guessing`() = runBlocking {
        val service = SystemOcrService(
            FakeBackend(onLanguages = { Err(ServiceError.ConfigurationError("engine unavailable")) }),
            logger,
        )

        assertTrue(service.extractText(OCRRequest(imageData(), LanguageCode.AUTO)).unwrapError() is ServiceError.ConfigurationError)
    }

    @Test
    fun `an explicit language is used without consulting the installed set`() = runBlocking {
        var languagesAsked = false
        val service = SystemOcrService(
            FakeBackend(onLanguages = { languagesAsked = true; Ok(BackendLanguages(setOf(LanguageCode.ENGLISH), false)) }),
            logger,
        )

        service.extractText(OCRRequest(imageData(), LanguageCode.KOREAN)).unwrap()

        assertFalse(languagesAsked)
    }

    @Test
    fun `AUTO reaches Tesseract as a concrete installed language`() = runBlocking {
        val runner = FakeProcessRunner { command ->
            if (command.contains("--list-langs")) {
                ProcessOutcome(0, "List of available languages (1):\neng\n", "", false)
            } else {
                ProcessOutcome(0, "recognized", "", false)
            }
        }
        val service = SystemOcrService(LinuxTesseractBackend(runner, "tesseract"), logger)

        assertEquals("recognized", service.extractText(OCRRequest(imageData(), LanguageCode.AUTO)).unwrap().text)
        assertEquals("eng", runner.commands.single { !it.contains("--list-langs") }.last())
    }
}
