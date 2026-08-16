package com.github.ahatem.qtranslate.core.document

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.BatchTranslationRequest
import com.github.ahatem.qtranslate.api.translator.BatchTranslator
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PageDrawer
import org.apache.pdfbox.rendering.PageDrawerParameters
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.text.AttributedString
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.awt.font.TextAttribute
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

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
                PdfTranslationMode.LAYOUT_AWARE -> "pdf"
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

    private fun groupPdfBlocks(lines: List<PdfTextBlock>): List<PdfTextBlock> {
        if (lines.size < 2) return lines
        val sorted = lines.sortedWith(compareBy(PdfTextBlock::top, PdfTextBlock::x))
        val groups = mutableListOf<MutableList<PdfTextBlock>>()

        sorted.forEach { line ->
            val current = groups.lastOrNull()
            val previous = current?.lastOrNull()
            if (previous == null || !belongsToSameParagraph(current, previous, line)) {
                groups += mutableListOf(line)
            } else {
                current += line
            }
        }

        return groups.map { group ->
            if (group.size == 1) return@map group.single()
            val left = group.minOf(PdfTextBlock::x)
            val right = group.maxOf { it.x + it.width }
            val top = group.minOf(PdfTextBlock::top)
            val bottom = group.maxOf { it.top + it.height }
            val first = group.first()
            first.copy(
                sourceText = joinPdfLines(group.map(PdfTextBlock::sourceText)),
                x = left,
                top = top,
                width = right - left,
                height = bottom - top,
                translatedText = ""
            )
        }
    }

    private fun belongsToSameParagraph(
        group: List<PdfTextBlock>,
        previous: PdfTextBlock,
        next: PdfTextBlock
    ): Boolean {
        val baselineStep = next.top - previous.top
        val comparableSize = max(previous.fontSizePoints, next.fontSizePoints)
        if (baselineStep <= 0f || baselineStep > comparableSize * 1.5f) return false
        if (kotlin.math.abs(previous.fontSizePoints - next.fontSizePoints) > comparableSize * 0.2f) return false

        val groupStartsWithBullet = group.first().sourceText.trimStart().startsWithAnyBullet()
        val nextStartsWithBullet = next.sourceText.trimStart().startsWithAnyBullet()
        if (nextStartsWithBullet) return false
        if (groupStartsWithBullet) return next.x >= group.first().x - 1f && next.x - group.first().x <= 18f

        val first = group.first()
        val firstIsStandaloneItalic = group.size == 1 && first.fontStyle and Font.ITALIC != 0
        val nextIsRegular = next.fontStyle and Font.ITALIC == 0
        if (firstIsStandaloneItalic && nextIsRegular && next.x - first.x > 8f) return false
        return kotlin.math.abs(next.x - first.x) <= 20f
    }

    private fun joinPdfLines(lines: List<String>): String {
        val result = StringBuilder()
        lines.forEach { line ->
            val clean = line.trim()
            if (result.isEmpty()) {
                result.append(clean)
            } else if (result.last() in PDF_LINE_END_HYPHENS) {
                result.setLength(result.length - 1)
                result.append(clean)
            } else {
                result.append(' ').append(clean)
            }
        }
        return result.toString()
    }

    private fun String.startsWithAnyBullet(): Boolean = firstOrNull() in PDF_BULLETS

    private fun prepareTranslatedBlocks(
        image: BufferedImage,
        blocks: List<PdfTextBlock>,
        vectorFonts: PdfVectorFonts
    ): List<PdfVectorLine> {
        val scale = PDF_RENDER_DPI / PDF_POINTS_PER_INCH
        val contentRight = blocks.maxOfOrNull { it.x + it.width } ?: 0f
        val graphics = image.createGraphics()
        val vectorLines = mutableListOf<PdfVectorLine>()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

            blocks.forEach { block ->
                val bounds = block.pixelBounds(scale, image, contentRight)
                if (bounds.width < 2 || bounds.height < 2 || block.translatedText.isBlank()) return@forEach
                val background = sampleBackground(image, bounds)
                val color = contrastingTextColor(background)
                val lines = fitText(graphics, block, bounds, scale, color, vectorFonts)
                val pdfFont = vectorFonts.forStyle(block.fontFamily, block.fontStyle)
                val canDrawAsVector = lines.all { line ->
                    line.layout.isLeftToRight && runCatching { pdfFont.encode(line.text) }.isSuccess
                }
                if (canDrawAsVector) {
                    vectorLines += lines.map { it.toVectorLine(block.fontFamily, block.fontStyle, color) }
                } else {
                    drawRasterLines(graphics, bounds, lines, color)
                }
            }
        } finally {
            graphics.dispose()
        }
        return vectorLines
    }

    private fun fitText(
        graphics: java.awt.Graphics2D,
        block: PdfTextBlock,
        bounds: Rectangle,
        scale: Float,
        color: Color,
        vectorFonts: PdfVectorFonts
    ): List<FittedPdfLine> {
        val cleanText = block.translatedText.replace(Regex("\\s+"), " ").trim()
        if (cleanText.isEmpty()) return emptyList()
        val context = graphics.fontRenderContext
        val minimumSize = max(6f * scale, block.fontSizePoints * scale * 0.68f)
        var size = max(minimumSize, block.fontSizePoints * scale)
        var layouts: List<MeasuredPdfLine>
        do {
            val font = vectorFonts.awtForText(block.fontFamily, block.fontStyle, cleanText).deriveFont(size)
            layouts = measureWrappedLines(cleanText, font, context, bounds.width.toFloat())
            val requiredHeight = layouts.sumOf { (it.layout.ascent + it.layout.descent + it.layout.leading).toDouble() }.toFloat()
            if (requiredHeight <= bounds.height || size <= minimumSize) break
            size = max(minimumSize, size - 0.5f)
        } while (true)

        var baseline = bounds.y.toFloat()
        return layouts.map { measured ->
            val layout = measured.layout
            baseline += layout.ascent
            val x = if (layout.isLeftToRight) bounds.x.toFloat() else bounds.maxX.toFloat() - layout.advance
            FittedPdfLine(measured.text.trimEnd(), layout, x, baseline, size, color).also {
                baseline += layout.descent + layout.leading
            }
        }
    }

    private fun drawRasterLines(
        graphics: java.awt.Graphics2D,
        bounds: Rectangle,
        lines: List<FittedPdfLine>,
        color: Color
    ) {
        val oldClip = graphics.clip
        graphics.clip(bounds)
        graphics.color = color
        lines.forEach { line -> line.layout.draw(graphics, line.x, line.baseline) }
        graphics.clip = oldClip
    }

    private fun measureWrappedLines(
        text: String,
        font: Font,
        context: java.awt.font.FontRenderContext,
        width: Float
    ): List<MeasuredPdfLine> {
        val attributed = AttributedString(text).apply { addAttribute(TextAttribute.FONT, font) }
        val iterator = attributed.iterator
        val measurer = LineBreakMeasurer(iterator, context)
        return buildList {
            while (measurer.position < iterator.endIndex) {
                val start = measurer.position
                val layout = measurer.nextLayout(width.coerceAtLeast(1f))
                add(MeasuredPdfLine(text.substring(start, measurer.position), layout))
            }
        }
    }

    private fun drawVectorLines(
        content: PDPageContentStream,
        lines: List<PdfVectorLine>,
        fonts: PdfVectorFonts,
        pageHeightPoints: Float
    ) {
        val scale = PDF_RENDER_DPI / PDF_POINTS_PER_INCH
        lines.forEach { line ->
            if (line.text.isBlank()) return@forEach
            content.beginText()
            content.setFont(fonts.forStyle(line.fontFamily, line.fontStyle), line.sizePixels / scale)
            content.setNonStrokingColor(line.color)
            content.newLineAtOffset(line.xPixels / scale, pageHeightPoints - line.baselinePixels / scale)
            content.showText(line.text)
            content.endText()
        }
    }

    private fun sampleBackground(image: BufferedImage, bounds: Rectangle): Color {
        val samples = buildList {
            val left = (bounds.x - 2).coerceAtLeast(0)
            val right = (bounds.x + bounds.width + 1).coerceAtMost(image.width - 1)
            val top = (bounds.y - 2).coerceAtLeast(0)
            val bottom = (bounds.y + bounds.height + 1).coerceAtMost(image.height - 1)
            for (x in left..right step max(1, (right - left) / 12)) {
                add(Color(image.getRGB(x, top)))
                add(Color(image.getRGB(x, bottom)))
            }
            for (y in top..bottom step max(1, (bottom - top) / 6)) {
                add(Color(image.getRGB(left, y)))
                add(Color(image.getRGB(right, y)))
            }
        }
        fun median(channel: (Color) -> Int): Int = samples.map(channel).sorted()[samples.size / 2]
        return Color(median(Color::getRed), median(Color::getGreen), median(Color::getBlue))
    }

    private fun contrastingTextColor(background: Color): Color {
        val luminance = 0.2126 * background.red + 0.7152 * background.green + 0.0722 * background.blue
        return if (luminance < 110) Color.WHITE else Color(28, 28, 28)
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
        val batchTranslator = translator as? BatchTranslator
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

    private data class MeasuredPdfLine(val text: String, val layout: TextLayout)

    private data class FittedPdfLine(
        val text: String,
        val layout: TextLayout,
        val x: Float,
        val baseline: Float,
        val size: Float,
        val color: Color
    ) {
        fun toVectorLine(fontFamily: String, fontStyle: Int, color: Color) = PdfVectorLine(
            text = text,
            xPixels = x,
            baselinePixels = baseline,
            sizePixels = size,
            fontFamily = fontFamily,
            fontStyle = fontStyle,
            color = color
        )
    }

    private data class PdfVectorLine(
        val text: String,
        val xPixels: Float,
        val baselinePixels: Float,
        val sizePixels: Float,
        val fontFamily: String,
        val fontStyle: Int,
        val color: Color
    )

    private class PdfVectorFonts(document: PDDocument) {
        private data class FontPair(val pdf: PDFont, val awt: Font)

        private val serifRegular = load(document, "fonts/serif/ibm/IBMPlexSerif-Regular.ttf")
        private val serifBold = load(document, "fonts/serif/ibm/IBMPlexSerif-SemiBold.ttf")
        private val serifItalic = load(document, "fonts/serif/ibm/IBMPlexSerif-Italic.ttf")
        private val serifBoldItalic = load(document, "fonts/serif/ibm/IBMPlexSerif-SemiBoldItalic.ttf")
        private val sansRegular = load(document, "fonts/sans/ibm/IBMPlexSans-Regular.ttf")
        private val sansBold = load(document, "fonts/sans/ibm/IBMPlexSans-SemiBold.ttf")
        private val sansItalic = load(document, "fonts/sans/ibm/IBMPlexSans-Italic.ttf")
        private val sansBoldItalic = load(document, "fonts/sans/ibm/IBMPlexSans-SemiBoldItalic.ttf")
        private val arabicRegular = loadAwt("fonts/arabic/noto/NotoNaskhArabic-Regular.ttf")
        private val arabicBold = loadAwt("fonts/arabic/noto/NotoNaskhArabic-Bold.ttf")

        fun forStyle(family: String, style: Int): PDFont {
            return pairForStyle(family, style).pdf
        }

        fun awtForText(family: String, style: Int, text: String): Font {
            val preferred = pairForStyle(family, style).awt
            if (preferred.canDisplayUpTo(text) < 0) return preferred
            val arabic = if (style and Font.BOLD != 0) arabicBold else arabicRegular
            return if (arabic.canDisplayUpTo(text) < 0) arabic else Font(Font.SANS_SERIF, style, 1)
        }

        private fun pairForStyle(family: String, style: Int): FontPair {
            val bold = style and Font.BOLD != 0
            val italic = style and Font.ITALIC != 0
            return when (family) {
                Font.SANS_SERIF -> select(bold, italic, sansRegular, sansBold, sansItalic, sansBoldItalic)
                else -> select(bold, italic, serifRegular, serifBold, serifItalic, serifBoldItalic)
            }
        }

        private fun select(
            bold: Boolean,
            italic: Boolean,
            regular: FontPair,
            boldFont: FontPair,
            italicFont: FontPair,
            boldItalicFont: FontPair
        ): FontPair = when {
            bold && italic -> boldItalicFont
            bold -> boldFont
            italic -> italicFont
            else -> regular
        }

        private fun load(document: PDDocument, path: String): FontPair {
            val bytes = loadFontBytes(path)
            val pdf = PDType0Font.load(document, ByteArrayInputStream(bytes), true)
            val awt = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(bytes))
            return FontPair(pdf, awt)
        }

        private fun loadAwt(path: String): Font =
            Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(loadFontBytes(path)))

        private fun loadFontBytes(path: String): ByteArray =
            checkNotNull(DocumentTranslationUseCase::class.java.classLoader.getResourceAsStream(path)) {
                "Bundled PDF font not found: $path"
            }.use { it.readBytes() }
    }

    private data class PdfTextBlock(
        val sourceText: String,
        val x: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val fontFamily: String,
        val fontStyle: Int,
        val fontSizePoints: Float,
        var translatedText: String = sourceText
    ) {
        fun pixelBounds(scale: Float, image: BufferedImage, contentRight: Float): Rectangle {
            val left = (x * scale).roundToInt().coerceIn(0, image.width - 1)
            val y = (top * scale).roundToInt().coerceIn(0, image.height - 1)
            val naturalRight = x + width
            val availableRight = if (contentRight > naturalRight) contentRight else naturalRight
            val right = (availableRight * scale).roundToInt().coerceIn(left + 1, image.width)
            val descentAllowance = fontSizePoints * scale * 0.25f
            val bottom = ((top + height) * scale + descentAllowance).roundToInt().coerceIn(y + 1, image.height)
            return Rectangle(left, y, right - left, bottom - y)
        }
    }

    private class TextFreePdfRenderer(document: PDDocument) : PDFRenderer(document) {
        override fun createPageDrawer(parameters: PageDrawerParameters): PageDrawer =
            object : PageDrawer(parameters) {
                override fun showFontGlyph(
                    textRenderingMatrix: Matrix,
                    font: PDFont,
                    code: Int,
                    displacement: Vector
                ) = Unit
            }
    }

    private class PositionedTextStripper(private val pageNumber: Int) : PDFTextStripper() {
        private val blocks = mutableListOf<PdfTextBlock>()

        init {
            sortByPosition = true
            startPage = pageNumber
            endPage = pageNumber
        }

        fun extract(document: PDDocument): List<PdfTextBlock> {
            getText(document)
            return blocks.toList()
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            val cleanText = text.replace(Regex("\\s+"), " ").trim()
            val positions = textPositions.filter { it.unicode.isNotBlank() }
            if (cleanText.isEmpty() || positions.isEmpty()) return

            val left = positions.minOf(TextPosition::getXDirAdj)
            val right = positions.maxOf { it.xDirAdj + it.widthDirAdj }
            val top = positions.minOf { it.yDirAdj - it.heightDir }
            val bottom = positions.maxOf(TextPosition::getYDirAdj)
            val primaryFont = positions.groupingBy { it.font.name }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
            val normalizedFontName = primaryFont.lowercase()
            val isItalic = "italic" in normalizedFontName || "oblique" in normalizedFontName ||
                normalizedFontName.endsWith("-it") || normalizedFontName.endsWith("_it")
            val isBold = "bold" in normalizedFontName || "black" in normalizedFontName ||
                "semibold" in normalizedFontName || "demibold" in normalizedFontName
            val fontFamily = when {
                "courier" in normalizedFontName || "mono" in normalizedFontName -> Font.MONOSPACED
                "helvetica" in normalizedFontName || "arial" in normalizedFontName ||
                    "sans" in normalizedFontName || "myriad" in normalizedFontName ||
                    "roboto" in normalizedFontName || "calibri" in normalizedFontName -> Font.SANS_SERIF
                else -> Font.SERIF
            }
            val fontStyle = when {
                isBold && isItalic -> Font.BOLD or Font.ITALIC
                isBold -> Font.BOLD
                isItalic -> Font.ITALIC
                else -> Font.PLAIN
            }
            blocks += PdfTextBlock(
                sourceText = cleanText,
                x = left,
                top = top,
                width = (right - left).coerceAtLeast(1f),
                height = (bottom - top).coerceAtLeast(1f),
                fontFamily = fontFamily,
                fontStyle = fontStyle,
                fontSizePoints = positions.map(TextPosition::getFontSizeInPt).average().toFloat().coerceAtLeast(1f)
            )
        }
    }

    private companion object {
        const val PDF_RENDER_DPI = 216f
        const val PDF_POINTS_PER_INCH = 72f
        val PDF_LINE_END_HYPHENS = setOf('-', '\u2010', '\u2011', '\u00AD')
        val PDF_BULLETS = setOf('\u2022', '\u25E6', '\u25AA', '\u2013')
        val srtTimestamp = Regex("\\d{2}:\\d{2}:\\d{2},\\d{3}\\s+-->\\s+\\d{2}:\\d{2}:\\d{2},\\d{3}")
        val vttTimestamp = Regex("(?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3}\\s+-->\\s+(?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3}")
        val markup = Regex("<[^>]+>|\\{\\\\[^}]+}")
    }
}

private val PdfTranslationMode.displayName: String
    get() = when (this) {
        PdfTranslationMode.TEXT_ONLY -> "Text-only"
        PdfTranslationMode.LAYOUT_AWARE -> "Best-effort appearance"
    }
