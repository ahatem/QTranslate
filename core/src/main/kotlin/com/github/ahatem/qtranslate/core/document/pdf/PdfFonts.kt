package com.github.ahatem.qtranslate.core.document.pdf

import com.github.ahatem.qtranslate.core.document.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.awt.Font
import java.io.ByteArrayInputStream

internal class PdfVectorFonts(document: PDDocument) {
    internal data class FontPair(val pdf: PDFont, val awt: Font)

    internal val serifRegular = load(document, "fonts/serif/ibm/IBMPlexSerif-Regular.ttf")
    internal val serifBold = load(document, "fonts/serif/ibm/IBMPlexSerif-SemiBold.ttf")
    internal val serifItalic = load(document, "fonts/serif/ibm/IBMPlexSerif-Italic.ttf")
    internal val serifBoldItalic = load(document, "fonts/serif/ibm/IBMPlexSerif-SemiBoldItalic.ttf")
    internal val sansRegular = load(document, "fonts/sans/ibm/IBMPlexSans-Regular.ttf")
    internal val sansBold = load(document, "fonts/sans/ibm/IBMPlexSans-SemiBold.ttf")
    internal val sansItalic = load(document, "fonts/sans/ibm/IBMPlexSans-Italic.ttf")
    internal val sansBoldItalic = load(document, "fonts/sans/ibm/IBMPlexSans-SemiBoldItalic.ttf")
    internal val arabicRegular = loadAwt("fonts/arabic/noto/NotoNaskhArabic-Regular.ttf")
    internal val arabicBold = loadAwt("fonts/arabic/noto/NotoNaskhArabic-Bold.ttf")

    fun forStyle(family: String, style: Int): PDFont {
        return pairForStyle(family, style).pdf
    }

    fun awtForText(family: String, style: Int, text: String): Font {
        val preferred = pairForStyle(family, style).awt
        if (preferred.canDisplayUpTo(text) < 0) return preferred
        val arabic = if (style and Font.BOLD != 0) arabicBold else arabicRegular
        return if (arabic.canDisplayUpTo(text) < 0) arabic else Font(Font.SANS_SERIF, style, 1)
    }

    internal fun pairForStyle(family: String, style: Int): FontPair {
        val bold = style and Font.BOLD != 0
        val italic = style and Font.ITALIC != 0
        return when (family) {
            Font.SANS_SERIF -> select(bold, italic, sansRegular, sansBold, sansItalic, sansBoldItalic)
            else -> select(bold, italic, serifRegular, serifBold, serifItalic, serifBoldItalic)
        }
    }

    internal fun select(
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

    internal fun load(document: PDDocument, path: String): FontPair {
        val bytes = loadFontBytes(path)
        val pdf = PDType0Font.load(document, ByteArrayInputStream(bytes), true)
        val awt = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(bytes))
        return FontPair(pdf, awt)
    }

    internal fun loadAwt(path: String): Font =
        Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(loadFontBytes(path)))

    internal fun loadFontBytes(path: String): ByteArray =
        checkNotNull(DocumentTranslationUseCase::class.java.classLoader.getResourceAsStream(path)) {
            "Bundled PDF font not found: $path"
        }.use { it.readBytes() }
}
