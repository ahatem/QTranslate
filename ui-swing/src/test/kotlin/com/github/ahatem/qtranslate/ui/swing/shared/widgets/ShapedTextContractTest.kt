package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.FontUtils
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.ShapedCarets
import java.awt.Container
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
import java.awt.image.BufferedImage
import java.text.AttributedString
import java.text.BreakIterator
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.text.StyleConstants
import javax.swing.text.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The maintainer-reported regressions in shaped text, against the exact sentence that reproduced
 * them: a word ("بدلاً") cut across two lines with no newline in it, and caret and mouse placement
 * a few characters away from where it belongs.
 *
 * Both came from the same place. The bundled Rubik face claims every Arabic letter in that sentence,
 * but the JDK's layout of it loses track of its characters past a lam-alef ligature: every later
 * caret reports the same point. The wrap then gave up far short of the room it had, and the view
 * layer's fallback for that case both cut words at whatever cluster fit and placed the caret by
 * approximation. Now the font-fallback pass notices the unsound layout and hands that paragraph's
 * Arabic to the configured fallback when the fallback shapes it soundly, so normal Swing and
 * `TextLayout` own wrapping, the caret and the mouse; and every run, sound or not, only ends a line
 * where a line may legally end.
 *
 * Fonts are named here because they are the reproduction. The production code never does: it
 * decides from the layout's own answers.
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

    /** What a new installation is configured with. */
    private val product = FontPair(RubikSansFont.FAMILY, NotoNaskhArabicFont.FAMILY)

    /**
     * The product pair, each bundled face on its own, and the platform's logical font. A pair whose
     * two fonts are the same leaves the fallback pass nothing to switch to, which is how the view
     * layer's own last-resort measurement is exercised.
     */
    private val pairs = listOf(
        product,
        FontPair(NotoNaskhArabicFont.FAMILY, NotoNaskhArabicFont.FAMILY),
        FontPair(Font.DIALOG, NotoNaskhArabicFont.FAMILY),
        FontPair(RubikSansFont.FAMILY, RubikSansFont.FAMILY),
        FontPair(Font.DIALOG, Font.DIALOG),
    )

    // -------------------------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------------------------

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun installFonts() {
        // Registered exactly as AppUiSetup does, so Font(name, ...) resolves to the bundled file.
        RubikSansFont.installLazy()
        FontUtils.getCompositeFont(RubikSansFont.FAMILY, Font.PLAIN, 15)
        NotoNaskhArabicFont.install()
    }

    /**
     * A pane whose text gets [width] pixels to wrap in.
     *
     * Widths here are the room the text has, not the size of the component around it: the scroll
     * pane's border, the scroll bar and the pane's own margin all scale with the look and feel, and
     * whatever an earlier test left installed would otherwise decide how much room was left.
     */
    private fun pane(text: String, width: Int, fonts: FontPair): AdvancedTextPane {
        installFonts()
        val primary = Font(fonts.primary, Font.PLAIN, 15)
        val fallback = Font(fonts.fallback, Font.PLAIN, 15)
        val pane = onEdt { AdvancedTextPane(onTextChanged = {}, onTranslateRequest = {}, onListenRequest = {}) }
        onEdt { JScrollPane(pane) }
        onEdt {
            pane.render(text, emptyList(), isEditable = true)
            pane.updateFontsAndRescanDocument(primary, fallback)
        }
        resize(pane, width)
        waitForFallbackPass(pane)
        settle(pane)
        paintOnce(pane)
        return pane
    }

    /**
     * Paints the pane into an image. Swing's text UI answers no keyboard navigation for a component
     * it has never painted, and a headless test never shows one.
     */
    private fun paintOnce(pane: AdvancedTextPane) = onEdt {
        val image = BufferedImage(maxOf(1, pane.width), maxOf(1, pane.height), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try { pane.paint(g) } finally { g.dispose() }
    }

    /** Gives the pane's text [width] pixels to wrap in; see [pane]. */
    private fun resize(pane: AdvancedTextPane, width: Int) {
        onEdt {
            val scroll = pane.parent.parent as JScrollPane
            val chrome = scroll.insets.left + scroll.insets.right +
                scroll.verticalScrollBar.preferredSize.width +
                pane.insets.left + pane.insets.right
            scroll.setSize(width + chrome, 600)
            layoutTree(scroll)
        }
        settle(pane)
        paintOnce(pane)
    }

    /** Waits until the batched font-fallback pass has stopped rewriting runs. */
    private fun waitForFallbackPass(pane: AdvancedTextPane, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            val runs = onEdt { runs(pane).joinToString() }
            if (runs == last && stable++ >= 5) return
            if (runs != last) stable = 0
            last = runs
        }
    }

    /** Lays the tree out until the row structure stops changing between passes. */
    private fun settle(pane: AdvancedTextPane, timeoutMs: Long = 5000) {
        val scroll = onEdt { pane.parent.parent as JScrollPane }
        val deadline = System.currentTimeMillis() + timeoutMs
        var previous: String? = null
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            onEdt { layoutTree(scroll) }
            val signature = onEdt { rows(pane).joinToString("|") { "${it.startOffset}-${it.endOffset}:${it.getPreferredSpan(0)}" } }
            if (signature == previous && stable++ >= 2) return
            if (signature != previous) stable = 0
            previous = signature
        }
    }

    private fun runs(pane: AdvancedTextPane): List<String> {
        val doc = pane.styledDocument
        val found = ArrayList<String>()
        var offset = 0
        while (offset < doc.length) {
            val element = doc.getCharacterElement(offset)
            found += "${element.startOffset}-${element.endOffset}:${StyleConstants.getFontFamily(element.attributes)}"
            offset = element.endOffset
        }
        return found
    }

    private fun familyAt(pane: AdvancedTextPane, offset: Int): String =
        onEdt { StyleConstants.getFontFamily(pane.styledDocument.getCharacterElement(offset).attributes) }

    private fun rows(pane: AdvancedTextPane): List<View> {
        val paragraph = pane.ui.getRootView(pane).getView(0).getView(0)
        return (0 until paragraph.viewCount).map { paragraph.getView(it) }.sortedBy { it.startOffset }
    }

    private fun rowStarts(pane: AdvancedTextPane): List<Int> = onEdt { rows(pane).map { it.startOffset } }

    private fun caret(pane: AdvancedTextPane, offset: Int): Rectangle2D = onEdt { pane.modelToView2D(offset)!! }

    private fun hit(pane: AdvancedTextPane, x: Double, y: Double): Int =
        onEdt { pane.viewToModel2D(Point2D.Double(x, y)) }

    private fun isCluster(text: String, offset: Int): Boolean =
        BreakIterator.getCharacterInstance().apply { setText(text) }.isBoundary(offset)

    /**
     * The line-breaking contract, checked row by row: every row starts at a legal line-break
     * opportunity, unless the row before it contains none at all -- a single token wider than the
     * line -- in which case it starts at a grapheme-cluster boundary.
     */
    private fun assertRowsRespectLineBreaks(pane: AdvancedTextPane, label: String) {
        val text = onEdt { pane.styledDocument.getText(0, pane.styledDocument.length) }
        val lines = BreakIterator.getLineInstance(Locale.ROOT).apply { setText(text) }
        val starts = rowStarts(pane)
        for (i in 1 until starts.size) {
            val start = starts[i]
            if (lines.isBoundary(start)) continue
            val previous = starts[i - 1]
            val previousRowHadABreak = (previous + 1 until start).any { lines.isBoundary(it) }
            assertFalse(
                previousRowHadABreak,
                "$label: row $i starts inside a word at $start (\"${text.substring(maxOf(0, start - 6), minOf(text.length, start + 6))}\") " +
                    "although the row before it could have ended at a legal break"
            )
            assertTrue(isCluster(text, start), "$label: an overlong token was cut inside a grapheme cluster at $start")
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
    // Word breaking
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the maintainer's sentence never splits the word at any width, with the product fonts`() {
        val p = pane(sentence, width = 600, fonts = product)
        for (width in 240..900 step 4) {
            resize(p, width)
            assertWordNeverSplit(p, "width $width")
            assertRowsRespectLineBreaks(p, "width $width")
        }
    }

    @Test
    fun `the maintainer's sentence never splits the word on any font pair, including the last-resort path`() {
        for (fonts in pairs) {
            val p = pane(sentence, width = 600, fonts = fonts)
            for (width in (560..640 step 8) + (240..900 step 110)) {
                resize(p, width)
                assertWordNeverSplit(p, "$fonts at $width")
                assertRowsRespectLineBreaks(p, "$fonts at $width")
            }
        }
    }

    @Test
    fun `ordinary text of every kind breaks only at legal opportunities, on every font pair`() {
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
                for (width in listOf(180, 260, 300, 420)) {
                    resize(p, width)
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
                // The short first word ends the first row on its own: the token moves to a new row
                // whole before anything is cut.
                assertEquals(text.indexOf(' ') + 1, starts[1], "$fonts, $label: the token should start its own row")
                assertRowsRespectLineBreaks(p, "$fonts, $label")
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Font choice
    // -------------------------------------------------------------------------------------------

    private fun layoutIsSound(text: String, family: String): Boolean {
        installFonts()
        val attributed = AttributedString(text).apply { addAttribute(TextAttribute.FONT, Font(family, Font.PLAIN, 15)) }
        val layout = TextLayout(attributed.iterator, FontRenderContext(null, true, true))
        return ShapedCarets.layoutIsSound(text, layout, 0, text.length)
    }

    @Test
    fun `arabic moves to the fallback only when the primary shapes it unsoundly, and latin never moves`() {
        val p = pane(sentence, width = 600, fonts = product)
        val arabicOffset = sentence.indexOf(word)
        val latinOffset = sentence.indexOf("JDK")
        val expectedArabic = if (layoutIsSound(sentence, RubikSansFont.FAMILY)) RubikSansFont.FAMILY else NotoNaskhArabicFont.FAMILY
        assertEquals(expectedArabic, familyAt(p, arabicOffset))
        assertEquals(RubikSansFont.FAMILY, familyAt(p, latinOffset), "Latin text is never moved because Arabic had a problem")
        // Every Arabic word in the paragraph shares one face.
        val arabicFamilies = sentence.indices
            .filter { Character.UnicodeScript.of(sentence.codePointAt(it)) == Character.UnicodeScript.ARABIC }
            .map { familyAt(p, it) }
            .toSet()
        assertEquals(setOf(expectedArabic), arabicFamilies)
    }

    @Test
    fun `a primary font that shapes soundly is left alone even when the fallback also would`() {
        val p = pane(sentence, width = 600, fonts = FontPair(NotoNaskhArabicFont.FAMILY, RubikSansFont.FAMILY))
        if (layoutIsSound(sentence, NotoNaskhArabicFont.FAMILY)) {
            assertEquals(NotoNaskhArabicFont.FAMILY, familyAt(p, sentence.indexOf(word)))
        }
    }

    @Test
    fun `the document is exactly the source text on every font pair`() {
        for (fonts in pairs) {
            val p = pane(sentence, width = 600, fonts = fonts)
            val stored = onEdt { p.styledDocument.getText(0, p.styledDocument.length) }
            assertEquals(sentence, stored, "$fonts: no inserted newline, mark, joiner or reordering")
        }
    }

    // -------------------------------------------------------------------------------------------
    // Caret and mouse, with the product fonts
    // -------------------------------------------------------------------------------------------

    private val caretWidths = listOf(560, 600, 640)

    /** Before ب, between ب/د, between د/ل, between ل/ا (inside the lam-alef ligature), after the word. */
    private fun boundariesOf(start: Int) = listOf(start, start + 1, start + 2, start + 3, start + word.length)

    @Test
    fun `every cluster boundary of the word round trips exactly through modelToView and viewToModel`() {
        for (width in caretWidths) {
            val p = pane(sentence, width = width, fonts = product)
            for (start in wordStarts) {
                for (offset in boundariesOf(start)) {
                    val rect = caret(p, offset)
                    assertEquals(offset, hit(p, rect.x, rect.centerY), "width $width: offset $offset (word at $start)")
                }
                // Between the alef and its tanween is not a cluster boundary: it resolves to the end of
                // that cluster, which is where its caret is drawn.
                val insideCluster = start + 4
                val rect = caret(p, insideCluster)
                assertEquals(start + word.length, hit(p, rect.x, rect.centerY), "width $width: inside the last cluster")
            }
        }
    }

    @Test
    fun `each cluster boundary of the word has a caret of its own, in reading order`() {
        val p = pane(sentence, width = 600, fonts = product)
        for (start in wordStarts) {
            val xs = boundariesOf(start).map { caret(p, it).x }
            val rows = boundariesOf(start).map { caret(p, it).y }.toSet()
            assertEquals(1, rows.size, "the word sits on one row")
            // Right to left: every later position is strictly further left, including the one inside
            // the lam-alef ligature, which the layout on its own collapses onto the ligature's edge.
            for (i in 1 until xs.size) {
                assertTrue(xs[i] < xs[i - 1] - 0.5, "word at $start: carets must move left in reading order, got $xs")
            }
        }
    }

    @Test
    fun `a click anywhere over the word lands on the nearest cluster boundary`() {
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
    }

    @Test
    fun `a real mouse press puts the caret on the clicked cluster boundary`() {
        val p = pane(sentence, width = 600, fonts = product)
        val start = wordStarts.first()
        for (offset in boundariesOf(start)) {
            val rect = caret(p, offset)
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

    @Test
    fun `every position in the sentence round trips to itself or to a position drawn at the same place`() {
        for (width in caretWidths) {
            val p = pane(sentence, width = width, fonts = product)
            for (offset in 0..sentence.length) {
                val rect = caret(p, offset)
                val back = hit(p, rect.x, rect.centerY)
                if (back == offset) continue
                val backRect = caret(p, back)
                // Two positions can share one visual location: inside a grapheme cluster, and at a
                // boundary between text of opposite directions. Anything else is a real miss.
                assertTrue(
                    kotlin.math.abs(backRect.x - rect.x) < 1.0 && backRect.y == rect.y,
                    "width $width: $offset came back as $back (drawn at ${rect.x} vs ${backRect.x})"
                )
            }
        }
    }

    private fun press(pane: AdvancedTextPane, key: Int, modifiers: Int = 0) = onEdt {
        val binding = pane.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, modifiers))
        val action = pane.actionMap.get(binding)!!
        action.actionPerformed(ActionEvent(pane, ActionEvent.ACTION_PERFORMED, binding.toString()))
    }

    @Test
    fun `left and right arrows step through every cluster boundary of the word`() {
        val p = pane(sentence, width = 600, fonts = product)
        val start = wordStarts.first()
        val offsets = boundariesOf(start)
        onEdt { p.caretPosition = start }
        // Right to left: Left moves on through the text, Right moves back.
        val forward = (1 until offsets.size).map { press(p, KeyEvent.VK_LEFT); onEdt { p.caretPosition } }
        assertEquals(offsets.drop(1), forward)
        val backward = (1 until offsets.size).map { press(p, KeyEvent.VK_RIGHT); onEdt { p.caretPosition } }
        assertEquals(offsets.dropLast(1).reversed(), backward)
    }

    @Test
    fun `selecting across the word and copying it gives exactly the word`() {
        val p = pane(sentence, width = 600, fonts = product)
        for (start in wordStarts) {
            // By the mouse: from the caret before the word to the caret after it.
            val from = caret(p, start)
            val to = caret(p, start + word.length)
            val a = hit(p, from.x, from.centerY)
            val b = hit(p, to.x, to.centerY)
            onEdt { p.select(minOf(a, b), maxOf(a, b)) }
            assertEquals(word, onEdt { p.selectedText })

            val clipboard = Clipboard("test")
            onEdt { p.transferHandler.exportToClipboard(p, clipboard, TransferHandler.COPY) }
            assertEquals(word, clipboard.getData(DataFlavor.stringFlavor))

            // By the keyboard: Shift+Left across all four clusters.
            onEdt { p.caretPosition = start }
            repeat(4) { press(p, KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK) }
            assertEquals(word, onEdt { p.selectedText })
        }
    }

    @Test
    fun `home and end on the right to left row that holds the word go to that row's ends`() {
        val p = pane(sentence, width = 600, fonts = product)
        val start = wordStarts.first()
        val row = onEdt { rows(p).first { start in it.startOffset until it.endOffset } }
        val rowStart = onEdt { row.startOffset }
        val rowEnd = onEdt { row.endOffset }

        onEdt { p.caretPosition = start + 2 }
        press(p, KeyEvent.VK_HOME)
        assertEquals(rowStart, onEdt { p.caretPosition })

        onEdt { p.caretPosition = start + 2 }
        press(p, KeyEvent.VK_END)
        val end = onEdt { p.caretPosition }
        assertTrue(end in rowEnd - 1..rowEnd, "End should land at the row's end ($rowStart-$rowEnd), got $end")
    }
}
