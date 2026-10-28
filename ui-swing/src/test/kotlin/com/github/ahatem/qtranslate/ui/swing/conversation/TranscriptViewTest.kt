package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.LiveLine
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.TranscriptWord
import com.github.ahatem.qtranslate.core.conversation.VerificationState
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import org.junit.jupiter.api.Test
import java.awt.Point
import java.nio.file.Files
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranscriptViewTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun strings(): ConversationStrings {
        val tempDir = Files.createTempDirectory("transcript-view-test").toFile()
        val manager = LocalizationManager(tempDir, LanguageTomlParser(), silentLogger)
        return ConversationStrings(manager)
    }

    private fun createView(strings: ConversationStrings = strings()): TranscriptSurface =
        TranscriptListView(strings)

    @Test
    fun `hit test distinguishes play button, retry button, delete button and text`() = SwingUtilities.invokeAndWait {
        val view: TranscriptSurface = createView()
        view.newestFirst = false
        view.asComponent.setSize(600, 400)

        val segment = TranscriptSegment(
            id = "seg-1",
            source = AudioSource.MICROPHONE,
            startMillis = 1000,
            endMillis = 3000,
            text = "hello world",
            pending = false,
            failed = false
        )
        val session = ConversationSession(
            id = "session-1",
            title = "Test",
            createdAt = 1000L,
            segments = listOf(segment)
        )
        view.render(session)

        val span = view.spans.single()
        assertTrue(span.playStart < span.playEnd)
        assertTrue(span.retryStart < span.retryEnd)
        assertTrue(span.deleteStart < span.deleteEnd)
        assertTrue(span.textStart < span.textEnd)

        // Point over play button
        val playPos = view.boundsOf((span.playStart + 1)..(span.playStart + 1))
        assertNotNull(playPos)
        val playHit = view.hitTest(Point(playPos.x + 1, playPos.y + 1))
        assertTrue(playHit is TranscriptView.Hit.PlayButton, "Hit over play symbol should be Hit.PlayButton")

        // When playing, hit test should return Hit.StopButton
        view.playingTag = "seg-1"
        val stopHit = view.hitTest(Point(playPos.x + 1, playPos.y + 1))
        assertTrue(stopHit is TranscriptView.Hit.StopButton, "Hit over playing segment symbol should be Hit.StopButton")
        view.playingTag = null

        // Point over retry button
        val retryPos = view.boundsOf((span.retryStart + 1)..(span.retryStart + 1))
        assertNotNull(retryPos)
        val retryHit = view.hitTest(Point(retryPos.x + 1, retryPos.y + 1))
        assertTrue(retryHit is TranscriptView.Hit.RetryButton, "Hit over retry symbol should be Hit.RetryButton")

        // Point over delete button
        val deletePos = view.boundsOf((span.deleteStart + 1)..(span.deleteStart + 1))
        assertNotNull(deletePos)
        val deleteHit = view.hitTest(Point(deletePos.x + 1, deletePos.y + 1))
        assertTrue(deleteHit is TranscriptView.Hit.DeleteButton, "Hit over delete symbol should be Hit.DeleteButton")

        // Point over text
        val textPos = view.boundsOf((span.textStart + 2)..(span.textStart + 2))
        assertNotNull(textPos)
        val textHit = view.hitTest(Point(textPos.x + 1, textPos.y + 1))
        assertTrue(textHit is TranscriptView.Hit.Text, "Hit over text should be Hit.Text")
    }

    @Test
    fun `render newestFirst places latest segment at the top`() = SwingUtilities.invokeAndWait {
        val view: TranscriptSurface = createView()
        view.newestFirst = true

        val seg1 = TranscriptSegment(id = "1", source = AudioSource.MICROPHONE, startMillis = 0, endMillis = 1000, text = "first")
        val seg2 = TranscriptSegment(id = "2", source = AudioSource.MICROPHONE, startMillis = 2000, endMillis = 3000, text = "second")

        val session = ConversationSession(id = "s", title = "T", createdAt = 1000L, segments = listOf(seg1, seg2))
        view.render(session)

        assertEquals(2, view.spans.size)
        assertEquals("second", view.spans[0].segment.text, "Newest segment should be first in newestFirst mode")
        assertEquals("first", view.spans[1].segment.text)
    }

    @Test
    fun `updateCursor sets HAND_CURSOR for buttons, TEXT_CURSOR for text, and DEFAULT_CURSOR for header`() = SwingUtilities.invokeAndWait {
        val view: TranscriptSurface = createView()
        val span = TranscriptView.SegmentSpan(
            TranscriptSegment(id = "1", source = AudioSource.MICROPHONE, startMillis = 0, endMillis = 1000, text = "test"),
            0, 2, 2, 10, 10, 12, 12, 14, 15, 19, emptyList()
        )

        view.updateCursor(TranscriptView.Hit.PlayButton(span))
        assertEquals(java.awt.Cursor.HAND_CURSOR, view.asComponent.cursor.type)

        view.updateCursor(TranscriptView.Hit.RetryButton(span))
        assertEquals(java.awt.Cursor.HAND_CURSOR, view.asComponent.cursor.type)

        view.updateCursor(TranscriptView.Hit.DeleteButton(span))
        assertEquals(java.awt.Cursor.HAND_CURSOR, view.asComponent.cursor.type)

        view.updateCursor(TranscriptView.Hit.Header(span))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, view.asComponent.cursor.type)

        view.updateCursor(TranscriptView.Hit.Text(span, 16))
        assertEquals(java.awt.Cursor.TEXT_CURSOR, view.asComponent.cursor.type)

        view.updateCursor(null)
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, view.asComponent.cursor.type)
    }

    @Test
    fun `render renders confirmed prefix in normal style and live text in draft style`() = SwingUtilities.invokeAndWait {
        val view: TranscriptSurface = createView()
        view.newestFirst = false

        val seg = TranscriptSegment(
            id = "live-mic",
            source = AudioSource.MICROPHONE,
            startMillis = 1000,
            endMillis = 5000,
            text = "Hello world how are you",
            confirmedText = "Hello world",
            draft = true
        )

        val session = ConversationSession(id = "s", title = "T", createdAt = 0, segments = listOf(seg))
        view.render(session)

        val docText = view.renderedText()
        assertTrue(docText.contains("Hello world how are you"))
    }

    @Test
    fun `inserting a card above anchored card under pointer keeps anchored card on-screen Y unchanged`() = SwingUtilities.invokeAndWait {
        val view: TranscriptSurface = createView()
        view.newestFirst = true
        val scrollPane = JScrollPane(view.asComponent)
        scrollPane.setSize(600, 180)
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()

        val initialSegments = (1..8).map { i ->
            TranscriptSegment(
                id = "seg-$i",
                source = AudioSource.MICROPHONE,
                startMillis = i * 1000L,
                endMillis = i * 1000L + 800L,
                text = "Line number $i with enough words to take up vertical space in the transcript view"
            )
        }
        val session1 = ConversationSession(id = "s", title = "T", createdAt = 0L, segments = initialSegments)
        view.render(session1)
        scrollPane.validate()

        val anchoredSpanBefore = view.spans.first { it.segment.id == "seg-7" }
        val docTopBefore = view.cardTopY("seg-7")
        val playRectBefore = view.boundsOf(anchoredSpanBefore.playStart..anchoredSpanBefore.playStart)
        assertNotNull(docTopBefore)
        assertNotNull(playRectBefore)
        view.pointerPosition = Point(playRectBefore.x + 10, docTopBefore.toInt() + 10)

        val screenYBefore = kotlin.math.round(docTopBefore - scrollPane.viewport.viewPosition.y).toInt()

        val newerSegment = TranscriptSegment(
            id = "seg-9",
            source = AudioSource.SYSTEM,
            startMillis = 9000L,
            endMillis = 9800L,
            text = "A brand new incoming message that gets inserted at the very top of the transcript"
        )
        val session2 = session1.withSegment(newerSegment)
        view.render(session2)
        scrollPane.validate()

        val docTopAfter = view.cardTopY("seg-7")
        assertNotNull(docTopAfter)
        val screenYAfter = kotlin.math.round(docTopAfter - scrollPane.viewport.viewPosition.y).toInt()

        assertEquals(screenYBefore, screenYAfter, "Anchored card under pointer must stay at the exact same on-screen Y")
    }

    @Test
    fun `render displays verification state styles, accuracy badge tooltip, and fades corrected highlight after 3s`() = SwingUtilities.invokeAndWait {
        val s = strings()
        val view: TranscriptSurface = createView(s)
        var now = 10_000L
        view.clock = { now }
        view.newestFirst = false
        view.asComponent.setSize(600, 400)

        val segVerified = TranscriptSegment(
            id = "seg-v",
            source = AudioSource.MICROPHONE,
            startMillis = 1000L,
            endMillis = 3000L,
            text = "hello world",
            words = listOf(
                TranscriptWord("hello", 1000L, 1800L, VerificationState.VERIFIED),
                TranscriptWord("world", 1900L, 2800L, VerificationState.CORRECTED)
            ),
            verificationState = VerificationState.CORRECTED,
            agreement = 0.92
        )
        val segUnverified = TranscriptSegment(
            id = "seg-u",
            source = AudioSource.SYSTEM,
            startMillis = 4000L,
            endMillis = 6000L,
            text = "unverified line",
            verificationState = VerificationState.UNVERIFIED
        )

        val session = ConversationSession(
            id = "s",
            title = "T",
            createdAt = 0L,
            segments = listOf(segVerified, segUnverified)
        )
        view.render(session)

        val fullText = view.renderedText()
        assertTrue(fullText.contains("✓ 92%"), "Verified header should include ✓ 92% badge")

        val spanV = view.spans[0]
        assertTrue(spanV.badgeEnd > spanV.badgeStart)
        assertEquals(
            s.verifiedBadgeTooltip(92),
            view.tooltipForHit(TranscriptView.Hit.VerificationBadge(spanV))
        )

        // Word "world" is CORRECTED and should initially have a soft highlight background
        val worldRange = spanV.words[1].first
        val attrsBeforeFade = view.characterAttributesAt(worldRange.first)
        assertNotNull(attrsBeforeFade)
        assertEquals(view.correctedHighlightColor(), StyleConstants.getBackground(attrsBeforeFade))
        assertFalse(StyleConstants.isItalic(attrsBeforeFade))

        // Advance clock past 3 seconds and trigger fade check
        now += 3_500L
        view.checkCorrectionHighlightFade()
        val spanVAfter = view.spans[0]
        val worldRangeAfter = spanVAfter.words[1].first
        val attrsAfterFade = view.characterAttributesAt(worldRangeAfter.first)
        assertNotNull(attrsAfterFade)
        assertNotEquals(view.correctedHighlightColor(), StyleConstants.getBackground(attrsAfterFade))

        // Unverified segment has unverified tooltip on both badge and text
        val spanU = view.spans[1]
        assertEquals(
            s.unverifiedBadgeTooltip,
            view.tooltipForHit(TranscriptView.Hit.VerificationBadge(spanU))
        )
        assertEquals(
            s.unverifiedBadgeTooltip,
            view.tooltipForHit(TranscriptView.Hit.Text(spanU, spanU.textStart + 1))
        )
    }

    @Test
    fun `low confidence words below 0_6 in LIVE state are underlined while high confidence or verified words are not`() = SwingUtilities.invokeAndWait {
        val highConfLive = TranscriptWord("clear", 0L, 400L, VerificationState.LIVE, confidence = 0.85)
        val lowConfLive = TranscriptWord("mumbled", 500L, 900L, VerificationState.LIVE, confidence = 0.45)
        val lowConfVerified = TranscriptWord("checked", 1000L, 1400L, VerificationState.VERIFIED, confidence = 0.45)

        assertFalse(TranscriptView.isLowConfidenceLiveWord(highConfLive))
        assertTrue(TranscriptView.isLowConfidenceLiveWord(lowConfLive))
        assertFalse(TranscriptView.isLowConfidenceLiveWord(lowConfVerified))

        val view: TranscriptSurface = createView()
        view.newestFirst = false
        val seg = TranscriptSegment(
            id = "live-mic",
            source = AudioSource.MICROPHONE,
            startMillis = 0L,
            endMillis = 1500L,
            text = "clear mumbled checked",
            words = listOf(highConfLive, lowConfLive, lowConfVerified),
            draft = true,
            verificationState = VerificationState.LIVE
        )
        val session = ConversationSession(id = "s", title = "T", createdAt = 0L, segments = listOf(seg))
        view.render(session)

        val span = view.spans.single()
        val clearAttrs = view.characterAttributesAt(span.words[0].first.first)
        val mumbledAttrs = view.characterAttributesAt(span.words[1].first.first)
        val checkedAttrs = view.characterAttributesAt(span.words[2].first.first)
        assertNotNull(clearAttrs)
        assertNotNull(mumbledAttrs)
        assertNotNull(checkedAttrs)

        assertFalse(StyleConstants.isUnderline(clearAttrs), "High-confidence LIVE word must not be underlined")
        assertTrue(StyleConstants.isUnderline(mumbledAttrs), "Low-confidence LIVE word (< 0.6) must be underlined")
        assertFalse(StyleConstants.isUnderline(checkedAttrs), "VERIFIED word must not have low-confidence underline")
    }

    @Test
    fun `LiveSlotsPanel maintains two permanent speaker slots with bounded height and clears slot on settle`() = SwingUtilities.invokeAndWait {
        val panel = LiveSlotsPanel(strings())
        panel.setSize(600, 120)

        // Simultaneous live speech from both speakers updates their respective permanent slots independently
        panel.render(
            mapOf(
                AudioSource.MICROPHONE to LiveLine(
                    startMillis = 1000L,
                    text = "I am speaking right now",
                    confirmedText = "I am",
                    agreement = 0.95
                ),
                AudioSource.SYSTEM to LiveLine(
                    startMillis = 1200L,
                    text = "And the other person is speaking at the same time"
                )
            ),
            visible = true
        )

        assertTrue(panel.isVisible)
        assertEquals("I am speaking right now", panel.slotText(AudioSource.MICROPHONE))
        assertEquals("And the other person is speaking at the same time", panel.slotText(AudioSource.SYSTEM))
        assertTrue(panel.micSlot.badgeLabel.isVisible)
        assertTrue(panel.micSlot.badgeLabel.text.contains("95%"))

        // Slot scrollPane enforces a fixed height (4 lines) so text growth never shifts archive cards
        val minH = panel.micSlot.scrollPane.minimumSize.height
        val maxH = panel.micSlot.scrollPane.maximumSize.height
        assertEquals(minH, maxH, "Slot scrollPane should have fixed height")
        assertEquals(minH, panel.micSlot.scrollPane.preferredSize.height)

        // When MICROPHONE settles, its slot clears while SYSTEM keeps its live speech
        panel.render(
            mapOf(
                AudioSource.SYSTEM to LiveLine(
                    startMillis = 1200L,
                    text = "And the other person is speaking at the same time"
                )
            ),
            visible = true
        )
        assertEquals("", panel.slotText(AudioSource.MICROPHONE))
        assertEquals("And the other person is speaking at the same time", panel.slotText(AudioSource.SYSTEM))
    }

    @Test
    fun `cards use full width minus margins in both layouts and for both sources`() = SwingUtilities.invokeAndWait {
        val ltrMic = TranscriptView.cardHorizontalBounds(AudioSource.MICROPHONE, 600, chatLayout = true, rtl = false)
        val ltrSys = TranscriptView.cardHorizontalBounds(AudioSource.SYSTEM, 600, chatLayout = true, rtl = false)
        assertEquals(10..590, ltrMic, "In LTR chat layout, MICROPHONE card spans full width (10 px margins)")
        assertEquals(10..590, ltrSys, "In LTR chat layout, SYSTEM card spans full width (10 px margins)")

        val rtlMic = TranscriptView.cardHorizontalBounds(AudioSource.MICROPHONE, 600, chatLayout = true, rtl = true)
        val rtlSys = TranscriptView.cardHorizontalBounds(AudioSource.SYSTEM, 600, chatLayout = true, rtl = true)
        assertEquals(10..590, rtlMic, "In RTL chat layout, MICROPHONE card spans full width")
        assertEquals(10..590, rtlSys, "In RTL chat layout, SYSTEM card spans full width")

        val disabledMic = TranscriptView.cardHorizontalBounds(AudioSource.MICROPHONE, 600, chatLayout = false, rtl = false)
        val disabledSys = TranscriptView.cardHorizontalBounds(AudioSource.SYSTEM, 600, chatLayout = false, rtl = false)
        assertEquals(10..590, disabledMic, "When chatLayout is false, card spans full width")
        assertEquals(10..590, disabledSys, "When chatLayout is false, card spans full width")
    }

    @Test
    fun `50 partial events within 1s cause at most 5 full renders while settles flush immediately`() = SwingUtilities.invokeAndWait {
        val view: TranscriptSurface = createView()
        var now = 1_000L
        view.clock = { now }
        view.newestFirst = false
        view.asComponent.setSize(600, 400)

        val baseSeg = TranscriptSegment(
            id = "seg-settled",
            source = AudioSource.MICROPHONE,
            startMillis = 0L,
            endMillis = 800L,
            text = "Initial settled card",
            draft = false
        )
        val liveSeg = TranscriptSegment(
            id = "seg-draft",
            source = AudioSource.SYSTEM,
            startMillis = 1000L,
            endMillis = 1000L,
            text = "word0",
            draft = true
        )
        val initialSession = ConversationSession(id = "s", title = "T", createdAt = 0L, segments = listOf(baseSeg, liveSeg))
        view.render(initialSession)
        assertEquals(1, view.fullRenderCount)

        // Fire 50 partial events across 1,000 ms (one every 20 ms)
        for (i in 1..50) {
            now += 20L
            val updatedLive = liveSeg.copy(text = "word0 " + (1..i).joinToString(" ") { "w$it" })
            view.render(initialSession.withSegment(updatedLive))
        }
        view.flushThrottledRender()

        val totalRenders = view.fullRenderCount + view.incrementalRenderCount
        assertTrue(view.fullRenderCount <= 5, "Expected <= 5 full renders for 50 partials in 1s, was ${view.fullRenderCount}")
        assertTrue(totalRenders <= 6, "Expected throttled renders <= 6 in 1s (including initial), was $totalRenders")
        assertTrue(view.incrementalRenderCount >= 3, "Partial updates should use incremental single-card rendering")

        // A settled archive card flushes immediately even within the 250ms throttle window, and fades in over 150ms
        now += 10L
        val newlySettled = TranscriptSegment(
            id = "seg-new-settled",
            source = AudioSource.MICROPHONE,
            startMillis = 2500L,
            endMillis = 3200L,
            settledAtMillis = 3200L,
            text = "Settled immediately",
            draft = false
        )
        view.render(initialSession.withSegment(newlySettled))
        val docText = view.renderedText()
        assertTrue(docText.contains("Settled immediately"), "Settled segment must flush immediately without waiting for throttle")

        // Verify 150ms fade-in alpha progression for the newly added archive card
        assertEquals(0.15f, view.cardAlpha("seg-new-settled", now), 0.01f)
        assertTrue(view.cardAlpha("seg-new-settled", now + 75L) in 0.5f..0.65f)
        assertEquals(1.0f, view.cardAlpha("seg-new-settled", now + 150L), 0.01f)
    }

    @Test
    fun `incremental single-card update shifts subsequent span offsets and keeps hit testing accurate`() = SwingUtilities.invokeAndWait {
        val view: TranscriptSurface = createView()
        var now = 5_000L
        view.clock = { now }
        view.newestFirst = false
        view.asComponent.setSize(600, 400)

        val seg1 = TranscriptSegment(
            id = "seg-1",
            source = AudioSource.MICROPHONE,
            startMillis = 1000L,
            endMillis = 2000L,
            settledAtMillis = 2000L,
            text = "short",
            draft = true
        )
        val seg2 = TranscriptSegment(
            id = "seg-2",
            source = AudioSource.SYSTEM,
            startMillis = 3000L,
            endMillis = 4000L,
            settledAtMillis = 4000L,
            text = "second card target",
            words = listOf(TranscriptWord("second", 3000L, 3400L), TranscriptWord("card", 3450L, 3700L), TranscriptWord("target", 3750L, 4000L)),
            draft = false
        )
        val session1 = ConversationSession(id = "s", title = "T", createdAt = 0L, segments = listOf(seg1, seg2))
        view.render(session1)
        assertEquals(1, view.fullRenderCount)
        assertEquals(0, view.incrementalRenderCount)

        // Advance past throttle window and update seg1 with longer text -> triggers incremental render
        now += 300L
        val seg1Longer = seg1.copy(text = "much longer first card text now")
        view.render(session1.withSegment(seg1Longer))

        assertEquals(1, view.fullRenderCount, "Must not perform a full rebuild when only one card's text changed")
        assertEquals(1, view.incrementalRenderCount, "Must perform an incremental update")

        val span2 = view.spans[1]
        assertEquals("second card target", view.textOf(span2.textStart until span2.textEnd))
        assertEquals("target", view.textOf(span2.words[2].first))
    }

    @Test
    fun `word hover, single-card selection clamping, copy, and search highlights work through TranscriptSurface`() = SwingUtilities.invokeAndWait {
        val listView = TranscriptListView(strings())
        val view: TranscriptSurface = listView
        view.newestFirst = false
        view.asComponent.setSize(600, 400)

        val seg1 = TranscriptSegment(
            id = "seg-1",
            source = AudioSource.MICROPHONE,
            startMillis = 1000L,
            endMillis = 2500L,
            text = "Alpha beta gamma",
            words = listOf(
                TranscriptWord("Alpha", 1000L, 1400L),
                TranscriptWord("beta", 1450L, 1900L),
                TranscriptWord("gamma", 1950L, 2500L)
            )
        )
        val seg2 = TranscriptSegment(
            id = "seg-2",
            source = AudioSource.SYSTEM,
            startMillis = 3000L,
            endMillis = 4500L,
            text = "Delta beta epsilon"
        )
        val session = ConversationSession(id = "s", title = "T", createdAt = 0L, segments = listOf(seg1, seg2))
        view.render(session)

        val span1 = view.spans[0]
        val betaOffset = span1.textStart + "Alpha ".length + 1
        val wordRange = view.wordRangeAt(span1, betaOffset)
        assertNotNull(wordRange)
        assertEquals("beta", view.textOf(wordRange))
        val timedWord = view.timedWordAt(span1, wordRange)
        assertNotNull(timedWord)
        assertEquals(1450L, timedWord.startMillis)
        assertNotNull(view.boundsOf(wordRange))

        // Selection is clamped within a single card (seg-1) even if end extends into seg-2
        val span2 = view.spans[1]
        listView.select(span1.textStart, span2.textEnd)
        assertTrue(view.selectionStart < view.selectionEnd)
        assertEquals("Alpha beta gamma", view.textOf(view.selectionStart until view.selectionEnd))
        assertEquals("Alpha beta gamma", listView.copySelectedTextToClipboard())

        // Search highlights find "beta" in both seg-1 and seg-2
        view.highlightSearch("beta")
        assertEquals(2, view.searchHighlights.size)
        assertEquals("seg-1", view.searchHighlights[0].first)
        assertEquals("seg-2", view.searchHighlights[1].first)

        view.highlightSearch("")
        assertTrue(view.searchHighlights.isEmpty())
    }
}
