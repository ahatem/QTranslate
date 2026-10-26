package com.github.ahatem.qtranslate.core.main.mvi

import kotlin.test.Test
import kotlin.test.assertEquals

class OcrLineBreaksPreprocessingTest {

    @Test
    fun `prepareInputText joins multiline text into single continuous line when enabled`() {
        val multilineOcrText = """
            This is a multi-line paragraph
            scanned from a document
            or a book page.
        """.trimIndent()

        val cleaned = prepareInputText(multilineOcrText, removeLineBreaksEnabled = true)
        assertEquals(
            "This is a multi-line paragraph scanned from a document or a book page.",
            cleaned
        )
    }

    @Test
    fun `prepareInputText normalizes CRLF and removes extraneous whitespace`() {
        val crlfText = "First line.\r\nSecond line.\r\nThird line."
        val cleaned = prepareInputText(crlfText, removeLineBreaksEnabled = true)
        assertEquals("First line. Second line. Third line.", cleaned)
    }

    @Test
    fun `prepareInputText preserves original multiline structure when disabled`() {
        val multilineOcrText = "Line one\nLine two\nLine three"
        val untouched = prepareInputText(multilineOcrText, removeLineBreaksEnabled = false)
        assertEquals(multilineOcrText, untouched)
    }
}
