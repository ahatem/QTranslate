package com.github.ahatem.qtranslate.core.document

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.api.translator.BatchTranslationRequest
import com.github.ahatem.qtranslate.api.translator.BatchTranslationResponse
import com.github.ahatem.qtranslate.api.translator.BatchTranslator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.apache.poi.util.Units
import org.apache.poi.xwpf.usermodel.BreakType
import org.apache.poi.xwpf.usermodel.Document
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.wp.usermodel.HeaderFooterType
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
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
    fun `docx translation preserves structure and translates paragraphs with context`() = runBlocking {
        val input = File(directory, "styled.docx")
        XWPFDocument().use { document ->
            document.createParagraph().apply {
                createRun().apply { isBold = true; setText("Hello ") }
                createRun().apply { isItalic = true; color = "336699"; setText("world") }
            }
            document.createTable(1, 1).getRow(0).getCell(0).apply {
                paragraphs.first().createRun().setText("Table text")
            }
            document.createParagraph().createRun().apply {
                addPicture(
                    ByteArrayInputStream(Base64.getDecoder().decode(pixelPng)),
                    Document.PICTURE_TYPE_PNG,
                    "pixel.png",
                    Units.toEMU(1.0),
                    Units.toEMU(1.0)
                )
                addBreak(BreakType.PAGE)
            }
            document.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Header text")
            document.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Footer text")
            input.outputStream().use(document::write)
        }
        val output = File(directory, "translated.docx")
        val translator = FakeBatchTranslator()

        useCase(translator).invoke(request(input, output)) { }

        XWPFDocument(output.inputStream()).use { translated ->
            val paragraph = translated.paragraphs.first()
            assertEquals("[Hello world]", paragraph.text)
            assertTrue(paragraph.runs.first().isBold)
            assertTrue(paragraph.runs.last().isItalic)
            assertEquals("336699", paragraph.runs.last().color)
            assertEquals("[Table text]", translated.tables.single().getRow(0).getCell(0).text)
            assertEquals("[Header text]", translated.headerList.single().text.trim())
            assertEquals("[Footer text]", translated.footerList.single().text.trim())
            assertEquals(1, translated.allPictures.size)
            assertEquals(1, translated.paragraphs.last().runs.single().ctr.sizeOfBrArray())
        }
        assertTrue(translator.requests.flatten().contains("Hello world"))
        assertTrue(translator.requests.none { batch -> batch.any { it == "Hello " || it == "world" } })
    }

    @Test
    fun `document translation uses provider batch limits and preserves order`() = runBlocking {
        val input = File(directory, "source.txt").apply { writeText("One\nTwo\nThree") }
        val output = File(directory, "translated.txt")
        val translator = FakeBatchTranslator(maxBatchSize = 2)

        useCase(translator).invoke(request(input, output)) { }

        assertEquals("[One]\n[Two]\n[Three]", output.readText())
        assertEquals(listOf(listOf("One", "Two"), listOf("Three")), translator.requests)
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

    private class FakeBatchTranslator(
        override val maxBatchSize: Int = 50
    ) : BatchTranslator {
        override val id = "test-batch-translator"
        override val name = "Test Batch Translator"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        val requests = mutableListOf<List<String>>()

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            Ok(TranslationResponse("[${request.text}]"))

        override suspend fun translateBatch(
            request: BatchTranslationRequest
        ): Result<BatchTranslationResponse, ServiceError> {
            requests += request.texts
            return Ok(BatchTranslationResponse(request.texts.map { TranslationResponse("[$it]") }))
        }
    }

    private object NoOpLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private companion object {
        const val pixelPng =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
