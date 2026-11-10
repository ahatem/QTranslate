package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptCardStampTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun strings(): ConversationStrings {
        val tempDir = Files.createTempDirectory("transcript-card-stamp-test").toFile()
        val manager = LocalizationManager(tempDir, LanguageTomlParser(), silentLogger)
        return ConversationStrings(manager)
    }

    @Test
    fun `measured height is stable for identical input and grows only when card text grows`() = SwingUtilities.invokeAndWait {
        val stamp = TranscriptCardStamp(strings())
        val shortSeg = TranscriptSegment(
            id = "seg-1",
            source = AudioSource.MICROPHONE,
            startMillis = 1000L,
            endMillis = 2000L,
            text = "Short single-line message"
        )
        val model1 = CardModel(shortSeg, isLatest = true, chatLayout = true, rtlLayout = false)

        val layout1a = stamp.measureCard(model1, totalWidth = 500)
        val layout1b = stamp.measureCard(model1, totalWidth = 500)
        assertEquals(layout1a.cardHeight, layout1b.cardHeight, "Height must be deterministic for the same CardModel and width")

        // Measuring another long card in between must not alter shortSeg's measured height
        val longText = (1..25).joinToString(" ") { "word$it with enough length to wrap across several lines in the card" }
        val longModel = CardModel(shortSeg.copy(id = "seg-2", text = longText), isLatest = false, chatLayout = true, rtlLayout = false)
        val longLayout = stamp.measureCard(longModel, totalWidth = 500)
        assertTrue(longLayout.cardHeight > layout1a.cardHeight, "Card height must grow when its own text wraps across more lines")

        val layout1c = stamp.measureCard(model1, totalWidth = 500)
        assertEquals(layout1a.cardHeight, layout1c.cardHeight, "Measuring a long card must not contaminate subsequent measurement of short card")
    }

    @Test
    fun `stamp applies RTL right alignment for Arabic text and mirrors chat layout horizontal bounds`() = SwingUtilities.invokeAndWait {
        val stamp = TranscriptCardStamp(strings())
        val arabicSeg = TranscriptSegment(
            id = "seg-ar",
            source = AudioSource.MICROPHONE,
            startMillis = 1000L,
            endMillis = 3000L,
            text = "مرحبا بك في جلسة المحادثة المباشرة"
        )
        val englishSeg = TranscriptSegment(
            id = "seg-en",
            source = AudioSource.SYSTEM,
            startMillis = 4000L,
            endMillis = 6000L,
            text = "Welcome to the live conversation session"
        )

        val arLtrUi = stamp.measureCard(CardModel(arabicSeg, chatLayout = true, rtlLayout = false), totalWidth = 600)
        val enLtrUi = stamp.measureCard(CardModel(englishSeg, chatLayout = true, rtlLayout = false), totalWidth = 600)

        assertEquals(StyleConstants.ALIGN_RIGHT, arLtrUi.paragraphAlignment, "Arabic card paragraph must be right-aligned")
        assertEquals(StyleConstants.ALIGN_LEFT, enLtrUi.paragraphAlignment, "English card paragraph must be left-aligned")
        assertEquals(10..590, arLtrUi.horizontalBounds, "Cards span full width minus margins in LTR UI")
        assertEquals(10..590, enLtrUi.horizontalBounds, "Cards span full width minus margins in LTR UI")

        val arRtlUi = stamp.measureCard(CardModel(arabicSeg, chatLayout = true, rtlLayout = true), totalWidth = 600)
        val enRtlUi = stamp.measureCard(CardModel(englishSeg, chatLayout = true, rtlLayout = true), totalWidth = 600)
        assertEquals(10..590, enRtlUi.horizontalBounds, "Cards span full width minus margins in RTL UI")
        assertEquals(10..590, arRtlUi.horizontalBounds, "Cards span full width minus margins in RTL UI")

        // Verify paintCard stamps onto a Graphics2D without throwing
        val host = JPanel()
        val img = BufferedImage(600, 200, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        try {
            stamp.paintCard(
                g = g2,
                host = host,
                model = CardModel(arabicSeg, isLatest = true, chatLayout = true, rtlLayout = true),
                cardTopY = 12,
                totalWidth = 600,
                cardHeight = arRtlUi.cardHeight
            )
        } finally {
            g2.dispose()
        }
        assertEquals(1, stamp.paintCount)
    }
}
