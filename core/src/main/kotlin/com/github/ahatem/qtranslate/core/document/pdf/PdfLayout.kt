package com.github.ahatem.qtranslate.core.document.pdf

import com.github.ahatem.qtranslate.core.document.*
import org.apache.pdfbox.pdmodel.PDPageContentStream
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.text.AttributedString
import java.awt.font.TextAttribute
import kotlin.math.max

internal fun prepareTranslatedBlocks(
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

internal fun fitText(
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

internal fun drawRasterLines(
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

internal fun measureWrappedLines(
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

internal fun drawVectorLines(
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

internal fun sampleBackground(image: BufferedImage, bounds: Rectangle): Color {
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

internal fun contrastingTextColor(background: Color): Color {
    val luminance = 0.2126 * background.red + 0.7152 * background.green + 0.0722 * background.blue
    return if (luminance < 110) Color.WHITE else Color(28, 28, 28)
}

internal data class MeasuredPdfLine(val text: String, val layout: TextLayout)

internal data class FittedPdfLine(
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

internal data class PdfVectorLine(
    val text: String,
    val xPixels: Float,
    val baselinePixels: Float,
    val sizePixels: Float,
    val fontFamily: String,
    val fontStyle: Int,
    val color: Color
)


internal const val PDF_RENDER_DPI = 216f
internal const val PDF_POINTS_PER_INCH = 72f
