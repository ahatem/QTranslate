package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import org.junit.jupiter.api.Test
import java.nio.file.Files
import javax.swing.SwingUtilities

class Item5BaselineBenchmarkTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun strings(): ConversationStrings {
        val tempDir = Files.createTempDirectory("benchmark-test").toFile()
        val manager = LocalizationManager(tempDir, LanguageTomlParser(), silentLogger)
        return ConversationStrings(manager)
    }

    @Test
    fun `measure baseline metrics`() {
        SwingUtilities.invokeAndWait {
            val strings = strings()
            val view = TranscriptListView(strings)
            var now = 10_000L
            view.clock = { now }
            view.setSize(600, 400)

            for (count in listOf(200, 1000, 2000)) {
                val segments = (1..count).map { i ->
                    TranscriptSegment(
                        id = "seg-$i",
                        source = if (i % 2 == 1) AudioSource.MICROPHONE else AudioSource.SYSTEM,
                        startMillis = i * 1000L,
                        endMillis = i * 1000L + 800L,
                        settledAtMillis = i * 1000L + 800L,
                        text = "Message $i in session for baseline measurement"
                    )
                }
                val session = ConversationSession(id = "s-$count", title = "Bench $count", createdAt = 0L, segments = segments)
                view.render(session)

                // Warm up
                for (w in 1..5) {
                    val incoming = TranscriptSegment("live-draft", AudioSource.MICROPHONE, 999999L, 999999L, "Live draft $w", draft = true)
                    view.render(session.copy(segments = segments + incoming))
                }

                // Time live update
                view.stamp.resetCounters()
                val liveDraft = TranscriptSegment("live-draft", AudioSource.MICROPHONE, 999999L, 999999L, "Live draft measured", draft = true)
                val t0 = System.nanoTime()
                view.render(session.copy(segments = segments + liveDraft))
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

                println("BASELINE [count=$count]: time=${"%.3f".format(elapsedMs)} ms, measuredCards=${view.stamp.measureCount}")
            }

            // Measure memory after 2000 lines (simulating ~60 min call at ~33 lines/min)
            System.gc()
            System.gc()
            Thread.sleep(200)
            val runtime = Runtime.getRuntime()
            val usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
            println("BASELINE [60-min replay / 2000 lines]: heap=${"%.2f".format(usedHeapMb)} MB")
        }
    }
}
