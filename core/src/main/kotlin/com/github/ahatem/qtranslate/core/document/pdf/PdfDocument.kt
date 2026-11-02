package com.github.ahatem.qtranslate.core.document.pdf

import com.github.ahatem.qtranslate.core.document.*
import com.github.ahatem.qtranslate.core.document.common.translateIndexed
import com.github.ahatem.qtranslate.api.translator.Translator
import kotlinx.coroutines.ensureActive
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

internal object PdfDocument {
    suspend fun translatePdf(
        request: DocumentTranslationRequest,
        output: File,
        translator: Translator,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        Loader.loadPDF(request.inputFile).use { pdf ->
            when (request.pdfMode) {
                PdfTranslationMode.TEXT_ONLY -> translatePdfTextOnly(pdf, request, output, translator, onProgress)
                PdfTranslationMode.LAYOUT_AWARE -> translatePdfWithPageAppearance(
                    pdf,
                    request,
                    output,
                    translator,
                    onProgress
                )
            }
        }
    }

    private suspend fun translatePdfTextOnly(
        pdf: PDDocument,
        request: DocumentTranslationRequest,
        output: File,
        translator: Translator,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        val pages = (1..pdf.numberOfPages).map { page ->
            stripper.startPage = page
            stripper.endPage = page
            stripper.getText(pdf).trimEnd()
        }.toMutableList()
        requirePdfText(pdf, pages)
        val indexes = pages.indices.filter { pages[it].isNotBlank() }
        translateIndexed(indexes, pages::get, { index, value -> pages[index] = value }, request, translator, onProgress)
        output.writeText(pages.joinToString("\n\u000C\n"), StandardCharsets.UTF_8)
    }

    private suspend fun translatePdfWithPageAppearance(
        pdf: PDDocument,
        request: DocumentTranslationRequest,
        output: File,
        translator: Translator,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        val blocksByPage = (0 until pdf.numberOfPages).map { pageIndex ->
            groupPdfBlocks(PositionedTextStripper(pageIndex + 1).extract(pdf))
        }
        requirePdfText(pdf, blocksByPage.map { blocks -> blocks.joinToString(" ") { it.sourceText } })
        val blocks = blocksByPage.flatten()
        translateIndexed(
            blocks,
            PdfTextBlock::sourceText,
            { block, translated -> block.translatedText = translated },
            request,
            translator,
            onProgress
        )

        val renderer = TextFreePdfRenderer(pdf)
        PDDocument().use { translatedPdf ->
            val vectorFonts = PdfVectorFonts(translatedPdf)
            translatedPdf.documentInformation.title = pdf.documentInformation.title
            translatedPdf.documentInformation.author = pdf.documentInformation.author
            translatedPdf.documentInformation.subject = pdf.documentInformation.subject
            translatedPdf.documentInformation.keywords = pdf.documentInformation.keywords

            blocksByPage.forEachIndexed { pageIndex, pageBlocks ->
                coroutineContext.ensureActive()
                val image = renderer.renderImageWithDPI(pageIndex, PDF_RENDER_DPI, ImageType.RGB)
                val vectorLines = prepareTranslatedBlocks(image, pageBlocks, vectorFonts)

                val widthPoints = image.width * PDF_POINTS_PER_INCH / PDF_RENDER_DPI
                val heightPoints = image.height * PDF_POINTS_PER_INCH / PDF_RENDER_DPI
                val outputPage = PDPage(PDRectangle(widthPoints, heightPoints))
                translatedPdf.addPage(outputPage)
                val pageImage = LosslessFactory.createFromImage(translatedPdf, image)
                PDPageContentStream(translatedPdf, outputPage).use { content ->
                    content.drawImage(pageImage, 0f, 0f, widthPoints, heightPoints)
                    drawVectorLines(content, vectorLines, vectorFonts, heightPoints)
                }
            }
            translatedPdf.save(output)
        }
    }

    private fun requirePdfText(pdf: PDDocument, pageTexts: List<String>) {
        if (pageTexts.any(String::isNotBlank)) return
        val containsImages = pdf.pages.any { page ->
            page.resources.xObjectNames.any { name ->
                runCatching { page.resources.getXObject(name) is PDImageXObject }.getOrDefault(false)
            }
        }
        val message = if (containsImages) {
            "This PDF appears to contain scanned pages without selectable text. Run OCR before translating it."
        } else {
            "No selectable text was found in this PDF. Run OCR before translating it."
        }
        throw DocumentTranslationException(message)
    }

}
