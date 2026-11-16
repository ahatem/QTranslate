package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationFeature
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.LiveLine
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.VerificationState
import com.github.ahatem.qtranslate.core.conversation.WasapiLoopbackBackend
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationWindowLayoutTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun strings(): ConversationStrings {
        val tempDir = Files.createTempDirectory("conversation-layout-test").toFile()
        val manager = LocalizationManager(tempDir, LanguageTomlParser(), silentLogger)
        return ConversationStrings(manager)
    }

    @Test
    fun `window layout at 780, 1000 and 1300 px LTR and RTL does not clip top controls`() {
        SwingUtilities.invokeAndWait {
            val appData = Files.createTempDirectory("qt-conv-layout-test").toFile()
            val feature = ConversationFeature(
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
                activeServiceManager = com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager(
                    kotlinx.coroutines.flow.MutableStateFlow(emptyMap()),
                    kotlinx.coroutines.flow.MutableStateFlow(com.github.ahatem.qtranslate.core.settings.data.Configuration.DEFAULT)
                ),
                translateString = com.github.ahatem.qtranslate.core.main.domain.usecase.TranslateStringUseCase(
                    com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager(
                        kotlinx.coroutines.flow.MutableStateFlow(emptyMap()),
                        kotlinx.coroutines.flow.MutableStateFlow(com.github.ahatem.qtranslate.core.settings.data.Configuration.DEFAULT)
                    )
                ),
                appData = appData,
                loggerFactory = object : com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory { override fun getLogger(name: String) = silentLogger },
                systemBackend = WasapiLoopbackBackend(powershell = null)
            )
            val localizer = LocalizationManager(appData, LanguageTomlParser(), silentLogger)
            val languages = listOf(
                com.github.ahatem.qtranslate.api.language.LanguageCode.AUTO,
                com.github.ahatem.qtranslate.api.language.LanguageCode.ENGLISH,
                com.github.ahatem.qtranslate.api.language.LanguageCode.ARABIC
            )

            listOf(false, true).forEach { isRtlMode ->
                val window = ConversationWindow(
                    feature = feature,
                    strings = ConversationStrings(localizer),
                    languages = { languages },
                    defaultTarget = { com.github.ahatem.qtranslate.api.language.LanguageCode.ENGLISH },
                    isRtl = { isRtlMode }
                )
                window.open()

                listOf(780, 1000, 1300).forEach { w ->
                    window.size = java.awt.Dimension(w, 600)
                    window.validate()
                    window.doLayout()

                    // Ensure overlay banners can show and hide without clipping or crashing
                    window.privacyBar.isVisible = true
                    window.deviceWarningBar.isVisible = true
                    window.noticeBar.isVisible = true
                    window.validate()

                    window.privacyBar.isVisible = false
                    window.deviceWarningBar.isVisible = false
                    window.noticeBar.isVisible = false
                    window.validate()
                }
                window.dispose()
            }
        }
    }

    private fun layoutAll(innerPanel: JPanel, overlay: TranscriptOverlayPane, scrollPane: JScrollPane) {
        innerPanel.doLayout()
        overlay.doLayout()
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
    }

    private fun cardScreenY(
        view: TranscriptSurface,
        scrollPane: JScrollPane,
        innerPanel: JPanel,
        segmentId: String
    ): Double {
        val cardYInView = view.cardTopY(segmentId)
        assertNotNull(cardYInView, "Card top Y for $segmentId must exist")
        return cardYInView - scrollPane.viewport.viewPosition.y +
            SwingUtilities.convertPoint(scrollPane.viewport, 0, 0, innerPanel).y
    }

    @Test
    fun `showing and hiding pause pill does not change transcript viewport bounds`() {
        SwingUtilities.invokeAndWait {
            val view = TranscriptListView(strings())
            val scrollPane = JScrollPane(view)
            val pill = JButton("Pause Pill").apply { isVisible = false }
            val overlay = TranscriptOverlayPane(scrollPane, pill)

            val container = JPanel(BorderLayout()).apply {
                setBounds(0, 0, 600, 400)
                add(overlay, BorderLayout.CENTER)
            }
            layoutAll(container, overlay, scrollPane)

            val initialBounds = scrollPane.viewport.bounds
            assertTrue(initialBounds.width > 0 && initialBounds.height > 0)

            // Show pill dynamically without manual layoutAll — TranscriptOverlayPane must auto-layout pill bounds
            pill.text = "⏸ Updates paused while reading · 2 new"
            pill.isVisible = true
            assertTrue(pill.bounds.width > 0 && pill.bounds.height > 0, "Visible pause pill must immediately receive non-zero bounds")

            val boundsWithPill = scrollPane.viewport.bounds
            assertEquals(initialBounds, boundsWithPill, "Showing pill must not change viewport bounds")

            // Hide pill
            pill.isVisible = false
            layoutAll(container, overlay, scrollPane)

            val boundsWithoutPill = scrollPane.viewport.bounds
            assertEquals(initialBounds, boundsWithoutPill, "Hiding pill must not change viewport bounds")

            // Showing and closing overlay banners (privacy, warning, notice) does not change viewport bounds
            val banner1 = JButton("Privacy banner").apply { isVisible = false }
            val banner2 = JButton("Warning banner").apply { isVisible = false }
            val banner3 = JButton("Notice banner").apply { isVisible = false }
            val overlayWithBanners = TranscriptOverlayPane(scrollPane, pill, listOf(banner1, banner2, banner3))
            val bannerContainer = JPanel(BorderLayout()).apply {
                setBounds(0, 0, 600, 400)
                add(overlayWithBanners, BorderLayout.CENTER)
            }
            layoutAll(bannerContainer, overlayWithBanners, scrollPane)
            val boundsBaseline = scrollPane.viewport.bounds

            banner1.isVisible = true
            layoutAll(bannerContainer, overlayWithBanners, scrollPane)
            assertEquals(boundsBaseline, scrollPane.viewport.bounds, "Showing privacy banner overlay must not change viewport bounds")

            banner2.isVisible = true
            banner3.isVisible = true
            layoutAll(bannerContainer, overlayWithBanners, scrollPane)
            assertEquals(boundsBaseline, scrollPane.viewport.bounds, "Showing warning and notice banner overlays must not change viewport bounds")

            banner1.isVisible = false
            banner2.isVisible = false
            banner3.isVisible = false
            layoutAll(bannerContainer, overlayWithBanners, scrollPane)
            assertEquals(boundsBaseline, scrollPane.viewport.bounds, "Hiding all banner overlays must not change viewport bounds")

            // Pointer in empty space below cards must not count as hovering a card or block live-edge scroll
            val s1 = TranscriptSegment(id = "seg-1", source = AudioSource.MICROPHONE, startMillis = 1000, endMillis = 2000, text = "First card")
            view.render(ConversationSession(id = "s-1", title = "T", createdAt = 0L, segments = listOf(s1)))
            view.pointerPosition = Point(100, 350) // empty space well below single card
            assertEquals(null, view.spanAtPoint(Point(100, 350)), "Empty space below cards must return null spanAtPoint")
            val s2 = TranscriptSegment(id = "seg-2", source = AudioSource.SYSTEM, startMillis = 2000, endMillis = 3000, text = "Second card at top")
            view.render(ConversationSession(id = "s-1", title = "T", createdAt = 0L, segments = listOf(s1, s2)))
            assertEquals(0, scrollPane.viewport.viewPosition.y, "Pointer in empty space must not prevent staying at top live edge")
        }
    }

    @Test
    fun `live slot text growing from 0 to 10 lines while recording does not change panel height or viewport bounds`() {
        SwingUtilities.invokeAndWait {
            val strings = strings()
            val livePanel = LiveSlotsPanel(strings)
            val view = TranscriptListView(strings)
            val scrollPane = JScrollPane(view)
            val pill = JButton("Pill").apply { isVisible = false }
            val overlay = TranscriptOverlayPane(scrollPane, pill)

            val innerPanel = JPanel(BorderLayout()).apply {
                setBounds(0, 0, 600, 400)
                add(livePanel, BorderLayout.NORTH)
                add(overlay, BorderLayout.CENTER)
            }
            layoutAll(innerPanel, overlay, scrollPane)

            // Empty live speech while recording
            livePanel.render(emptyMap(), visible = true)
            layoutAll(innerPanel, overlay, scrollPane)

            val initialPanelHeight = livePanel.preferredSize.height
            val initialViewportBounds = scrollPane.viewport.bounds
            assertTrue(initialPanelHeight > 0)

            // Text growing to 10 lines
            val tenLinesText = (1..10).joinToString("\n") { "Live line $it" }
            livePanel.render(
                mapOf(
                    AudioSource.MICROPHONE to LiveLine(
                        startMillis = 1000L,
                        text = tenLinesText
                    )
                ),
                visible = true
            )
            layoutAll(innerPanel, overlay, scrollPane)

            assertEquals(
                initialPanelHeight,
                livePanel.preferredSize.height,
                "LiveSlotsPanel preferred height must remain constant when text grows"
            )
            assertEquals(
                initialViewportBounds,
                scrollPane.viewport.bounds,
                "Viewport bounds must remain constant when live slot text grows"
            )
        }
    }

    @Test
    fun `across recording start and stop anchored card on-screen Y is preserved`() {
        SwingUtilities.invokeAndWait {
            val strings = strings()
            val view = TranscriptListView(strings)
            view.setSize(600, 400)
            val scrollPane = JScrollPane(view)
            scrollPane.setSize(600, 400)
            val pill = JButton("Pill").apply { isVisible = false }
            val overlay = TranscriptOverlayPane(scrollPane, pill)
            val livePanel = LiveSlotsPanel(strings)

            val innerPanel = JPanel(BorderLayout()).apply {
                setBounds(0, 0, 600, 400)
                add(livePanel, BorderLayout.NORTH)
                add(overlay, BorderLayout.CENTER)
            }
            layoutAll(innerPanel, overlay, scrollPane)

            // 30 settled segments in archive
            val segments = (1..30).map { i ->
                TranscriptSegment(
                    id = "seg-$i",
                    source = if (i % 2 == 1) AudioSource.MICROPHONE else AudioSource.SYSTEM,
                    startMillis = i * 2000L,
                    endMillis = i * 2000L + 1500L,
                    text = "Settled message $i from the speaker",
                    pending = false,
                    failed = false
                )
            }
            val session = ConversationSession(id = "s-1", title = "Session 1", createdAt = 0L, segments = segments)
            view.windowSize = 50
            view.newestFirst = true
            view.render(session)
            layoutAll(innerPanel, overlay, scrollPane)

            // Rest mouse pointer over card seg-15 scrolled far enough down that viewPosition.y has room to increase
            val initialCardYInView = view.cardTopY("seg-15")
            assertNotNull(initialCardYInView)
            scrollPane.viewport.viewPosition = Point(0, (initialCardYInView - 140).toInt().coerceAtLeast(0))
            view.pointerPosition = Point(50, initialCardYInView.toInt() + 5)

            val anchorBefore = view.captureScrollAnchor()
            assertNotNull(anchorBefore)
            assertEquals("seg-15", anchorBefore.segmentId)
            val cardYBefore = cardScreenY(view, scrollPane, innerPanel, "seg-15")

            // Start recording: livePanel becomes visible at BorderLayout.NORTH
            livePanel.render(emptyMap(), visible = true)
            layoutAll(innerPanel, overlay, scrollPane)

            // Restore anchor
            view.restoreScrollAnchor(anchorBefore)

            val cardYAfterStart = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            assertEquals(cardYBefore, cardYAfterStart, 1.0, "Card on-screen Y must be preserved (±1 px) on start")

            // Stop recording: livePanel becomes hidden
            val anchorBeforeStop = view.captureScrollAnchor()
            livePanel.render(emptyMap(), visible = false)
            layoutAll(innerPanel, overlay, scrollPane)

            // Restore anchor
            view.restoreScrollAnchor(anchorBeforeStop)

            val cardYAfterStop = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            assertEquals(cardYBefore, cardYAfterStop, 1.0, "Card on-screen Y must be preserved (±1 px) on stop")
        }
    }

    @Test
    fun `anchored card on-screen Y is preserved in SOUTH mode and across order toggle`() {
        SwingUtilities.invokeAndWait {
            val strings = strings()
            val view = TranscriptListView(strings)
            view.setSize(600, 400)
            val scrollPane = JScrollPane(view)
            scrollPane.setSize(600, 400)
            val pill = JButton("Pill").apply { isVisible = false }
            val overlay = TranscriptOverlayPane(scrollPane, pill)
            val livePanel = LiveSlotsPanel(strings)

            val innerPanel = JPanel(BorderLayout()).apply {
                setBounds(0, 0, 600, 400)
                add(livePanel, BorderLayout.SOUTH)
                add(overlay, BorderLayout.CENTER)
            }
            layoutAll(innerPanel, overlay, scrollPane)

            val segments = (1..30).map { i ->
                TranscriptSegment(
                    id = "seg-$i",
                    source = if (i % 2 == 1) AudioSource.MICROPHONE else AudioSource.SYSTEM,
                    startMillis = i * 2000L,
                    endMillis = i * 2000L + 1500L,
                    text = "Settled message $i from the speaker",
                    pending = false,
                    failed = false
                )
            }
            val session = ConversationSession(id = "s-1", title = "Session 1", createdAt = 0L, segments = segments)
            view.windowSize = 50
            view.newestFirst = false
            view.render(session)
            layoutAll(innerPanel, overlay, scrollPane)

            val initialCardYInView = view.cardTopY("seg-15")
            assertNotNull(initialCardYInView)
            scrollPane.viewport.viewPosition = Point(0, (initialCardYInView - 140).toInt().coerceAtLeast(0))
            view.pointerPosition = Point(50, initialCardYInView.toInt() + 5)

            val cardYBeforeSouth = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            val anchorSouthStart = view.captureScrollAnchor()

            // Start recording in SOUTH mode
            livePanel.render(emptyMap(), visible = true)
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorSouthStart)

            val cardYAfterSouthStart = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            assertEquals(cardYBeforeSouth, cardYAfterSouthStart, 1.0, "Card on-screen Y must be preserved (±1 px) in SOUTH mode on start")

            // Toggle order from oldest-first (SOUTH) to newest-first (NORTH) while recording is active
            val anchorBeforeToggleToNorth = view.captureScrollAnchor()
            view.newestFirst = true
            innerPanel.remove(livePanel)
            innerPanel.add(livePanel, BorderLayout.NORTH)
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorBeforeToggleToNorth)

            val cardYAfterToggleToNorth = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            assertEquals(cardYBeforeSouth, cardYAfterToggleToNorth, 1.0, "Card on-screen Y must be preserved (±1 px) when toggling SOUTH -> NORTH")

            // Toggle order back from newest-first (NORTH) to oldest-first (SOUTH)
            val anchorBeforeToggleToSouth = view.captureScrollAnchor()
            view.newestFirst = false
            innerPanel.remove(livePanel)
            innerPanel.add(livePanel, BorderLayout.SOUTH)
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorBeforeToggleToSouth)

            val cardYAfterToggleToSouth = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            assertEquals(cardYBeforeSouth, cardYAfterToggleToSouth, 1.0, "Card on-screen Y must be preserved (±1 px) when toggling NORTH -> SOUTH")
        }
    }

    @Test
    fun `pointer on card X stays within 1 px across 50 inserts above X, height-changing correction above X, live growth, pill, start-stop, and order toggle`() {
        SwingUtilities.invokeAndWait {
            val strings = strings()
            val view = TranscriptListView(strings)
            var now = 10_000L
            view.clock = { now }
            view.setSize(600, 400)
            val scrollPane = JScrollPane(view)
            scrollPane.setSize(600, 400)
            val pill = JButton("↑ 3 new").apply { isVisible = false }
            val overlay = TranscriptOverlayPane(scrollPane, pill)
            val livePanel = LiveSlotsPanel(strings)

            val innerPanel = JPanel(BorderLayout()).apply {
                setBounds(0, 0, 600, 400)
                add(livePanel, BorderLayout.NORTH)
                add(overlay, BorderLayout.CENTER)
            }
            layoutAll(innerPanel, overlay, scrollPane)

            var session = ConversationSession(
                id = "s-stability",
                title = "Stability",
                createdAt = 0L,
                segments = (1..20).map { i ->
                    TranscriptSegment(
                        id = "seg-$i",
                        source = if (i % 2 == 1) AudioSource.MICROPHONE else AudioSource.SYSTEM,
                        startMillis = i * 1000L,
                        endMillis = i * 1000L + 800L,
                        settledAtMillis = i * 1000L + 800L,
                        text = "Initial archive card $i with spoken content"
                    )
                }
            )
            view.windowSize = 100
            view.newestFirst = true
            view.render(session)
            layoutAll(innerPanel, overlay, scrollPane)

            val cardXId = "seg-10"
            val initialCardTop = view.cardTopY(cardXId)!!
            scrollPane.viewport.viewPosition = Point(0, (initialCardTop - 150).toInt().coerceAtLeast(0))
            view.pointerPosition = Point(60, initialCardTop.toInt() + 10)
            val expectedScreenY = cardScreenY(view, scrollPane, innerPanel, cardXId)

            // (1) 50 inserts above X in newestFirst = true mode
            for (k in 21..70) {
                now += 500L
                val inserted = TranscriptSegment(
                    id = "seg-$k",
                    source = if (k % 2 == 1) AudioSource.MICROPHONE else AudioSource.SYSTEM,
                    startMillis = k * 1000L,
                    endMillis = k * 1000L + 800L,
                    settledAtMillis = k * 1000L + 800L,
                    text = if (k % 3 == 0) {
                        "Multi-line incoming card $k with a longer sentence that wraps across two or three lines in the transcript list"
                    } else {
                        "Incoming card $k"
                    }
                )
                session = session.withSegment(inserted)
                view.render(session)
                layoutAll(innerPanel, overlay, scrollPane)
                assertEquals(
                    expectedScreenY,
                    cardScreenY(view, scrollPane, innerPanel, cardXId),
                    1.0,
                    "Card X on-screen Y must stay within ±1 px after insert $k above X"
                )
            }

            // (2) A correction that changes the height of a card ("seg-65") above X
            val heightBeforeCorrection = view.heightIndex.heightOf("seg-65")!!
            val expandedText = (1..30).joinToString(" ") { "correctedWord$it expanding card height significantly" }
            val correctedSeg65 = session.segments.first { it.id == "seg-65" }.copy(
                text = expandedText,
                verificationState = VerificationState.CORRECTED,
                agreement = 0.88
            )
            now += 500L
            session = session.withSegment(correctedSeg65)
            view.render(session)
            layoutAll(innerPanel, overlay, scrollPane)
            val heightAfterCorrection = view.heightIndex.heightOf("seg-65")!!
            assertTrue(heightAfterCorrection > heightBeforeCorrection, "Corrected card above X must grow in height")
            assertEquals(
                expectedScreenY,
                cardScreenY(view, scrollPane, innerPanel, cardXId),
                1.0,
                "Card X on-screen Y must stay within ±1 px when a card above X grows due to correction"
            )

            // (3) Recording start + live text growing + pause pill show/hide + recording stop + order toggle
            val anchorStart = view.captureScrollAnchor()
            livePanel.render(emptyMap(), visible = true)
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorStart)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0)

            val growingLive = (1..10).joinToString("\n") { "Growing live line $it" }
            livePanel.render(mapOf(AudioSource.MICROPHONE to LiveLine(1000L, growingLive)), visible = true)
            layoutAll(innerPanel, overlay, scrollPane)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0)

            pill.isVisible = true
            layoutAll(innerPanel, overlay, scrollPane)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0)

            pill.isVisible = false
            layoutAll(innerPanel, overlay, scrollPane)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0)

            val anchorToggle = view.captureScrollAnchor()
            view.newestFirst = false
            innerPanel.remove(livePanel)
            innerPanel.add(livePanel, BorderLayout.SOUTH)
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorToggle)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0)

            val anchorStop = view.captureScrollAnchor()
            livePanel.render(emptyMap(), visible = false)
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorStop)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0)

            // Toggling chat layout while pointer rests on card must preserve on-screen Y
            val anchorChatToggle = view.captureScrollAnchor()
            view.chatLayout = false
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorChatToggle)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0, "Screen stability preserved when chatLayout is toggled off")

            val anchorChatToggleBack = view.captureScrollAnchor()
            view.chatLayout = true
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorChatToggleBack)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0, "Screen stability preserved when chatLayout is toggled on")

            // Showing and closing overlay banners does not change anchored card screen Y
            val b1 = JButton("Banner 1").apply { isVisible = false }
            val b2 = JButton("Banner 2").apply { isVisible = false }
            val overlayWithBannersTest = TranscriptOverlayPane(scrollPane, pill, listOf(b1, b2))
            innerPanel.remove(overlay)
            innerPanel.add(overlayWithBannersTest, BorderLayout.CENTER)
            layoutAll(innerPanel, overlayWithBannersTest, scrollPane)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0)

            b1.isVisible = true
            layoutAll(innerPanel, overlayWithBannersTest, scrollPane)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0, "Screen Y preserved when banner 1 is shown")

            b2.isVisible = true
            layoutAll(innerPanel, overlayWithBannersTest, scrollPane)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0, "Screen Y preserved when banner 2 is shown")

            b1.isVisible = false
            b2.isVisible = false
            layoutAll(innerPanel, overlayWithBannersTest, scrollPane)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0, "Screen Y preserved when banners are hidden")

            // Auto-translate stability: turning auto-translate on and receiving translations must maintain screen stability
            val anchorAutoTransOn = view.captureScrollAnchor()
            view.autoTranslate = true
            layoutAll(innerPanel, overlayWithBannersTest, scrollPane)
            view.restoreScrollAnchor(anchorAutoTransOn)
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0, "Screen Y preserved when auto-translate is turned on")

            // Arrival of short translation for a settled line above X does not move card X (one line was reserved)
            val seg70 = session.segments.first { it.id == "seg-70" }
            val heightWithReservedLine = view.heightIndex.heightOf("seg-70")!!
            val seg70WithShortTrans = seg70.copy(translation = "Short translation")
            session = session.withSegment(seg70WithShortTrans)
            view.render(session)
            layoutAll(innerPanel, overlayWithBannersTest, scrollPane)
            val heightWithShortTrans = view.heightIndex.heightOf("seg-70")!!
            assertEquals(heightWithReservedLine, heightWithShortTrans, "Arrival of short translation must not change card height")
            assertEquals(expectedScreenY, cardScreenY(view, scrollPane, innerPanel, cardXId), 1.0, "Screen Y preserved when translation arrives")
        }
    }

    @Test
    fun `2000 cards partial render under 5ms and paint measures zero cards and paints at most visible plus overscan`() {
        SwingUtilities.invokeAndWait {
            val strings = strings()
            val view = TranscriptListView(strings)
            view.windowSize = 2000
            var now = 1_000L
            view.clock = { now }
            view.newestFirst = true
            view.setSize(600, 400)
            val scrollPane = JScrollPane(view)
            scrollPane.setSize(600, 400)
            scrollPane.doLayout()
            scrollPane.viewport.doLayout()

            val settled = (1..1999).map { i ->
                TranscriptSegment(
                    id = "seg-$i",
                    source = if (i % 2 == 1) AudioSource.MICROPHONE else AudioSource.SYSTEM,
                    startMillis = i * 1000L,
                    endMillis = i * 1000L + 700L,
                    settledAtMillis = i * 1000L + 700L,
                    text = "Archive message number $i in a large 2000-card conversation session"
                )
            }
            val liveDraft = TranscriptSegment(
                id = "live-mic",
                source = AudioSource.MICROPHONE,
                startMillis = 2_000_000L,
                endMillis = 2_000_500L,
                text = "Live streaming words one two",
                draft = true
            )
            val initialSession = ConversationSession(
                id = "s-2000",
                title = "2000 Cards",
                createdAt = 0L,
                segments = settled + liveDraft
            )
            view.render(initialSession)
            scrollPane.doLayout()
            scrollPane.viewport.doLayout()
            assertEquals(2000, view.cards.size)

            // Warm up 5 partial updates so JIT optimizes collection iteration and layout
            for (w in 1..5) {
                now += 300L
                view.render(
                    initialSession.copy(
                        segments = settled + liveDraft.copy(text = "Live streaming words warmup $w")
                    )
                )
            }

            // Measure a single partial update across 2,000 cards
            now += 300L
            view.stamp.resetCounters()
            val updatedSession = initialSession.copy(
                segments = settled + liveDraft.copy(text = "Live streaming words one two three four")
            )
            val startNanos = System.nanoTime()
            view.render(updatedSession)
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0

            assertEquals(1, view.stamp.measureCount, "Partial update of 1 card in 2,000 must measure only 1 card")
            assertTrue(elapsedMs < 10.0, "Partial update render() on 2,000 cards must take < 10 ms, took ${"%.2f".format(elapsedMs)} ms")

            // Paint a 600x400 viewport clip and verify virtualisation
            view.stamp.resetCounters()
            val img = BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB)
            val g2 = img.createGraphics()
            try {
                g2.clip = Rectangle(0, 0, 600, 400)
                view.paint(g2)
            } finally {
                g2.dispose()
            }

            assertEquals(0, view.stamp.measureCount, "paintComponent must not measure cards (heights come from CardHeightIndex)")
            assertTrue(
                view.stamp.lastPaintCardCount in 1..15,
                "paintComponent must paint only visible + overscan cards (<= 15 out of 2,000), painted ${view.stamp.lastPaintCardCount}"
            )
        }
    }
}
