package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.argumentValue
import com.github.ahatem.qtranslate.plugins.systemocr.corruptImageData
import com.github.ahatem.qtranslate.plugins.systemocr.helperResponding
import com.github.ahatem.qtranslate.plugins.systemocr.helperWriting
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

class MacVisionOcrBackendTest {

    private val helper: File = Files.createTempFile("vision_ocr", "").toFile().apply { setExecutable(true) }

    private fun backend(runner: ProcessRunner, executable: File? = helper) =
        MacVisionOcrBackend(runner, executable)

    @Test
    fun `recognize returns the recognized text`() = runBlocking {
        val backend = backend(helperWriting("""{"ok":true,"text":"Merci\nBien"}""", outputFlag = "--output"))

        assertEquals("Merci\nBien", backend.recognize(imageData(), LanguageCode.FRENCH).unwrap())
    }

    @Test
    fun `recognize uses the Vision argument form`() = runBlocking {
        val runner = helperWriting("""{"ok":true,"text":"x"}""", outputFlag = "--output")

        backend(runner).recognize(imageData(), LanguageCode.FRENCH).unwrap()

        val command = runner.commands.single()
        assertEquals(helper.absolutePath, command.first())
        assertEquals("recognize", command.argumentValue("--command"))
        assertEquals("fr", command.argumentValue("--language"))
        assertTrue(command.argumentValue("--image") != null)
    }

    @Test
    fun `recognize maps an unsupported language`() = runBlocking {
        val backend = backend(
            helperWriting("""{"ok":false,"category":"unsupported_language","error":"nope"}""", outputFlag = "--output")
        )

        val error = backend.recognize(imageData(), LanguageCode.THAI).unwrapError()

        assertTrue(error is ServiceError.UnsupportedLanguageError)
    }

    @Test
    fun `a missing helper is a configuration error`() = runBlocking {
        val backend = backend(helperWriting("""{"ok":true,"text":"x"}""", outputFlag = "--output"), executable = null)

        assertTrue(backend.validate().unwrapError() is ServiceError.ConfigurationError)
        assertTrue(backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.ConfigurationError)
    }

    @Test
    fun `supportedLanguages reports Vision's own language detection capability`() = runBlocking {
        val backend = backend(
            helperResponding(
                capabilities = """{"ok":true,"languages":["en-US","fr-FR"],"autoDetect":true}""",
                recognition = """{"ok":true,"text":"x"}""",
                outputFlag = "--output",
            )
        )

        val supported = backend.supportedLanguages().unwrap()

        assertEquals(setOf(LanguageCode("en-US"), LanguageCode("fr-FR")), supported.languages)
        assertTrue(supported.detectsLanguage)
    }

    @Test
    fun `supportedLanguages reports no detection when Vision lacks it`() = runBlocking {
        val backend = backend(
            helperResponding(
                capabilities = """{"ok":true,"languages":["en-US"],"autoDetect":false}""",
                recognition = """{"ok":true,"text":"x"}""",
                outputFlag = "--output",
            )
        )

        assertFalse(backend.supportedLanguages().unwrap().detectsLanguage)
    }

    @Test
    fun `supportedLanguages reports no detection when the helper omits the flag`() = runBlocking {
        // An older helper that does not answer with autoDetect must not be read as capable.
        val backend = backend(helperWriting("""{"ok":true,"languages":["en-US"]}""", outputFlag = "--output"))

        assertFalse(backend.supportedLanguages().unwrap().detectsLanguage)
    }

    @Test
    fun `supportedLanguages accepts a scalar language list`() = runBlocking {
        val backend = backend(helperWriting("""{"ok":true,"languages":"en-US","autoDetect":true}""", outputFlag = "--output"))

        assertEquals(setOf(LanguageCode("en-US")), backend.supportedLanguages().unwrap().languages)
    }

    @Test
    fun `a corrupt image is rejected before the helper is started`() = runBlocking {
        val runner = helperWriting("""{"ok":true,"text":"x"}""", outputFlag = "--output")
        val backend = backend(runner)

        assertTrue(backend.recognize(corruptImageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.InvalidInputError)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `recognize deletes its temporary files`() = runBlocking {
        val runner = helperWriting("""{"ok":true,"text":"x"}""", outputFlag = "--output")

        backend(runner).recognize(imageData(), LanguageCode.ENGLISH).unwrap()

        val command = runner.commands.single()
        assertFalse(File(command.argumentValue("--image")!!).exists())
        assertFalse(File(command.argumentValue("--output")!!).exists())
    }

    @Test
    fun `AUTO asks Vision to detect the language`() = runBlocking {
        // An empty language is how the helper is told to use automaticallyDetectsLanguage; the
        // helper fails with unsupported_language on a macOS that cannot detect.
        val runner = helperWriting("""{"ok":true,"text":"x"}""", outputFlag = "--output")

        backend(runner).recognize(imageData(), LanguageCode.AUTO).unwrap()

        assertEquals("", runner.commands.single().argumentValue("--language"))
    }

    @Test
    fun `an AUTO request Vision cannot honour is an unsupported language`() = runBlocking {
        val backend = backend(
            helperWriting(
                """{"ok":false,"category":"unsupported_language","error":"this macOS cannot detect"}""",
                outputFlag = "--output",
            )
        )

        val error = backend.recognize(imageData(), LanguageCode.AUTO).unwrapError()

        assertTrue(error is ServiceError.UnsupportedLanguageError)
        assertEquals(LanguageCode.AUTO, error.language)
    }
}
