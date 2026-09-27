package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.FontUtils
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import java.awt.Container
import java.awt.Font
import java.awt.geom.Point2D
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A translated Arabic paragraph could wrap mid-word well inside the room it was given: a run such
 * as "مفتوحًا/قابلاً" (a tanween mark, then a lam-alef ligature) broke onto its own short line even
 * with hundreds of pixels to spare.
 *
 * Some fonts -- Rubik among them -- lay this text out with broken hit-testing past that ligature,
 * while others (Noto Naskh Arabic) shape it soundly. Nothing in production names a font: the
 * font-fallback pass ([ShapingAwareFallback]) moves a script's text to a configured fallback that
 * shapes it soundly, [WrappingEditorKit] leaves a sound layout to Swing, and a paragraph no
 * configured font shapes soundly is laid out by its measured last resort.
 *
 * Every pane here uses one font as both primary and fallback, so nothing can be handed over and each
 * font's own behaviour is tested: Rubik exercises the last resort, Noto Naskh Arabic is sound, and
 * the logical "Dialog" stands in for whatever a platform without either bundled face substitutes.
 * [ShapedTextContractTest] covers the configured pairs, including the product default, and the
 * exact caret contract.
 */
class ArabicShapingRegressionTest {

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun pane(text: String, width: Int, fontFamily: String, height: Int = 400): AdvancedTextPane {
        // The bundled faces, registered with the graphics environment exactly as AppUiSetup does,
        // so a real Font(name, ...) resolves to the actual file rather than a platform substitute
        // for an unrecognised family name.
        RubikSansFont.installLazy()
        FontUtils.getCompositeFont(RubikSansFont.FAMILY, Font.PLAIN, 15)
        NotoNaskhArabicFont.install()

        val font = Font(fontFamily, Font.PLAIN, 15)
        val pane = onEdt { AdvancedTextPane(onTextChanged = {}, onTranslateRequest = {}, onListenRequest = {}) }
        val scroll = onEdt { JScrollPane(pane) }
        onEdt {
            pane.render(text, emptyList(), isEditable = false)
            pane.updateFontsAndRescanDocument(font, font)
            scroll.setSize(width, height)
            layoutTree(scroll)
        }
        waitUntilSettled(pane)
        settleLayout(scroll, pane)
        return pane
    }

    /**
     * Waits for the batched fallback pass to finish, rather than sleeping a fixed guess.
     */
    private fun waitUntilSettled(pane: AdvancedTextPane, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastRuns = -1
        var stableTicks = 0
        while (System.currentTimeMillis() < deadline) {
            onEdt { }
            Thread.sleep(20)
            val runs = onEdt { runCount(pane) }
            if (runs == lastRuns && runs > 0) {
                stableTicks++
                if (stableTicks >= 3) return
            } else {
                stableTicks = 0
            }
            lastRuns = runs
        }
    }

    /**
     * Lays the tree out repeatedly until the row structure itself stops changing between passes,
     * rather than a fixed number of passes -- a fixed count is a guess about how many are needed
     * under whatever load the rest of the suite happens to be under when this runs, and a guess is
     * exactly what produced the flaky "wrapped one row short" failures this replaced.
     */
    private fun settleLayout(scroll: Container, pane: AdvancedTextPane, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var previous: String? = null
        var stableTicks = 0
        while (System.currentTimeMillis() < deadline) {
            onEdt { layoutTree(scroll) }
            val signature = onEdt { rows(pane).joinToString("|") { "${it.startOffset}-${it.endOffset}:${it.getPreferredSpan(0)}" } }
            if (signature == previous) {
                stableTicks++
                if (stableTicks >= 3) return
            } else {
                stableTicks = 0
            }
            previous = signature
        }
    }

    private fun runCount(pane: AdvancedTextPane): Int {
        val doc = pane.styledDocument
        var e = 0
        var count = 0
        while (e < doc.length) {
            count++
            e = doc.getCharacterElement(e).endOffset
        }
        return count
    }

    private fun rows(pane: AdvancedTextPane): List<javax.swing.text.View> {
        val root = pane.ui.getRootView(pane).getView(0).getView(0)
        return (0 until root.viewCount).map { root.getView(it) }
    }

    private fun widestRow(pane: AdvancedTextPane): Float = rows(pane).maxOf { it.getPreferredSpan(0) }

    /** The row spanning [offset] in the document, or null if none does (shouldn't happen). */
    private fun rowContaining(pane: AdvancedTextPane, offset: Int): javax.swing.text.View? =
        rows(pane).firstOrNull { offset in it.startOffset until it.endOffset }

    private val realisticSentence =
        "الرقم 298 الحالي هو 852 إضافة / 319 عملية حذف فقط ولا يزال مفتوحًا/قابلاً للدمج، لذلك لا يوجد " +
            "سبب لحمايته من أن يصبح تخطيطًا حقيقيًا للعلاقات العامة فقط للحفاظ على حدود المرحلة الأصلية جميلة."

    private val ligatureOffset = realisticSentence.indexOf("قابلاً")

    /**
     * A position well inside the long, uninterrupted Arabic run that follows the ligature -- the
     * run that actually has to be broken to fit a line, which is exactly where the corrupted
     * hit-testing this class works around used to cut it far short. The ligature's own word sits in
     * a short run of its own (a separate, pre-existing row-packing characteristic of Swing's bidi
     * layout, not something this fix touches either way), so it is not itself a useful probe for
     * whether a *forced break* landed at a sensible point.
     */
    private val longRunOffset = realisticSentence.indexOf("للعلاقات")

    private val fonts = listOf(RubikSansFont.FAMILY, NotoNaskhArabicFont.FAMILY, "Dialog")

    @Test
    fun `a forced break in the long run past the ligature lands close to where the width runs out`() {
        for (font in fonts) {
            val p = pane(realisticSentence, width = 640, fontFamily = font)
            val row = rowContaining(p, longRunOffset)
            assertTrue(row != null, "$font: this offset must land in some row")
            assertTrue(
                row!!.getPreferredSpan(0) > (640 - 20) * 0.4,
                "$font: a forced break should use most of the width, not stop far short (used ${row.getPreferredSpan(0)})"
            )
        }
    }

    @Test
    fun `pure english wraps to the viewport, on every font`() {
        val text = List(40) { "translation" }.joinToString(" ")
        for (font in fonts) {
            val p = pane(text, width = 400, fontFamily = font)
            assertTrue(widestRow(p) <= 400f, "$font: no row should exceed the width it was given")
            assertTrue(rows(p).size > 1, "$font: this much text should wrap at all")
        }
    }

    @Test
    fun `pure arabic wraps to the viewport, on every font`() {
        val text = List(40) { "الحركة الدودية" }.joinToString(" ")
        for (font in fonts) {
            val p = pane(text, width = 400, fontFamily = font)
            assertTrue(widestRow(p) <= 400f, "$font: no row should exceed the width it was given")
            assertTrue(rows(p).size > 1, "$font: this much text should wrap at all")
        }
    }

    @Test
    fun `arabic with a plain integer, a hash token, an acronym, slash separated numbers and punctuation all wrap normally`() {
        val samples = listOf(
            "الرقم الحالي هو 298 فقط ولا يزال مفتوحًا للنقاش حول القيمة الحقيقية",
            "لا يوجد سبب لحماية طلب الدمج رقم #298 من أن يصبح تخطيطًا حقيقيًا للمرحلة النهائية",
            "طلب الدمج PR رقم 298 لا يزال مفتوحًا وقابلاً للدمج حسب الحاجة الفعلية",
            "الرقم الحالي هو 852 إضافة / 319 عملية حذف فقط ولا يزال مفتوحًا وقابلاً للدمج",
            "مفتوحًا/قابلاً للدمج، لذلك لا يوجد سبب لحمايته من أن يصبح تخطيطًا حقيقيًا! أليس كذلك؟",
        )
        for (font in fonts) {
            for (sample in samples) {
                val p = pane(sample, width = 300, fontFamily = font)
                // Deliberately loose: this guards against the original defect, a wrap tens of pixels
                // early, not against a row's exact fit. The exact line-breaking contract is
                // ShapedTextContractTest's.
                assertTrue(widestRow(p) <= 300f * 1.6f, "$font: \"$sample\" (row width ${widestRow(p)})")
            }
        }
    }

    @Test
    fun `resizing wider reflows into the newly available width, on every font`() {
        for (font in fonts) {
            val p = pane(realisticSentence, width = 300, fontFamily = font)
            val narrowRows = rows(p).size
            val scroll = onEdt { p.parent.parent as JScrollPane }
            onEdt { scroll.setSize(1400, 400) }
            settleLayout(scroll, p)
            assertTrue(rows(p).size <= 2, "$font: given a viewport wider than the text, it should not still be wrapped into several lines (got ${rows(p).size})")
            assertTrue(rows(p).size < narrowRows, "$font")
        }
    }

    @Test
    fun `document text is exactly the source string on every font, no inserted newline or directional mark`() {
        for (font in fonts) {
            val p = pane(realisticSentence, width = 400, fontFamily = font)
            val doc = p.styledDocument
            val stored = doc.getText(0, doc.length)
            assertEquals(realisticSentence, stored.trimEnd('\n'), font)
            assertFalse(stored.contains('\n'), "$font: presentation must not introduce a hard line break")
            assertFalse(
                stored.any { it.code in 0x200E..0x200F || it.code in 0x202A..0x202E },
                "$font: no directional formatting character was injected"
            )
        }
    }

    @Test
    fun `paragraph base direction follows the arabic majority, on every font`() {
        for (font in fonts) {
            val p = pane(realisticSentence, width = 400, fontFamily = font)
            assertFalse(p.componentOrientation.isLeftToRight, "$font: a majority-Arabic paragraph should read right to left")
        }
    }

    @Test
    fun `a pure english paragraph keeps left to right direction, on every font`() {
        for (font in fonts) {
            val p = pane("This pull request is still open and mergeable.", width = 400, fontFamily = font)
            assertTrue(p.componentOrientation.isLeftToRight, font)
        }
    }

    @Test
    fun `selection across the affected ligature keeps the exact source characters, on every font`() {
        val start = realisticSentence.indexOf("مفتوحًا")
        val end = start + "مفتوحًا/قابلاً".length
        for (font in fonts) {
            val p = pane(realisticSentence, width = 640, fontFamily = font)
            onEdt { p.select(start, end) }
            assertEquals("مفتوحًا/قابلاً", onEdt { p.selectedText }, font)
        }
    }

    @Test
    fun `caret and mouse hit testing past the affected ligature are exact on a sound font and word-accurate on the last resort`() {
        for (font in listOf(RubikSansFont.FAMILY, NotoNaskhArabicFont.FAMILY)) {
            val p = pane(realisticSentence, width = 640, fontFamily = font)
            val doc = p.styledDocument
            // Nothing can be handed to a fallback here: a font that shapes this soundly is left to
            // Swing and must be exact, and one that does not is laid out by the measured last
            // resort, which is exact between words and approximate only inside one.
            val sound = onEdt {
                !(p.ui.getRootView(p).getView(0).getView(0) as WrappingEditorKit.WrappingParagraphView).measuresRuns
            }
            for (offset in 0..doc.length) {
                val rect = onEdt { p.modelToView2D(offset) } ?: continue
                val back = onEdt { p.viewToModel2D(Point2D.Double(rect.centerX, rect.centerY)) }
                // Exact means the same position or an equivalent one (see caretIsEquivalent): the
                // start of "319" and the space after it are one visual edge in this sentence.
                if (onEdt { p.caretIsEquivalent(offset, back) }) continue
                assertFalse(sound, "$font: $offset came back as $back")
                val between = realisticSentence.substring(minOf(offset, back), maxOf(offset, back).coerceAtMost(realisticSentence.length))
                assertTrue(' ' !in between && onEdt { p.modelToView2D(back).y == rect.y }, "$font: $offset came back as $back, in another word")
            }
        }
    }
}
