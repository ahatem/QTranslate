package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ShapedText.onEdt
import javax.swing.text.View
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Translated Arabic used to wrap far short of the room it had, because some fonts hit-test badly past
 * a lam-alef ligature. Each pane uses one font as both primary and fallback so every font is tested on
 * its own. Line-break legality, font choice and the caret live in [ShapedTextContractTest].
 */
class ArabicShapingRegressionTest {

    private val realisticSentence =
        "الرقم 298 الحالي هو 852 إضافة / 319 عملية حذف فقط ولا يزال مفتوحًا/قابلاً للدمج، لذلك لا يوجد " +
            "سبب لحمايته من أن يصبح تخطيطًا حقيقيًا للعلاقات العامة فقط للحفاظ على حدود المرحلة الأصلية جميلة."

    private val fonts = listOf(RubikSansFont.FAMILY, NotoNaskhArabicFont.FAMILY, "Dialog")

    private fun pane(text: String, width: Int, font: String) = ShapedText.pane(text, width, font, font)

    private fun rowSpans(pane: AdvancedTextPane): List<Float> = onEdt { ShapedText.rows(pane).map { it.getPreferredSpan(View.X_AXIS) } }

    @Test
    fun `a row broken inside the long run past the ligature uses most of the width`() {
        // A word in the long run after "مفتوحًا/قابلاً", which is where the broken layout cut rows short.
        val offset = realisticSentence.indexOf("للعلاقات")
        for (font in fonts) {
            val p = pane(realisticSentence, width = 620, font = font)
            val used = onEdt { ShapedText.rows(p).first { offset in it.startOffset until it.endOffset }.getPreferredSpan(View.X_AXIS) }
            assertTrue(used > 620 * 0.4, "$font: the row stopped far short of the width (used $used)")
        }
    }

    @Test
    fun `english and arabic paragraphs wrap within the viewport`() {
        val long = listOf(
            List(20) { "translation" }.joinToString(" "),
            List(20) { "الحركة الدودية" }.joinToString(" "),
        )
        val mixed = listOf(
            "الرقم الحالي هو 298 فقط ولا يزال مفتوحًا للنقاش حول القيمة الحقيقية",
            "لا يوجد سبب لحماية طلب الدمج رقم #298 من أن يصبح تخطيطًا حقيقيًا للمرحلة النهائية",
            "طلب الدمج PR رقم 298 لا يزال مفتوحًا وقابلاً للدمج حسب الحاجة الفعلية",
            "مفتوحًا/قابلاً للدمج، لذلك لا يوجد سبب لحمايته من أن يصبح تخطيطًا حقيقيًا! أليس كذلك؟",
        )
        for (font in fonts) {
            for (sample in long + mixed) {
                val spans = rowSpans(pane(sample, width = 300, font = font))
                if (sample in long) assertTrue(spans.size > 1, "$font: \"$sample\" should wrap at all")
                // Loose on purpose: Swing's measurer can pack a row a little past the edge. The defect
                // this guards against wrapped far short of it.
                assertTrue(spans.max() <= 300 * 1.6f, "$font: \"$sample\" overflowed (widest row ${spans.max()})")
            }
        }
    }

    @Test
    fun `resizing wider reflows into the newly available width`() {
        for (font in fonts) {
            val p = pane(realisticSentence, width = 300, font = font)
            val narrowRows = rowSpans(p).size
            ShapedText.resize(p, 1400)
            val wideRows = rowSpans(p).size
            assertTrue(wideRows <= 2 && wideRows < narrowRows, "$font: $narrowRows rows at 300px became $wideRows at 1400px")
        }
    }
}
