package com.github.ahatem.qtranslate.core.document

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentTranslationUseCaseTest {
    private val directory = Files.createTempDirectory("qtranslate-document-test").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `text translation preserves blank lines and trailing newline`() = runBlocking {
        val input = File(directory, "source.txt").apply { writeText("Hello\n\nWorld\n") }
        val output = File(directory, "translated.txt")

        useCase().invoke(request(input, output)) { }

        assertEquals("[Hello]\n\n[World]\n", output.readText())
    }

    @Test
    fun `srt translation preserves timing and inline markup`() = runBlocking {
        val input = File(directory, "source.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:03,000\n<i>Hello</i> world\n")
        }
        val output = File(directory, "translated.srt")

        useCase().invoke(request(input, output)) { }

        val translated = output.readText()
        assertTrue("00:00:01,000 --> 00:00:03,000" in translated)
        assertTrue("<i>" in translated && "</i>" in translated)
        assertTrue("[" in translated)
    }

    @Test
    fun `docx translation retains run styling`() = runBlocking {
        val input = File(directory, "source.docx")
        XWPFDocument().use { document ->
            document.createParagraph().createRun().apply {
                isBold = true
                setText("Hello")
            }
            input.outputStream().use(document::write)
        }
        val output = File(directory, "translated.docx")

        useCase().invoke(request(input, output)) { }

        XWPFDocument(output.inputStream()).use { translated ->
            val run = translated.paragraphs.single().runs.single()
            assertEquals("[Hello]", run.text())
            assertTrue(run.isBold)
        }
    }

    @Test
    fun `provider failure leaves an existing output untouched`() = runBlocking {
        val input = File(directory, "source.txt").apply { writeText("First\nSecond") }
        val output = File(directory, "translated.txt").apply { writeText("previous") }
        val translator = FakeTranslator(failOn = "Second")

        assertFailsWith<DocumentTranslationException> {
            useCase(translator).invoke(request(input, output)) { }
        }

        assertEquals("previous", output.readText())
    }

    private fun request(input: File, output: File) = DocumentTranslationRequest(
        inputFile = input,
        outputFile = output,
        sourceLanguage = LanguageCode("en"),
        targetLanguage = LanguageCode("fr")
    )

    private fun useCase(translator: Translator = FakeTranslator()): DocumentTranslationUseCase {
        val activeServices = MutableStateFlow(mapOf(translator.id to translator))
        val configuration = MutableStateFlow(Configuration.DEFAULT)
        return DocumentTranslationUseCase(
            ActiveServiceManager(activeServices, configuration),
            object : LoggerFactory {
                override fun getLogger(name: String): Logger = NoOpLogger
            }
        )
    }

    private class FakeTranslator(private val failOn: String? = null) : Translator {
        override val id = "test-translator"
        override val name = "Test Translator"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(
            request: TranslationRequest
        ): Result<TranslationResponse, ServiceError> = if (request.text == failOn) {
            Err(ServiceError.NetworkError("Offline"))
        } else {
            Ok(TranslationResponse("[${request.text}]"))
        }
    }

    private object NoOpLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
}
