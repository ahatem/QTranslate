package com.github.ahatem.qtranslate.plugins.mymemory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Utf8SegmenterTest {
    @Test
    fun `keeps every segment within the UTF-8 byte limit without losing text`() {
        val text = "Arabic: " + "مرحبا ".repeat(120) + "\n\n" + "English text"

        val segments = Utf8Segmenter.split(text, 500)

        assertEquals(text, segments.joinToString(""))
        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.toByteArray(Charsets.UTF_8).size <= 500 })
    }

    @Test
    fun `separates paragraphs even when they fit in one request`() {
        val text = "First paragraph.\n\nSecond paragraph."

        assertEquals(listOf("First paragraph.\n\n", "Second paragraph."), Utf8Segmenter.split(text, 500))
    }
}
