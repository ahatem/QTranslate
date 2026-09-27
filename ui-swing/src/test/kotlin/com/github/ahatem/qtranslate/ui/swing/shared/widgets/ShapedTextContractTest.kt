package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.ShapedCarets
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ShapedText.onEdt
import java.awt.Font
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.text.AttributedString
import java.text.BreakIterator
import java.util.Locale
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.TransferHandler
import javax.swing.text.AbstractDocument
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The behavioural contract for shaped text: where lines break, which font draws a script, and where
 * the caret and the mouse land.
 *
 * The sentence is the one that reproduced a word ("بدلاً") cut across two lines and carets landing a
 * few characters off. The bundled Rubik claims its Arabic but lays it out with broken hit-testing,
 * so the product pair exercises the configured fallback. Fonts are named here as reproductions; the
 * production code decides from each layout's own carets.
 */
class ShapedTextContractTest {

    private val sentence =
        "تحذير صادق أريد وضع علامة عليه مباشرة: بالنسبة للخط الذي يكون شكله العربي مكسورًا بشكل حقيقي " +
            "(تم تأكيده لروبيك المجمع الخاص بك في بعض حالات JDK وليس كلها)، يتم إصلاح الغلاف دائمًا بشكل " +
            "صحيح ولا يفسد النص أبدًا، ولكن النقر بالماوس/وضع علامة الإقحام مباشرة في المكان المعيب يمكن " +
            "أن يؤدي أحيانًا إلى ظهور بضعة أحرف بدلاً من دقة البكسل. إنها دقيقة تمامًا لنوتو النسخ العربية " +
            "وللجري حسن التصرف بشكل عام. يتم توثيق ذلك في ملف الاختبار ونص العلاقات العامة بدلاً من إخفائه."

    private val word = "بدلاً"

    private val wordStarts: List<Int> = Regex(word).findAll(sentence).map { it.range.first }.toList()

    private class FontPair(val primary: String, val fallback: String) {
        override fun toString() = "$primary -> $fallback"
    }

    /** The default configuration: an unsound primary with a sound fallback. */
    private val product = FontPair(RubikSansFont.FAMILY, NotoNaskhArabicFont.FAMILY)

    /** A sound font with nothing to fall back to. */
    private val sound = FontPair(NotoNaskhArabicFont.FAMILY, NotoNaskhArabicFont.FAMILY)

    /** An unsound font with nothing to fall back to: the measured last resort. */
    private val lastResort = FontPair(RubikSansFont.FAMILY, RubikSansFont.FAMILY)

    /** The paths above, plus the platform's logical font as the primary. */
    private val pairs = listOf(product, sound, FontPair(Font.DIALOG, NotoNaskhArabicFont.FAMILY), lastResort)

    private fun pane(text: String, width: Int, fonts: FontPair) = ShapedText.pane(text, width, fonts.primary, fonts.fallback)

    private fun rowStarts(pane: AdvancedTextPane): List<Int> = onEdt { ShapedText.rows(pane).map { it.startOffset } }

    private fun caret(pane: AdvancedTextPane, offset: Int): Rectangle2D = onEdt { pane.modelToView2D(offset)!! }

    private fun hit(pane: AdvancedTextPane, x: Double, y: Double): Int = onEdt { pane.viewToModel2D(Point2D.Double(x, y)) }

    private fun familyAt(pane: AdvancedTextPane, offset: Int): String =
        onEdt { StyleConstants.getFontFamily(pane.styledDocument.getCharacterElement(offset).attributes) }

    /**
     * Every row starts at a legal line-break opportunity, unless the row before it has none at all:
     * a token wider than the line, which may only be cut at a grapheme-cluster boundary.
     */
    private fun assertRowsRespectLineBreaks(pane: AdvancedTextPane, label: String) {
        val text = onEdt { pane.styledDocument.getText(0, pane.styledDocument.length) }
        val lines = BreakIterator.getLineInstance(Locale.ROOT).apply { setText(text) }
        val clusters = BreakIterator.getCharacterInstance().apply { setText(text) }
        val starts = rowStarts(pane)
        for (i in 1 until starts.size) {
            val start = starts[i]
            if (lines.isBoundary(start)) continue
            assertFalse(
                (starts[i - 1] + 1 until start).any { lines.isBoundary(it) },
                "$label: row $i starts inside a word at $start although the row before it could have ended at a legal break"
            )
            assertTrue(clusters.isBoundary(start), "$label: an overlong token was cut inside a grapheme cluster at $start")
        }
    }

    private fun assertWordNeverSplit(pane: AdvancedTextPane, label: String) {
        val starts = rowStarts(pane)
        for (w in wordStarts) {
            val inside = starts.filter { it > w && it < w + word.length }
            assertTrue(inside.isEmpty(), "$label: \"$word\" at $w was split across rows at $inside")
        }
    }

    // -------------------------------------------------------------------------------------------
    // Line breaking and document text
    // -------------------------------------------------------------------------------------------

    /** Every width, because each one moves the row ends somewhere else in the sentence. */
    @Test
    fun `the product fonts never split the word at any width`() {
        val p = pane(sentence, width = 600, fonts = product)
        for (width in 240..900 step 4) {
            ShapedText.resize(p, width)
            assertWordNeverSplit(p, "width $width")
            assertRowsRespectLineBreaks(p, "width $width")
        }
    }

    @Test
    fun `every font path keeps the word whole and the document exactly the source text`() {
        for (fonts in pairs) {
            val p = pane(sentence, width = 600, fonts = fonts)
            assertEquals(sentence, onEdt { p.styledDocument.getText(0, p.styledDocument.length) }, "$fonts: no inserted newline, mark or reordering")
            for (width in listOf(240) + (560..640 step 8) + 900) {
                ShapedText.resize(p, width)
                assertWordNeverSplit(p, "$fonts at $width")
                assertRowsRespectLineBreaks(p, "$fonts at $width")
            }
        }
    }

    @Test
    fun `ordinary text of every kind breaks only at legal opportunities`() {
        val samples = listOf(
            "pure english" to List(30) { "translation" }.joinToString(" "),
            "pure arabic" to List(30) { "الحركة الدودية" }.joinToString(" "),
            "arabic with combining marks" to List(20) { "مُسْتَشْفَيَاتُهُمْ الْعَرَبِيَّةُ" }.joinToString(" "),
            "mixed arabic and latin" to List(8) { "طلب الدمج PR رقم مفتوحًا وقابلاً للدمج pull request" }.joinToString(" "),
            "arabic with a hash token" to List(8) { "لا يوجد سبب لحماية طلب الدمج رقم #298 من التغيير" }.joinToString(" "),
            "arabic with slash separated numbers" to List(8) { "الرقم الحالي هو 852 إضافة / 319 عملية حذف فقط" }.joinToString(" "),
            "punctuation" to List(8) { "مفتوحًا/قابلاً للدمج، لذلك! أليس كذلك؟ (نعم): بالتأكيد." }.joinToString(" "),
        )
        for (fonts in pairs) {
            for ((label, text) in samples) {
                val p = pane(text, width = 300, fonts = fonts)
                for (width in listOf(180, 300, 420)) {
                    ShapedText.resize(p, width)
                    assertRowsRespectLineBreaks(p, "$fonts, $label at $width")
                }
            }
        }
    }

    @Test
    fun `only a token wider than the line is cut inside, and only at cluster boundaries`() {
        val samples = listOf(
            "long english token" to "a " + "Supercalifragilisticexpialidocious".repeat(4) + " end",
            "long arabic token with marks" to "كلمة " + "مُسْتَشْفَيَاتُهُمْ".repeat(8) + " نهاية",
        )
        for (fonts in pairs) {
            for ((label, text) in samples) {
                val p = pane(text, width = 200, fonts = fonts)
                val starts = rowStarts(p)
                assertTrue(starts.size > 2, "$fonts, $label: the overlong token must be cut to fit")
                assertEquals(text.indexOf(' ') + 1, starts[1], "$fonts, $label: the token moves to its own row before it is cut")
                assertRowsRespectLineBreaks(p, "$fonts, $label")
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Font choice
    // -------------------------------------------------------------------------------------------

    private fun layoutIsSound(text: String, family: String): Boolean {
        val attributed = AttributedString(text).apply { addAttribute(TextAttribute.FONT, Font(family, Font.PLAIN, 15)) }
        val layout = TextLayout(attributed.iterator, FontRenderContext(null, true, true))
        return ShapedCarets.layoutIsSound(text, layout, 0, text.length)
    }

    @Test
    fun `arabic moves to the fallback only when the primary shapes it unsoundly, and latin never moves`() {
        val p = pane(sentence, width = 600, fonts = product)
        val expectedArabic = if (layoutIsSound(sentence, RubikSansFont.FAMILY)) RubikSansFont.FAMILY else NotoNaskhArabicFont.FAMILY
        assertEquals(RubikSansFont.FAMILY, familyAt(p, sentence.indexOf("JDK")), "Latin stays in the primary")
        val arabicFamilies = sentence.indices
            .filter { Character.UnicodeScript.of(sentence.codePointAt(it)) == Character.UnicodeScript.ARABIC }
            .map { familyAt(p, it) }
            .toSet()
        assertEquals(setOf(expectedArabic), arabicFamilies, "all of the paragraph's Arabic shares one face")
    }

    @Test
    fun `a primary font that shapes soundly is left alone even when the fallback also would`() {
        val p = pane(sentence, width = 600, fonts = FontPair(NotoNaskhArabicFont.FAMILY, RubikSansFont.FAMILY))
        if (layoutIsSound(sentence, NotoNaskhArabicFont.FAMILY)) {
            assertEquals(NotoNaskhArabicFont.FAMILY, familyAt(p, sentence.indexOf(word)))
        }
    }

    // -------------------------------------------------------------------------------------------
    // Caret, mouse and keyboard
    // -------------------------------------------------------------------------------------------

    /** Before ب, between ب/د, between د/ل, between ل/ا (inside the lam-alef ligature), after the word. */
    private fun boundariesOf(start: Int) = listOf(start, start + 1, start + 2, start + 3, start + word.length)

    @Test
    fun `every cluster boundary of the word has its own caret, in reading order, and round trips exactly`() {
        val p = pane(sentence, width = 600, fonts = product)
        for (start in wordStarts) {
            val carets = boundariesOf(start).map { caret(p, it) }
            assertEquals(1, carets.map { it.y }.toSet().size, "the word sits on one row")
            // Right to left, so each later position is further left, including the one inside the
            // ligature, which the layout on its own collapses onto the ligature's edge.
            for (i in 1 until carets.size) {
                assertTrue(carets[i].x < carets[i - 1].x - 0.5, "word at $start: ${carets.map { it.x }}")
            }
            for ((offset, rect) in boundariesOf(start).zip(carets)) {
                assertEquals(offset, hit(p, rect.x, rect.centerY), "offset $offset")
            }
            // Between the alef and its tanween is inside a cluster; it resolves to the cluster's end.
            val inside = caret(p, start + 4)
            assertEquals(start + word.length, hit(p, inside.x, inside.centerY), "inside the last cluster")
        }
    }

    /**
     * Every width, because each one re-cuts rows somewhere else, and a re-cut fragment is where a
     * shaped run can lose its shaped caret geometry.
     */
    @Test
    fun `every position round trips exactly and in reading order, at every width`() {
        for (fonts in listOf(product, sound)) {
            val p = pane(sentence, width = 600, fonts = fonts)
            for (width in 240..900 step 10) {
                ShapedText.resize(p, width)
                onEdt { assertCaretsExact(p, "$fonts at $width") }
            }
        }
    }

    /** Call on the EDT. */
    private fun assertCaretsExact(p: AdvancedTextPane, label: String) {
        val doc = p.styledDocument as AbstractDocument
        val bidi = doc.bidiRootElement
        val clusters = BreakIterator.getCharacterInstance().apply { setText(sentence) }
        for (offset in 0..sentence.length) {
            val rect = p.modelToView2D(offset)
            val back = p.viewToModel2D(Point2D.Double(rect.centerX, rect.centerY))
            assertTrue(p.caretIsEquivalent(offset, back), "$label: $offset came back as $back")

            // Within one right-to-left run on one row, a later cluster boundary is never further right.
            val next = clusters.following(offset.coerceAtMost(sentence.length - 1))
            val run = bidi.getElement(bidi.getElementIndex(offset))
            if (offset < sentence.length && next < run.endOffset && StyleConstants.getBidiLevel(run.attributes) % 2 == 1) {
                val nextRect = p.modelToView2D(next)
                if (nextRect.y == rect.y) assertTrue(nextRect.x <= rect.x, "$label: $next is drawn right of $offset in right-to-left text")
            }
        }
    }

    @Test
    fun `the last resort keeps every click in the word it was aimed at`() {
        val p = pane(sentence, width = 600, fonts = lastResort)
        assertTrue(onEdt { ShapedText.measuresRuns(p) }, "Rubik alone is laid out by the last resort")
        for (width in listOf(330, 600, 900)) {
            ShapedText.resize(p, width)
            for (offset in 0..sentence.length) {
                val rect = caret(p, offset)
                val back = hit(p, rect.x, rect.centerY)
                if (onEdt { p.caretIsEquivalent(offset, back) }) continue
                // Measured inside a word, so only there may it miss.
                val between = sentence.substring(minOf(offset, back), maxOf(offset, back))
                assertTrue(' ' !in between && caret(p, back).y == rect.y, "width $width: $offset came back as $back, in another word")
            }
        }
    }

    @Test
    fun `an embedded run's start and the position after it share a caret, and nothing else in the paragraph does`() {
        val text = "إضافة / 319 عملية حذف فقط ولا يزال مفتوحًا Pull Request مقبول"
        val p = pane(text, width = 900, fonts = product)
        // The left-to-right "319" inside right-to-left text: its start and the space after it.
        val digits = text.indexOf("319")
        val afterDigits = digits + 3
        assertTrue(onEdt { p.caretIsEquivalent(afterDigits, digits) })
        assertTrue(
            kotlin.math.abs(caret(p, afterDigits).x - caret(p, digits).x) < kotlin.math.abs(caret(p, digits + 1).x - caret(p, digits).x),
            "the two positions are drawn at one edge of \"319\", closer than one of its digits is wide"
        )
        val back = hit(p, caret(p, afterDigits).x, caret(p, afterDigits).centerY)
        assertTrue(back == digits || back == afterDigits, "a click there lands on one of the two, got $back")

        // An ordinary right-to-left run between "319" and "Pull Request": its ends are far apart.
        val runStart = afterDigits
        val runEnd = text.indexOf("Pull")
        val interior = text.indexOf("فقط")
        assertTrue(caret(p, interior).x in caret(p, runEnd).x..caret(p, runStart).x, "the run's ends are visibly apart")
        assertFalse(onEdt { p.caretIsEquivalent(runStart, runEnd) }, "the ends of an ordinary run are not one caret")
        assertFalse(onEdt { p.caretIsEquivalent(runStart, interior) }, "nor is a position inside it")
        assertFalse(onEdt { p.caretIsEquivalent(interior, runEnd) })

        // An embedded run's start is not equivalent to a position inside that run either.
        val latin = text.indexOf("Pull")
        assertFalse(onEdt { p.caretIsEquivalent(latin, latin + 5) })
        assertFalse(onEdt { p.caretIsEquivalent(digits, digits + 1) })
    }

    @Test
    fun `a click over the word, or a real mouse press, lands on the nearest cluster boundary`() {
        val p = pane(sentence, width = 600, fonts = product)
        for (start in wordStarts) {
            val offsets = boundariesOf(start)
            val carets = offsets.map { caret(p, it) }
            for (i in 0 until offsets.size - 1) {
                val a = carets[i].x
                val b = carets[i + 1].x
                val y = carets[i].centerY
                assertEquals(offsets[i], hit(p, a + (b - a) * 0.3, y), "a click just past ${offsets[i]}")
                assertEquals(offsets[i + 1], hit(p, a + (b - a) * 0.7, y), "a click just before ${offsets[i + 1]}")
            }
        }
        for ((offset, rect) in boundariesOf(wordStarts.first()).map { it to caret(p, it) }) {
            onEdt {
                p.caretPosition = 0
                val press = MouseEvent(
                    p, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
                    rect.x.toInt(), rect.centerY.toInt(), 1, false, MouseEvent.BUTTON1
                )
                (p.caret as java.awt.event.MouseListener).mousePressed(press)
            }
            assertEquals(offset, onEdt { p.caretPosition }, "a press on the caret of $offset")
        }
    }

    private fun press(pane: AdvancedTextPane, key: Int, modifiers: Int = 0) = onEdt {
        val binding = pane.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, modifiers))
        pane.actionMap.get(binding)!!.actionPerformed(ActionEvent(pane, ActionEvent.ACTION_PERFORMED, binding.toString()))
    }

    @Test
    fun `the arrows step through every cluster boundary, and home and end reach the row's ends`() {
        val p = pane(sentence, width = 600, fonts = product)
        val start = wordStarts.first()
        val offsets = boundariesOf(start)
        onEdt { p.caretPosition = start }
        // Right to left: Left moves on through the text, Right moves back.
        assertEquals(offsets.drop(1), (1 until offsets.size).map { press(p, KeyEvent.VK_LEFT); onEdt { p.caretPosition } })
        assertEquals(offsets.dropLast(1).reversed(), (1 until offsets.size).map { press(p, KeyEvent.VK_RIGHT); onEdt { p.caretPosition } })

        val (rowStart, rowEnd) = onEdt { ShapedText.rows(p).first { start in it.startOffset until it.endOffset }.let { it.startOffset to it.endOffset } }
        onEdt { p.caretPosition = start + 2 }
        press(p, KeyEvent.VK_HOME)
        assertEquals(rowStart, onEdt { p.caretPosition })
        onEdt { p.caretPosition = start + 2 }
        press(p, KeyEvent.VK_END)
        assertTrue(onEdt { p.caretPosition } in rowEnd - 1..rowEnd, "End lands at the row's end ($rowStart-$rowEnd)")
    }

    @Test
    fun `selecting the word by mouse or keyboard and copying it gives exactly the word`() {
        val p = pane(sentence, width = 600, fonts = product)
        for (start in wordStarts) {
            val from = caret(p, start)
            val to = caret(p, start + word.length)
            val a = hit(p, from.x, from.centerY)
            val b = hit(p, to.x, to.centerY)
            onEdt { p.select(minOf(a, b), maxOf(a, b)) }
            assertEquals(word, onEdt { p.selectedText })

            val clipboard = Clipboard("test")
            onEdt { p.transferHandler.exportToClipboard(p, clipboard, TransferHandler.COPY) }
            assertEquals(word, clipboard.getData(DataFlavor.stringFlavor))

            onEdt { p.caretPosition = start }
            repeat(4) { press(p, KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK) }
            assertEquals(word, onEdt { p.selectedText })
        }
    }
}
