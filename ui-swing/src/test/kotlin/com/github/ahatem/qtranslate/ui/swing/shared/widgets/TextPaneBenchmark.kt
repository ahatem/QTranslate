package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.FlatLightLaf
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.system.measureTimeMillis
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Measures where the time actually goes in [AdvancedTextPane], rather than guessing.
 *
 * Not an assertion of any threshold — machines differ and a timing test that fails on a busy CI
 * runner teaches people to ignore it. This prints, and the numbers are read by a human deciding
 * whether there is a problem worth fixing.
 */
@Ignore("Measurement, not a check. Run with: gradlew :ui-swing:test --tests '*TextPaneBenchmark*' -i")
class TextPaneBenchmark {

    private fun words(n: Int, sample: List<String>) =
        (0 until n).joinToString(" ") { sample[it % sample.size] }

    private val english = "the quick brown fox jumps over a lazy dog while nobody watches".split(" ")
    private val arabic = "الترجمة السريعة تساعد القارئ على فهم النص الأجنبي بسهولة ووضوح".split(" ")
    private val chinese = listOf("快速翻译帮助读者轻松理解外文文本并保持阅读的连贯性和准确性")

    private fun measure(label: String, text: String) {
        lateinit var pane: AdvancedTextPane
        SwingUtilities.invokeAndWait {
            FlatLightLaf.setup()
            pane = AdvancedTextPane({}, {}, {})
            // A realistic width; wrapping cost depends on it.
            pane.size = Dimension(700, 900)
        }

        var setText = 0L
        var layout = 0L
        var paint = 0L

        SwingUtilities.invokeAndWait {
            setText = measureTimeMillis { pane.render(text, emptyList(), isEditable = true) }
        }
        // The font-fallback pass is batched, so let it run before measuring layout.
        Thread.sleep(400)

        SwingUtilities.invokeAndWait {
            val scroll = JScrollPane(pane)
            scroll.setSize(700, 900)
            layout = measureTimeMillis {
                scroll.doLayout()
                pane.preferredSize
                pane.getPreferredSize()
            }
            val image = BufferedImage(700, 900, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            paint = measureTimeMillis { pane.paint(g) }
            g.dispose()
        }

        println("BENCH $label chars=${text.length} setText=${setText}ms layout=${layout}ms paint=${paint}ms")
    }

    @Test
    fun `time english latin text at several sizes`() {
        measure("en-1k", words(1_000, english))
        measure("en-10k", words(10_000, english))
        measure("en-50k", words(50_000, english))
    }

    @Test
    fun `time arabic and mixed direction`() {
        // Realistic first: a paragraph and a page are what people actually translate. The large
        // sizes below say whether it degrades gracefully, not whether it is usable.
        measure("ar-100w", words(100, arabic))
        measure("ar-500w", words(500, arabic))
        measure("ar-2k", words(2_000, arabic))
        measure("ar-10k", words(10_000, arabic))
        measure("mixed-10k", words(5_000, english) + "\n\n" + words(5_000, arabic))
    }

    @Test
    fun `time cjk which has no spaces to break on`() {
        measure("zh-10k", words(200, chinese))
    }

    /**
     * Isolates the cost of the direction switch from the cost of the text itself.
     *
     * Rendering Arabic into a pane already in right-to-left changes no orientation, so the
     * difference between the first and second render is what `updateOrientation` costs.
     */
    @Test
    fun `separate the direction switch from the text`() {
        lateinit var pane: AdvancedTextPane
        SwingUtilities.invokeAndWait {
            FlatLightLaf.setup()
            pane = AdvancedTextPane({}, {}, {})
            pane.size = Dimension(700, 900)
        }
        val text = words(10_000, arabic)

        var first = 0L
        var second = 0L
        SwingUtilities.invokeAndWait {
            first = measureTimeMillis { pane.render(text, emptyList(), isEditable = true) }
        }
        Thread.sleep(400)
        SwingUtilities.invokeAndWait {
            // Same direction as the pane is already in, so no orientation pass.
            second = measureTimeMillis { pane.render(text + " ب", emptyList(), isEditable = true) }
        }
        println("BENCH ar-switch first=${first}ms second-no-switch=${second}ms")
    }
}
