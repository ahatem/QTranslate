package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.FakeProcessRunner
import com.github.ahatem.qtranslate.plugins.systemocr.corruptImageData
import com.github.ahatem.qtranslate.plugins.systemocr.imageData
import com.github.ahatem.qtranslate.plugins.systemocr.unwrap
import com.github.ahatem.qtranslate.plugins.systemocr.unwrapError
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxTesseractBackendTest {

    private fun backend(runner: ProcessRunner, tesseract: String? = "tesseract") =
        LinuxTesseractBackend(runner, tesseract)

    private fun listing(vararg codes: String): String = buildString {
        append("List of available languages in \"/usr/share/tesseract-ocr/5/tessdata/\" (${codes.size}):\n")
        codes.forEach { append(it).append('\n') }
    }

    private fun recognizing(
        listLanguages: String = listing("eng"),
        recognition: ProcessOutcome = ProcessOutcome(0, "text", "", false),
    ) = FakeProcessRunner { command ->
        if (command.contains("--list-langs")) ProcessOutcome(0, listLanguages, "", false) else recognition
    }

    @Test
    fun `a missing tesseract is a configuration error`() = runBlocking {
        val backend = backend(recognizing(), tesseract = null)

        assertTrue(backend.validate().unwrapError() is ServiceError.ConfigurationError)
        assertTrue(backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.ConfigurationError)
    }

    @Test
    fun `recognize returns the text and trims the trailing newline`() = runBlocking {
        val backend = backend(recognizing(recognition = ProcessOutcome(0, "Hello\n\n", "", false)))

        assertEquals("Hello", backend.recognize(imageData(), LanguageCode.ENGLISH).unwrap())
    }

    @Test
    fun `recognize requests the mapped language code`() = runBlocking {
        val runner = recognizing()

        backend(runner).recognize(imageData(), LanguageCode.ENGLISH).unwrap()

        val command = runner.commands.single()
        assertEquals("tesseract", command.first())
        assertEquals(listOf("stdout", "-l", "eng"), command.takeLast(3))
    }

    @Test
    fun `recognize rejects AUTO without running Tesseract`() = runBlocking {
        // Tesseract must be told the language; it cannot detect one. AUTO is refused rather than
        // silently answered with English or whatever happens to be installed first.
        val runner = recognizing()

        val error = backend(runner).recognize(imageData(), LanguageCode.AUTO).unwrapError()

        assertTrue(error is ServiceError.UnsupportedLanguageError)
        assertEquals(LanguageCode.AUTO, error.language)
        assertTrue(runner.commands.isEmpty(), "AUTO must be rejected before any process starts")
    }

    @Test
    fun `a failed language load is an unsupported language`() = runBlocking {
        val backend = backend(
            recognizing(
                recognition = ProcessOutcome(
                    1, "",
                    "Error opening data file /usr/share/tesseract-ocr/5/tessdata/xyz.traineddata\nFailed loading language 'xyz'",
                    false,
                )
            )
        )

        val error = backend.recognize(imageData(), LanguageCode.KOREAN).unwrapError()

        assertTrue(error is ServiceError.UnsupportedLanguageError)
    }

    @Test
    fun `an image failure is invalid input`() = runBlocking {
        val backend = backend(
            recognizing(recognition = ProcessOutcome(1, "", "Error: image format not supported", false))
        )

        assertTrue(backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.InvalidInputError)
    }

    @Test
    fun `an unrecognised failure is an unknown error`() = runBlocking {
        val backend = backend(recognizing(recognition = ProcessOutcome(3, "", "something odd happened", false)))

        assertTrue(backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.UnknownError)
    }

    @Test
    fun `a timeout is reported as a timeout`() = runBlocking {
        val backend = backend(recognizing(recognition = ProcessOutcome(-1, "", "", timedOut = true)))

        assertTrue(backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.TimeoutError)
    }

    @Test
    fun `an explicit language Tesseract cannot name is rejected without running anything`() = runBlocking {
        val runner = recognizing()
        val backend = backend(runner)

        val error = backend.recognize(imageData(), LanguageCode("xyz")).unwrapError()

        assertTrue(error is ServiceError.UnsupportedLanguageError)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `supportedLanguages maps codes and drops non-language models`() = runBlocking {
        val backend = backend(recognizing(listLanguages = listing("eng", "deu", "chi_sim", "osd", "equ")))

        assertEquals(
            setOf(LanguageCode.ENGLISH, LanguageCode.GERMAN, LanguageCode.CHINESE_SIMPLIFIED),
            backend.supportedLanguages().unwrap().languages,
        )
    }

    @Test
    fun `supportedLanguages never claims language detection`() = runBlocking {
        val backend = backend(recognizing(listLanguages = listing("eng")))

        assertFalse(backend.supportedLanguages().unwrap().detectsLanguage)
    }

    @Test
    fun `supportedLanguages fails when Tesseract errors`() = runBlocking {
        val backend = backend(FakeProcessRunner { ProcessOutcome(1, "", "boom", false) })

        assertTrue(backend.supportedLanguages().unwrapError() is ServiceError.UnknownError)
    }

    @Test
    fun `a corrupt image is rejected before Tesseract runs`() = runBlocking {
        val runner = recognizing()

        assertTrue(backend(runner).recognize(corruptImageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.InvalidInputError)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `recognize deletes its temporary image`() = runBlocking {
        val runner = recognizing()

        backend(runner).recognize(imageData(), LanguageCode.ENGLISH).unwrap()

        assertFalse(File(runner.commands.single()[1]).exists())
    }

    @Test
    fun `a program that cannot be launched becomes an Err, not a thrown exception`() = runBlocking {
        val absent = File(Files.createTempDirectory("system-ocr-absent").toFile(), "no-such-tesseract").absolutePath

        val error = LinuxTesseractBackend(RealProcessRunner(), absent)
            .recognize(imageData(), LanguageCode.ENGLISH)
            .unwrapError()

        assertTrue(error is ServiceError.ConfigurationError, "was ${error::class.simpleName}")
    }
}
