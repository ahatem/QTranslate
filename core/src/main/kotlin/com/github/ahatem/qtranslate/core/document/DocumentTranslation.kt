package com.github.ahatem.qtranslate.core.document

import com.github.ahatem.qtranslate.core.document.docx.DocxDocument
import com.github.ahatem.qtranslate.core.document.pdf.PdfDocument
import com.github.ahatem.qtranslate.core.document.text.TextDocuments
import com.github.ahatem.qtranslate.core.document.text.SubtitleDocument
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        val translator = activeServiceManager.getActiveService<Translator>(ServiceRole.TRANSLATOR)
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
                DocumentFormat.DOCX -> DocxDocument.translateDocx(request, temporary, translator, onProgress)
                DocumentFormat.PDF -> PdfDocument.translatePdf(request, temporary, translator, onProgress)
                DocumentFormat.TEXT -> TextDocuments.translatePlainText(request, temporary, translator, onProgress)
                DocumentFormat.SRT -> SubtitleDocument.translateSubtitle(request, temporary, translator, srtTimestamp, onProgress)
                DocumentFormat.VTT -> SubtitleDocument.translateSubtitle(request, temporary, translator, vttTimestamp, onProgress)
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
                PdfTranslationMode.LAYOUT_AWARE -> "pdf"
            }
            if (request.outputFile.extension.lowercase() != expectedExtension) {
                throw DocumentTranslationException(
                    "${request.pdfMode.displayName} PDF translations must be saved as ${expectedExtension.uppercase()}."
                )
            }
        }
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
    }
}

private val PdfTranslationMode.displayName: String
    get() = when (this) {
        PdfTranslationMode.TEXT_ONLY -> "Text-only"
        PdfTranslationMode.LAYOUT_AWARE -> "Best-effort appearance"
    }
