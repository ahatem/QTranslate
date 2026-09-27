package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.FontUtils
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import java.awt.Container
import java.awt.Font
import java.awt.geom.Point2D
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A translated Arabic paragraph could wrap mid-word well inside the room it was given: a run
 * such as "مفتوحًا/قابلاً" (a tanween mark, then a lam-alef ligature) broke onto its own short
 * line even with hundreds of pixels to spare.
 *
 * The cause was not the layout math. `Font.canDisplay` reports true for Rubik on Arabic code
 * points too, because the bundled file does carry glyphs for them, so the font-fallback pass never
 * routed that text to [NotoNaskhArabicFont] — the face installed and tested for Arabic. Rubik's own
 * Arabic shaping tables are incomplete, and past that ligature the JDK's own text-layout hit-testing
 * for Rubik (and for the platform fonts Java substitutes for it) goes wrong, which is what made
 * Swing's line breaker give up early. Routing every Arabic-script character to the dedicated face
 * whenever it can draw it, regardless of what the primary claims, is what fixes both the wrap and
 * the hit-testing it depends on.
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

    private val primary = Font(RubikSansFont.FAMILY, Font.PLAIN, 15)
    private val fallback = Font(NotoNaskhArabicFont.FAMILY, Font.PLAIN, 15)

    private fun pane(text: String, width: Int, height: Int = 400): AdvancedTextPane {
        // The bundled faces, registered with the graphics environment exactly as AppUiSetup does,
        // so canDisplay reflects the real files rather than whatever the platform composites for an
        // unrecognised family name.
        RubikSansFont.installLazy()
        FontUtils.getCompositeFont(RubikSansFont.FAMILY, Font.PLAIN, 15)
        NotoNaskhArabicFont.install()

        val pane = onEdt { AdvancedTextPane(onTextChanged = {}, onTranslateRequest = {}, onListenRequest = {}) }
        val scroll = onEdt { JScrollPane(pane) }
        onEdt {
            pane.render(text, emptyList(), isEditable = false)
            pane.updateFontsAndRescanDocument(primary, fallback)
            scroll.setSize(width, height)
            layoutTree(scroll)
        }
        waitUntilSettled(pane)
        onEdt { layoutTree(scroll) }
        onEdt { layoutTree(scroll) }
        return pane
    }

    /** Waits for the batched fallback pass to finish, rather than sleeping a fixed guess. */
    private fun waitUntilSettled(pane: AdvancedTextPane, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastRuns = -1
        while (System.currentTimeMillis() < deadline) {
            onEdt { }
            Thread.sleep(20)
            val runs = onEdt { runCount(pane) }
            if (runs == lastRuns && runs > 0) return
            lastRuns = runs
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

    private fun fontFamiliesInRange(pane: AdvancedTextPane, start: Int, end: Int): Set<String?> {
        val doc = pane.styledDocument
        val families = mutableSetOf<String?>()
        var i = start
        while (i < end) {
            val el = doc.getCharacterElement(i)
            families += StyleConstants.getFontFamily(el.attributes)
            i = el.endOffset
        }
        return families
    }

    private val realisticSentence =
        "الرقم 298 الحالي هو 852 إضافة / 319 عملية حذف فقط ولا يزال مفتوحًا/قابلاً للدمج، لذلك لا يوجد " +
            "سبب لحمايته من أن يصبح تخطيطًا حقيقيًا للعلاقات العامة فقط للحفاظ على حدود المرحلة الأصلية جميلة."

    @Test
    fun `the exact failing sequence no longer wraps mid word with room to spare`() {
        // ~640 logical px is comfortably wider than the roughly 110px fragment that used to be
        // forced onto its own line.
        val p = pane(realisticSentence, width = 640)
        val viewport = 640 - 20 // margin
        assertTrue(widestRow(p) > viewport * 0.6, "a row should use most of the width it was given")
        assertTrue(rows(p).size <= 3, "the sentence should wrap close to where the width runs out, not every few words")
    }

    @Test
    fun `pure english wraps to the viewport`() {
        val text = List(40) { "translation" }.joinToString(" ")
        val p = pane(text, width = 400)
        assertTrue(widestRow(p) <= 400f, "no row should exceed the width it was given")
        assertTrue(rows(p).size > 1, "this much text should wrap at all")
    }

    @Test
    fun `pure arabic wraps to the viewport`() {
        val text = List(40) { "الحركة الدودية" }.joinToString(" ")
        val p = pane(text, width = 400)
        assertTrue(widestRow(p) <= 400f, "no row should exceed the width it was given")
        assertTrue(rows(p).size > 1, "this much text should wrap at all")
    }

    @Test
    fun `arabic with a plain integer wraps normally`() {
        val p = pane("الرقم الحالي هو 298 فقط ولا يزال مفتوحًا للنقاش حول القيمة الحقيقية", width = 300)
        assertTrue(widestRow(p) <= 300f)
    }

    @Test
    fun `arabic with a hash token wraps normally`() {
        val p = pane("لا يوجد سبب لحماية طلب الدمج رقم #298 من أن يصبح تخطيطًا حقيقيًا للمرحلة النهائية", width = 300)
        assertTrue(widestRow(p) <= 300f)
    }

    @Test
    fun `arabic with an english acronym wraps normally`() {
        val p = pane("طلب الدمج PR رقم 298 لا يزال مفتوحًا وقابلاً للدمج حسب الحاجة الفعلية", width = 300)
        assertTrue(widestRow(p) <= 300f)
    }

    @Test
    fun `arabic with slash separated numbers wraps normally`() {
        val p = pane("الرقم الحالي هو 852 إضافة / 319 عملية حذف فقط ولا يزال مفتوحًا وقابلاً للدمج", width = 300)
        assertTrue(widestRow(p) <= 300f)
    }

    @Test
    fun `arabic with punctuation wraps normally`() {
        val p = pane("مفتوحًا/قابلاً للدمج، لذلك لا يوجد سبب لحمايته من أن يصبح تخطيطًا حقيقيًا! أليس كذلك؟", width = 300)
        assertTrue(widestRow(p) <= 300f)
    }

    @Test
    fun `resizing wider reflows into the newly available width`() {
        val p = pane(realisticSentence, width = 300)
        val narrowRows = rows(p).size
        val scroll = onEdt { p.parent.parent as JScrollPane }
        onEdt { scroll.setSize(1400, 400); layoutTree(scroll) }
        onEdt { layoutTree(scroll) }
        assertEquals(1, rows(p).size, "given a viewport wider than the text, it should not wrap at all")
        assertTrue(rows(p).size < narrowRows)
    }

    @Test
    fun `document text is exactly the source string, no inserted newline or directional mark`() {
        val p = pane(realisticSentence, width = 400)
        val doc = p.styledDocument
        val stored = doc.getText(0, doc.length)
        assertEquals(realisticSentence, stored.trimEnd('\n'))
        assertFalse(stored.contains('\n'), "presentation must not introduce a hard line break")
        assertFalse(stored.any { it.code in 0x200E..0x200F || it.code in 0x202A..0x202E }, "no directional formatting character was injected")
    }

    @Test
    fun `paragraph base direction follows the arabic majority`() {
        val p = pane(realisticSentence, width = 400)
        assertFalse(p.componentOrientation.isLeftToRight, "a majority-Arabic paragraph should read right to left")
    }

    @Test
    fun `a pure english paragraph keeps left to right direction`() {
        val p = pane("This pull request is still open and mergeable.", width = 400)
        assertTrue(p.componentOrientation.isLeftToRight)
    }

    @Test
    fun `arabic script characters are drawn with the dedicated fallback face`() {
        val p = pane(realisticSentence, width = 640)
        val doc = p.styledDocument
        var i = 0
        var checkedAtLeastOneArabicRun = false
        while (i < doc.length) {
            val el = doc.getCharacterElement(i)
            val text = doc.getText(el.startOffset, el.endOffset - el.startOffset)
            if (text.any { Character.UnicodeBlock.of(it) === Character.UnicodeBlock.ARABIC }) {
                assertEquals(
                    NotoNaskhArabicFont.FAMILY, StyleConstants.getFontFamily(el.attributes),
                    "an Arabic run must render in the face installed and tested for Arabic, not merely one that happens to contain some of its glyphs"
                )
                checkedAtLeastOneArabicRun = true
            }
            i = el.endOffset
        }
        assertTrue(checkedAtLeastOneArabicRun, "the sample text must actually contain Arabic runs for this to prove anything")
    }

    @Test
    fun `latin digits and punctuation next to arabic keep the primary face`() {
        val p = pane(realisticSentence, width = 640)
        val families = fontFamiliesInRange(p, 6, 9) // "298"
        assertEquals(setOf(RubikSansFont.FAMILY), families, "digits are drawn by the primary interface font")
    }

    @Test
    fun `caret geometry and mouse hit testing agree past the affected ligature`() {
        val p = pane(realisticSentence, width = 640)
        val doc = p.styledDocument
        var mismatches = 0
        for (offset in 0..doc.length) {
            val rect = p.modelToView2D(offset) ?: continue
            val roundTripped = p.viewToModel2D(Point2D.Double(rect.centerX, rect.centerY))
            if (kotlin.math.abs(roundTripped - offset) > 1) mismatches++
        }
        // A handful of exact boundary points between two runs are inherently ambiguous under
        // centre-point hit testing even for plain text; a corrupted layout put this in the dozens.
        assertTrue(mismatches <= 6, "caret/hit-test round trip should be reliable, found $mismatches mismatches of ${doc.length}")
    }

    @Test
    fun `selection across the affected ligature keeps the exact source characters`() {
        val p = pane(realisticSentence, width = 640)
        val start = realisticSentence.indexOf("مفتوحًا")
        val end = start + "مفتوحًا/قابلاً".length
        onEdt { p.select(start, end) }
        assertEquals("مفتوحًا/قابلاً", onEdt { p.selectedText })
    }
}
