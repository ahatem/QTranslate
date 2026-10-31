package com.github.ahatem.qtranslate.core.document.docx

import com.github.ahatem.qtranslate.core.document.*
import com.github.ahatem.qtranslate.core.document.common.translateIndexed
import com.github.ahatem.qtranslate.api.translator.Translator
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.File

internal object DocxDocument {
    suspend fun translateDocx(
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

}
