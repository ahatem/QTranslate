package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.systemservices.backend.BackendLanguages
import com.github.ahatem.qtranslate.plugins.systemservices.backend.LinuxTesseractBackend
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessOutcome
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.runBlocking
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
    fun `extractStructuredText returns provider geometry when the backend supplies it`() = runBlocking {
        val region = com.github.ahatem.qtranslate.api.ocr.OCRTextRegion(
            text = "Hello",
            left = 4,
            top = 8,
            right = 64,
            bottom = 24,
            kind = com.github.ahatem.qtranslate.api.ocr.OCRRegionKind.LINE,
        )
        val service = SystemOcrService(
            FakeBackend(
                onRecognizeStructured = { _, _ ->
                    Ok(
                        com.github.ahatem.qtranslate.plugins.systemservices.backend.SystemOcrRecognition(
                            text = "Hello",
                            regions = listOf(region),
                        )
                    )
                }
            ),
            logger,
        )

        val response = service.extractStructuredText(OCRRequest(imageData())).unwrap()

        assertEquals("Hello", response.text)
        assertEquals(listOf(region), response.regions)
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
    fun `AUTO is offered only when the backend really detects the language`() = runBlocking {
        // AUTO means "detect the image language" in QTranslate's vocabulary. A backend that falls
        // back to a default language must not be offered as if it detected one.
        val detecting = SystemOcrService(
            FakeBackend(onLanguages = { Ok(BackendLanguages(setOf(LanguageCode.ENGLISH), detectsLanguage = true)) }),
            logger,
        )
        val defaulting = SystemOcrService(
            FakeBackend(
                onLanguages = {
                    Ok(BackendLanguages(setOf(LanguageCode.ENGLISH, LanguageCode.GERMAN), detectsLanguage = false))
                }
            ),
            logger,
        )

        assertEquals(setOf(LanguageCode.AUTO, LanguageCode.ENGLISH), detecting.fetchSupportedLanguages().unwrap())
        assertEquals(setOf(LanguageCode.ENGLISH, LanguageCode.GERMAN), defaulting.fetchSupportedLanguages().unwrap())
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
