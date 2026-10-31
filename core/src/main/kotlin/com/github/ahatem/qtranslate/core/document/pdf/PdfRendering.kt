package com.github.ahatem.qtranslate.core.document.pdf

import com.github.ahatem.qtranslate.core.document.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.rendering.PageDrawer
import org.apache.pdfbox.rendering.PageDrawerParameters
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector

internal class TextFreePdfRenderer(document: PDDocument) : PDFRenderer(document) {
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
