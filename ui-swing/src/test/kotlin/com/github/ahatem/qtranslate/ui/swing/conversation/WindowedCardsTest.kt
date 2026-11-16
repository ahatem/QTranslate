package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import org.junit.jupiter.api.Test
import java.awt.Point
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowedCardsTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun strings(): ConversationStrings {
        val tempDir = Files.createTempDirectory("windowed-cards-test").toFile()
        val manager = LocalizationManager(tempDir, LanguageTomlParser(), silentLogger)
        return ConversationStrings(manager)
    }

    @Test
    fun `with 2000 lines view holds at most N plus loaded pages cards`() = SwingUtilities.invokeAndWait {
        val strings = strings()
        val view = TranscriptListView(strings)
        view.windowSize = 10
        view.setSize(600, 400)

        val totalSegments = (1..2000).map { i ->
            TranscriptSegment(
                id = "seg-$i",
                source = AudioSource.MICROPHONE,
                startMillis = i * 1000L,
                endMillis = i * 1000L + 500L,
                settledAtMillis = i * 1000L,
                text = "Message $i"
            )
        }
        val session = ConversationSession(id = "s-2000", title = "Session 2000", createdAt = 0L, segments = totalSegments)
        view.render(session)

        // Initial render: holds at most windowSize (10) cards
        assertEquals(10, view.spans.size, "View must hold at most N cards initially")
        assertTrue(view.hasMoreOlderCards, "View must indicate older cards are available")

        // Load more adds exactly N older cards
        view.loadMore()
        assertEquals(20, view.spans.size, "Load more adds exactly N older cards")

        // Another load more adds another N
        view.loadMore()
        assertEquals(30, view.spans.size, "Second load more adds another N cards")

        // Returning to live edge trims back to N
        view.loadedPagesCount = 0
        view.render(session)
        assertEquals(10, view.spans.size, "Returning to live edge trims back to N cards")
    }

    @Test
    fun `search result outside window loads its page and ensures visibility`() = SwingUtilities.invokeAndWait {
        val strings = strings()
        val view = TranscriptListView(strings)
        view.windowSize = 10
        view.setSize(600, 400)

        val totalSegments = (1..100).map { i ->
            TranscriptSegment(
                id = "seg-$i",
                source = AudioSource.MICROPHONE,
                startMillis = i * 1000L,
                endMillis = i * 1000L + 500L,
                settledAtMillis = i * 1000L,
                text = if (i == 15) "Target search hit needle" else "Message $i"
            )
        }
        val session = ConversationSession(id = "s-100", title = "Session 100", createdAt = 0L, segments = totalSegments)
        view.render(session)

        // seg-15 is older than the initial 10 (which are seg-91..seg-100)
        assertTrue(view.spans.none { it.segment.id == "seg-15" }, "seg-15 not visible in initial window")

        // Ensuring visibility expands loaded pages to include seg-15
        view.ensureSegmentVisible("seg-15")
        assertTrue(view.spans.any { it.segment.id == "seg-15" }, "seg-15 is now loaded and present in view")
        assertNotNull(view.cardTopY("seg-15"), "cardTopY for seg-15 must be computed")
    }

    @Test
    fun `load more row click hit test produces LoadMore hit`() = SwingUtilities.invokeAndWait {
        val strings = strings()
        val view = TranscriptListView(strings)
        view.windowSize = 10
        view.setSize(600, 400)

        val totalSegments = (1..50).map { i ->
            TranscriptSegment(
                id = "seg-$i",
                source = AudioSource.MICROPHONE,
                startMillis = i * 1000L,
                endMillis = i * 1000L + 500L,
                settledAtMillis = i * 1000L,
                text = "Message $i"
            )
        }
        val session = ConversationSession(id = "s-50", title = "Session 50", createdAt = 0L, segments = totalSegments)
        view.render(session)

        // In newest-first (default), load more is at the bottom
        val loadMoreTop = view.heightIndex.topOf(TranscriptListView.LOAD_MORE_ID)
        assertNotNull(loadMoreTop, "Load more row must be indexed in heightIndex")
        val hit = view.hitTest(Point(20, loadMoreTop + 10))
        assertTrue(hit is TranscriptView.Hit.LoadMore, "Clicking load more row returns Hit.LoadMore")
    }
}
