package com.github.ahatem.qtranslate.plugins.systemocr.backend

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
    fun `Arabic with embedded English keeps the Latin run reading left to right`() {
        // Logical: افتح Windows Terminal الآن
        val line = visualLine("الآن", "Windows", "Terminal", "افتح")

        assertEquals("افتح Windows Terminal الآن", OcrLineOrder.lineText(line, arabic))
    }

    @Test
    fun `Arabic with numbers leaves the digits untouched`() {
        // Logical: الإصدار 123 متاح الآن
        val line = visualLine("الآن", "متاح", "123", "الإصدار")

        val logical = OcrLineOrder.lineText(line, arabic)

        assertEquals("الإصدار 123 متاح الآن", logical)
        assertNotEquals("الإصدار 321 متاح الآن", logical)
    }

    @Test
    fun `a number after a Latin word stays inside the Latin run`() {
        // Logical: افتح Windows 11 الآن
        val line = visualLine("الآن", "Windows", "11", "افتح")

        val logical = OcrLineOrder.lineText(line, arabic)

        assertEquals("افتح Windows 11 الآن", logical)
        assertNotEquals("افتح 11 Windows الآن", logical)
    }

    @Test
    fun `another Latin and number run stays internally ordered`() {
        // Logical: شغل Python 3 الآن
        val line = visualLine("الآن", "Python", "3", "شغل")

        val logical = OcrLineOrder.lineText(line, arabic)

        assertEquals("شغل Python 3 الآن", logical)
        assertNotEquals("شغل 3 Python الآن", logical)
    }

    @Test
    fun `an acronym and a number stay together in Latin order`() {
        // Logical: ظهر HTTP 404 الآن
        val line = visualLine("الآن", "HTTP", "404", "ظهر")

        val logical = OcrLineOrder.lineText(line, arabic)

        assertEquals("ظهر HTTP 404 الآن", logical)
        assertNotEquals("ظهر 404 HTTP الآن", logical)
    }

    @Test
    fun `a chain of Latin words and numbers keeps its order`() {
        // Logical: افتح Windows 11 Pro الآن
        val line = visualLine("الآن", "Windows", "11", "Pro", "افتح")

        assertEquals("افتح Windows 11 Pro الآن", OcrLineOrder.lineText(line, arabic))
    }

    @Test
    fun `Arabic-Indic numerals stay in the Arabic flow`() {
        // Logical: الإصدار ١٢٣ متاح الآن
        val line = visualLine("الآن", "متاح", "١٢٣", "الإصدار")

        assertEquals("الإصدار ١٢٣ متاح الآن", OcrLineOrder.lineText(line, arabic))
    }

    @Test
    fun `Arabic with a Latin shortcut keeps the shortcut intact`() {
        // Logical: اضغط Ctrl + C للنسخ
        val line = visualLine("للنسخ", "Ctrl", "+", "C", "اضغط")

        val logical = OcrLineOrder.lineText(line, arabic)

        assertEquals("اضغط Ctrl + C للنسخ", logical)
        assertNotEquals("اضغط C + Ctrl للنسخ", logical)
    }

    @Test
    fun `a mixed sentence with an ambiguous start is resolved by the requested language`() {
        // Logical: افتح Windows Terminal واضغط Ctrl + C, with the shortcut leftmost on the page.
        val line = visualLine("Ctrl", "+", "C", "واضغط", "Windows", "Terminal", "افتح")

        assertEquals(
            "افتح Windows Terminal واضغط Ctrl + C",
            OcrLineOrder.lineText(line, arabic),
        )
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
