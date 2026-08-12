package com.github.ahatem.qtranslate.core.document

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.BatchTranslationRequest
import com.github.ahatem.qtranslate.api.translator.BatchTranslator
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.BreakType
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

enum class DocumentFormat(val extensions: Set<String>) {
    DOCX(setOf("docx")),
    PDF(setOf("pdf")),
    TEXT(setOf("txt")),
    SRT(setOf("srt")),
    VTT(setOf("vtt"));

    companion object {
        fun from(file: File): DocumentFormat? {
            val extension = file.extension.lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }
    }
}

enum class PdfTranslationMode {
    TEXT_ONLY,
    LAYOUT_AWARE
}

data class DocumentTranslationRequest(
    val inputFile: File,
    val outputFile: File,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val pdfMode: PdfTranslationMode = PdfTranslationMode.LAYOUT_AWARE
)

data class DocumentTranslationProgress(
    val completedSegments: Int,
    val totalSegments: Int,
    val currentText: String = ""
) {
    val percent: Int
        get() = if (totalSegments == 0) 100 else (completedSegments * 100 / totalSegments)
}

class DocumentTranslationException(
    override val message: String,
    val serviceError: ServiceError? = null,
    override val cause: Throwable? = serviceError?.cause
) : Exception(message, cause)

/** Translates supported document formats through the currently selected translator plugin. */
class DocumentTranslationUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("DocumentTranslationUseCase")

    suspend operator fun invoke(
        request: DocumentTranslationRequest,
        onProgress: (DocumentTranslationProgress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        validate(request)
        val translator = activeServiceManager.getActiveService<Translator>(ServiceType.TRANSLATOR)
            ?: throw DocumentTranslationException("No translator is active. Enable a translator plugin first.")
        val format = DocumentFormat.from(request.inputFile)
            ?: throw DocumentTranslationException("Unsupported file type: .${request.inputFile.extension}")

        val parent = request.outputFile.absoluteFile.parentFile
            ?: throw DocumentTranslationException("The output folder is invalid.")
        parent.mkdirs()
        val temporary = Files.createTempFile(parent.toPath(), ".qtranslate-", ".tmp").toFile()

        try {
            logger.info("Translating ${request.inputFile.name} with '${translator.name}'")
            when (format) {
                DocumentFormat.DOCX -> translateDocx(request, temporary, translator, onProgress)
                DocumentFormat.PDF -> translatePdf(request, temporary, translator, onProgress)
                DocumentFormat.TEXT -> translatePlainText(request, temporary, translator, onProgress)
                DocumentFormat.SRT -> translateSubtitle(request, temporary, translator, srtTimestamp, onProgress)
                DocumentFormat.VTT -> translateSubtitle(request, temporary, translator, vttTimestamp, onProgress)
            }
            moveAtomically(temporary, request.outputFile)
            logger.info("Document translation saved to ${request.outputFile.absolutePath}")
            request.outputFile
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun validate(request: DocumentTranslationRequest) {
        require(request.targetLanguage != LanguageCode.AUTO) { "Target language cannot be Auto Detect." }
        if (!request.inputFile.isFile) {
            throw DocumentTranslationException("Input file does not exist: ${request.inputFile.absolutePath}")
        }
        if (request.inputFile.canonicalFile == request.outputFile.canonicalFile) {
            throw DocumentTranslationException("Choose a different output file to keep the original unchanged.")
        }
        if (DocumentFormat.from(request.inputFile) == DocumentFormat.PDF) {
            val expectedExtension = when (request.pdfMode) {
                PdfTranslationMode.TEXT_ONLY -> "txt"
                PdfTranslationMode.LAYOUT_AWARE -> "docx"
            }
            if (request.outputFile.extension.lowercase() != expectedExtension) {
                throw DocumentTranslationException(
                    "${request.pdfMode.displayName} PDF translations must be saved as ${expectedExtension.uppercase()}."
                )
            }
        }
    }

    private suspend fun translatePlainText(
        request: DocumentTranslationRequest,
        output: File,
        translator: Translator,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        val original = request.inputFile.readText(StandardCharsets.UTF_8)
        val lineEnding = if ("\r\n" in original) "\r\n" else "\n"
        val trailingNewline = original.endsWith("\n") || original.endsWith("\r")
        val lines = original.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        if (trailingNewline && lines.lastOrNull()?.isEmpty() == true) lines.removeLast()
        val indexes = lines.indices.filter { lines[it].isNotBlank() }

        translateIndexed(indexes, lines::get, { index, value -> lines[index] = value }, request, translator, onProgress)
        output.writeText(lines.joinToString(lineEnding) + if (trailingNewline) lineEnding else "", StandardCharsets.UTF_8)
    }

    private suspend fun translateSubtitle(
        request: DocumentTranslationRequest,
        output: File,
        translator: Translator,
        timestampPattern: Regex,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        val original = request.inputFile.readText(StandardCharsets.UTF_8)
        val lineEnding = if ("\r\n" in original) "\r\n" else "\n"
        val trailingNewline = original.endsWith("\n") || original.endsWith("\r")
        val lines = original.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        if (trailingNewline && lines.lastOrNull()?.isEmpty() == true) lines.removeLast()
        val indexes = mutableListOf<Int>()
        var inCue = false
        for (index in lines.indices) {
            val line = lines[index]
            when {
                timestampPattern.containsMatchIn(line) -> inCue = true
                line.isBlank() -> inCue = false
                inCue && !line.startsWith("NOTE") && !line.startsWith("STYLE") && !line.startsWith("REGION") -> indexes += index
            }
        }

        translateIndexed(
            indexes,
            { index -> stripAndRememberMarkup(lines[index]).text },
            { index, value ->
                val protected = stripAndRememberMarkup(lines[index])
                lines[index] = protected.restore(value)
            },
            request,
            translator,
            onProgress
        )
        output.writeText(lines.joinToString(lineEnding) + if (trailingNewline) lineEnding else "", StandardCharsets.UTF_8)
    }

    private suspend fun translateDocx(
        request: DocumentTranslationRequest,
        output: File,
        translator: Translator,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        request.inputFile.inputStream().use { input ->
            XWPFDocument(input).use { document ->
                val paragraphs = buildList {
                    addParagraphs(document.paragraphs, document.tables)
                    document.headerList.forEach { addParagraphs(it.paragraphs, it.tables) }
                    document.footerList.forEach { addParagraphs(it.paragraphs, it.tables) }
                }.filter { paragraphText(it).isNotBlank() }

                translateIndexed(
                    paragraphs,
                    ::paragraphText,
                    ::replaceParagraphText,
                    request,
                    translator,
                    onProgress
                )
                output.outputStream().use(document::write)
            }
        }
    }

    private suspend fun translatePdf(
        request: DocumentTranslationRequest,
        output: File,
        translator: Translator,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        Loader.loadPDF(request.inputFile).use { pdf ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val pages = (1..pdf.numberOfPages).map { page ->
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(pdf).trimEnd()
            }.toMutableList()
            val indexes = pages.indices.filter { pages[it].isNotBlank() }
            translateIndexed(indexes, pages::get, { index, value -> pages[index] = value }, request, translator, onProgress)

            when (request.pdfMode) {
                PdfTranslationMode.TEXT_ONLY -> output.writeText(
                    pages.joinToString("\n\u000C\n"),
                    StandardCharsets.UTF_8
                )
                PdfTranslationMode.LAYOUT_AWARE -> XWPFDocument().use { document ->
                    pages.forEachIndexed { index, text ->
                        text.lines().forEach { line -> document.createParagraph().createRun().setText(line) }
                        if (index < pages.lastIndex) document.createParagraph().createRun().addBreak(BreakType.PAGE)
                    }
                    output.outputStream().use(document::write)
                }
            }
        }
    }

    private suspend fun <T> translateIndexed(
        items: List<T>,
        getText: (T) -> String,
        setText: (T, String) -> Unit,
        request: DocumentTranslationRequest,
        translator: Translator,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        onProgress(DocumentTranslationProgress(0, items.size))
        val segments = items.map { it to getText(it) }.filter { it.second.isNotBlank() }
        val batchTranslator = translator.getCapability(BatchTranslator::class.java)
        if (batchTranslator == null) {
            segments.forEachIndexed { position, (item, source) ->
                coroutineContext.ensureActive()
                setText(item, translateSegment(source, request, translator))
                onProgress(DocumentTranslationProgress(position + 1, segments.size, source.take(80)))
            }
            return
        }

        var completed = 0
        segments.chunkedFor(batchTranslator).forEach { batch ->
            coroutineContext.ensureActive()
            val translated = translateBatch(batch.map { it.second }, request, batchTranslator)
            batch.zip(translated).forEach { (segment, value) ->
                val (item, source) = segment
                setText(item, value)
                completed++
                onProgress(DocumentTranslationProgress(completed, segments.size, source.take(80)))
            }
        }
    }

    private fun <T> List<Pair<T, String>>.chunkedFor(translator: BatchTranslator): List<List<Pair<T, String>>> {
        val batches = mutableListOf<MutableList<Pair<T, String>>>()
        val maxItems = translator.maxBatchSize.coerceAtLeast(1)
        val maxCharacters = translator.maxBatchCharacters.coerceAtLeast(1)
        for (segment in this) {
            val current = batches.lastOrNull()
            val fits = current != null &&
                current.size < maxItems &&
                current.sumOf { it.second.length } + segment.second.length <= maxCharacters
            if (fits) current.add(segment) else batches += mutableListOf(segment)
        }
        return batches
    }

    private suspend fun translateBatch(
        texts: List<String>,
        request: DocumentTranslationRequest,
        translator: BatchTranslator
    ): List<String> {
        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            translator.translateBatch(
                BatchTranslationRequest(texts, request.sourceLanguage, request.targetLanguage)
            )
        } ?: throw DocumentTranslationException("Translation timed out while processing the document.")

        return result.fold(
            success = { response ->
                if (response.translations.size != texts.size) {
                    throw DocumentTranslationException(
                        "${translator.name} returned ${response.translations.size} translations for ${texts.size} segments."
                    )
                }
                response.translations.map { it.translatedText }
            },
            failure = { throw DocumentTranslationException(it.message, it) }
        )
    }

    private suspend fun translateSegment(
        text: String,
        request: DocumentTranslationRequest,
        translator: Translator
    ): String {
        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            translator.translate(TranslationRequest(text, request.sourceLanguage, request.targetLanguage))
        } ?: throw DocumentTranslationException("Translation timed out while processing the document.")

        return result.fold(
            success = { it.translatedText },
            failure = { throw DocumentTranslationException(it.message, it) }
        )
    }

    private fun MutableList<XWPFParagraph>.addParagraphs(
        paragraphs: List<XWPFParagraph>,
        tables: List<XWPFTable>
    ) {
        addAll(paragraphs)
        tables.forEach { table ->
            table.rows.flatMap { it.tableCells }.forEach { cell ->
                addParagraphs(cell.paragraphs, cell.tables)
            }
        }
    }

    private fun paragraphText(paragraph: XWPFParagraph): String =
        paragraph.runs.joinToString(separator = "") { it.text() }

    /** Reuses the original runs and XML nodes so formatting and embedded content remain in place. */
    private fun replaceParagraphText(paragraph: XWPFParagraph, translated: String) {
        val textRuns = paragraph.runs.filter { it.text().isNotEmpty() }
        if (textRuns.isEmpty()) return
        val originalLength = textRuns.sumOf { it.text().length }.coerceAtLeast(1)
        var originalOffset = 0
        var translatedOffset = 0

        textRuns.forEachIndexed { index, run ->
            originalOffset += run.text().length
            val end = if (index == textRuns.lastIndex) {
                translated.length
            } else {
                (translated.length.toLong() * originalOffset / originalLength).toInt()
            }.coerceIn(translatedOffset, translated.length)
            replaceRunText(run, translated.substring(translatedOffset, end))
            translatedOffset = end
        }
    }

    private fun replaceRunText(run: XWPFRun, text: String) {
        val textNodes = run.ctr.tList
        if (textNodes.isEmpty()) {
            run.setText(text)
            return
        }
        run.setText(text, 0)
        textNodes.drop(1).forEach { it.stringValue = "" }
    }

    private data class ProtectedMarkup(val text: String, val tokens: List<Pair<String, String>>) {
        fun restore(translated: String): String {
            var restored = translated
            tokens.forEach { (token, markup) -> restored = restored.replace(token, markup) }
            return restored
        }
    }

    private fun stripAndRememberMarkup(text: String): ProtectedMarkup {
        val tokens = mutableListOf<Pair<String, String>>()
        var protected = text
        markup.findAll(text).toList().asReversed().forEachIndexed { index, match ->
            val token = "__QT_MARKUP_${index}__"
            tokens += token to match.value
            protected = protected.replaceRange(match.range, token)
        }
        return ProtectedMarkup(protected, tokens)
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val srtTimestamp = Regex("\\d{2}:\\d{2}:\\d{2},\\d{3}\\s+-->\\s+\\d{2}:\\d{2}:\\d{2},\\d{3}")
        val vttTimestamp = Regex("(?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3}\\s+-->\\s+(?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3}")
        val markup = Regex("<[^>]+>|\\{\\\\[^}]+}")
    }
}

private val PdfTranslationMode.displayName: String
    get() = when (this) {
        PdfTranslationMode.TEXT_ONLY -> "Text-only"
        PdfTranslationMode.LAYOUT_AWARE -> "Layout-aware"
    }
