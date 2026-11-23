package com.github.ahatem.qtranslate.plugins.systemservices.backend

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Reading-order reconstruction for Windows OCR lines.
 *
 * Each fixture is built the way `Windows.Media.Ocr` reports a line: its words in visual order, left
 * to right on the page, with x increasing that way. `line.text` is the engine's raw join of them.
 */
class OcrLineOrderTest {

    private val arabic = LanguageCode("ar")
    private val english = LanguageCode.ENGLISH

    /** A line as the engine reports it: visual order, left to right, with monotonic x. */
    private fun visualLine(vararg words: String): HelperLine = HelperLine(
        text = words.joinToString(" "),
        words = words.mapIndexed { index, word -> HelperWord(t = word, x = index * 100.0, y = 0.0) },
    )

    @Test
    fun `the reported Arabic sentence comes back in logical order`() {
        val broken = "الترمنال يعشق من الا قيمته يعرف لن رهييييب"
        val expected = "رهييييب لن يعرف قيمته الا من يعشق الترمنال"
        val line = visualLine(
            "الترمنال", "يعشق", "من", "الا", "قيمته", "يعرف", "لن", "رهييييب",
        )

        assertEquals(broken, line.text)

        val logical = OcrLineOrder.lineText(line, arabic)

        assertEquals(expected, logical)
        assertNotEquals(broken, logical)
    }

    @Test
    fun `pure English is left exactly as the engine reported it`() {
        val line = visualLine("QTranslate", "OCR", "Test")

        assertEquals("QTranslate OCR Test", OcrLineOrder.lineText(line, english))
    }

    @Test
    fun `English is not reversed even when the requested language is Arabic`() {
        val line = visualLine("QTranslate", "OCR", "Test")

        assertEquals("QTranslate OCR Test", OcrLineOrder.lineText(line, arabic))
    }

    @Test
    fun `Latin words, numbers and shortcuts keep their order inside Arabic text`() {
        // (words as the engine reports them, visually) -> logical text
        val cases = listOf(
            listOf("الآن", "Windows", "Terminal", "افتح") to "افتح Windows Terminal الآن",
            listOf("الآن", "متاح", "123", "الإصدار") to "الإصدار 123 متاح الآن",
            listOf("الآن", "Windows", "11", "افتح") to "افتح Windows 11 الآن",
            listOf("الآن", "Python", "3", "شغل") to "شغل Python 3 الآن",
            listOf("الآن", "HTTP", "404", "ظهر") to "ظهر HTTP 404 الآن",
            listOf("الآن", "Windows", "11", "Pro", "افتح") to "افتح Windows 11 Pro الآن",
            listOf("الآن", "متاح", "١٢٣", "الإصدار") to "الإصدار ١٢٣ متاح الآن",
            listOf("للنسخ", "Ctrl", "+", "C", "اضغط") to "اضغط Ctrl + C للنسخ",
            // An ambiguous start (the shortcut sits leftmost) is resolved by the requested language.
            listOf("Ctrl", "+", "C", "واضغط", "Windows", "Terminal", "افتح") to
                "افتح Windows Terminal واضغط Ctrl + C",
        )
        for ((words, expected) in cases) {
            assertEquals(expected, OcrLineOrder.lineText(visualLine(*words.toTypedArray()), arabic))
        }
    }

    @Test
    fun `geometry decides the order rather than the engine's enumeration order`() {
        // The same words, handed over shuffled: only x may decide the order.
        val line = HelperLine(
            text = "ignored",
            words = listOf(
                HelperWord("من", x = 200.0),
                HelperWord("رهييييب", x = 700.0),
                HelperWord("الترمنال", x = 0.0),
                HelperWord("لن", x = 600.0),
                HelperWord("يعرف", x = 500.0),
                HelperWord("الا", x = 300.0),
                HelperWord("يعشق", x = 100.0),
                HelperWord("قيمته", x = 400.0),
            ),
        )

        assertEquals(
            "رهييييب لن يعرف قيمته الا من يعشق الترمنال",
            OcrLineOrder.lineText(line, arabic),
        )
    }

    @Test
    fun `a line of digits only is not reordered`() {
        val line = visualLine("12345")

        assertEquals("12345", OcrLineOrder.lineText(line, arabic))
    }

    @Test
    fun `a script without spaces keeps the engine's exact text`() {
        // Chinese does not separate words with spaces, so an LTR line has to pass through verbatim.
        val line = HelperLine(
            text = "你好世界",
            words = listOf(HelperWord("你好", x = 0.0), HelperWord("世界", x = 40.0)),
        )

        assertEquals("你好世界", OcrLineOrder.lineText(line, LanguageCode.CHINESE_SIMPLIFIED))
    }

    @Test
    fun `a line with no words falls back to the engine's text`() {
        val line = HelperLine(text = "fallback", words = emptyList())

        assertEquals("fallback", OcrLineOrder.lineText(line, arabic))
    }
}
