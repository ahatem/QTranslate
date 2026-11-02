package com.github.ahatem.qtranslate.core.document.pdf

import com.github.ahatem.qtranslate.core.document.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.Font
import kotlin.math.roundToInt
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.max

internal fun groupPdfBlocks(lines: List<PdfTextBlock>): List<PdfTextBlock> {
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

internal fun belongsToSameParagraph(
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

internal fun joinPdfLines(lines: List<String>): String {
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

internal fun String.startsWithAnyBullet(): Boolean = firstOrNull() in PDF_BULLETS

internal data class PdfTextBlock(
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

internal class PositionedTextStripper(internal val pageNumber: Int) : PDFTextStripper() {
    internal val blocks = mutableListOf<PdfTextBlock>()

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


internal val PDF_LINE_END_HYPHENS = setOf('-', '\u2010', '\u2011', '\u00AD')
internal val PDF_BULLETS = setOf('\u2022', '\u25E6', '\u25AA', '\u2013')
