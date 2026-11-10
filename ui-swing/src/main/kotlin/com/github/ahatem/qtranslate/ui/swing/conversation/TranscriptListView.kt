package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.TranscriptWord
import com.github.ahatem.qtranslate.core.conversation.VerificationState
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.text.BreakIterator
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.text.AttributeSet
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Virtualised transcript card list (`JComponent` + [Scrollable] + [TranscriptSurface]).
 *
 * Each [TranscriptSegment] is represented by an independent [CardModel] whose height is indexed by
 * [CardHeightIndex] and whose visual layout is measured and painted on demand by a single reusable
 * [TranscriptCardStamp] (`CellRendererPane`). Only cards intersecting the viewport clip (plus a
 * small overscan) are painted, and scroll anchoring uses exact prefix-sum positions from
 * [CardHeightIndex] rather than document-wide text relayout.
 */
class TranscriptListView(private val strings: ConversationStrings) : JComponent(), Scrollable, TranscriptSurface {

    private class CachedCardEntry(
        val segment: TranscriptSegment,
        val width: Int,
        val contentVersion: Any,
        val cardHeight: Int,
        val localSpan: TranscriptView.SegmentSpan,
        val renderedText: String,
        val anyActiveCorrectionHighlight: Boolean,
        var cachedBaseOffset: Int = -1,
        var cachedGlobalSpan: TranscriptView.SegmentSpan? = null
    ) {
        fun globalSpanAt(baseOffset: Int): TranscriptView.SegmentSpan {
            if (baseOffset == 0) return localSpan
            val existing = cachedGlobalSpan
            if (existing != null && cachedBaseOffset == baseOffset) {
                return existing
            }
            val shifted = TranscriptView.SegmentSpan(
                segment = segment,
                playStart = baseOffset + localSpan.playStart,
                playEnd = baseOffset + localSpan.playEnd,
                headerStart = baseOffset + localSpan.headerStart,
                headerEnd = baseOffset + localSpan.headerEnd,
                retryStart = baseOffset + localSpan.retryStart,
                retryEnd = baseOffset + localSpan.retryEnd,
                deleteStart = baseOffset + localSpan.deleteStart,
                deleteEnd = baseOffset + localSpan.deleteEnd,
                textStart = baseOffset + localSpan.textStart,
                textEnd = baseOffset + localSpan.textEnd,
                words = localSpan.words.map { (r, w) -> ((baseOffset + r.first)..(baseOffset + r.last)) to w },
                badgeStart = baseOffset + localSpan.badgeStart,
                badgeEnd = baseOffset + localSpan.badgeEnd
            )
            cachedBaseOffset = baseOffset
            cachedGlobalSpan = shifted
            return shifted
        }
    }

    val stamp = TranscriptCardStamp(strings)
    val heightIndex = CardHeightIndex(topPadding = TOP_PADDING, bottomPadding = BOTTOM_PADDING, cardGap = 0)

    var cards: List<CardModel> = emptyList()
        private set

    override var spans: List<TranscriptView.SegmentSpan> = emptyList()
        private set

    private val spanById = HashMap<String, TranscriptView.SegmentSpan>()
    private val cardById = HashMap<String, CardModel>()
    private val cachedEntries = HashMap<String, CachedCardEntry>()

    override var clock: () -> Long = System::currentTimeMillis

    override var playingTag: Any?
        get() = stamp.playingTag
        set(value) {
            val oldTag = stamp.playingTag
            if (oldTag != value) {
                stamp.playingTag = value
                val oldSegId = when (oldTag) {
                    is String -> oldTag
                    is com.github.ahatem.qtranslate.core.conversation.PlaybackTag -> oldTag.segmentId
                    else -> oldTag?.toString()
                }
                val newSegId = when (value) {
                    is String -> value
                    is com.github.ahatem.qtranslate.core.conversation.PlaybackTag -> value.segmentId
                    else -> value?.toString()
                }
                // Repaint only the affected cards without re-measuring heights
                if (oldSegId != null) repaintCard(oldSegId)
                if (newSegId != null && newSegId != oldSegId) repaintCard(newSegId)
            }
        }

    private fun repaintCard(segmentId: String) {
        val top = heightIndex.topOf(segmentId) ?: return
        val height = heightIndex.heightOf(segmentId) ?: return
        repaint(0, top, width.coerceAtLeast(1), height)
    }

    override var fullRenderCount: Int = 0
        private set

    override var incrementalRenderCount: Int = 0
        private set

    /** Whether scroll anchoring is active on model updates (exposed so tests can prove failure when disabled). */
    var anchoringEnabled: Boolean = true

    private var lastRenderMillis: Long = -TranscriptView.RENDER_THROTTLE_MS
    private var pendingThrottledSession: ConversationSession? = null
    private val renderThrottleTimer = Timer(TranscriptView.RENDER_THROTTLE_MS.toInt()) { flushThrottledRender() }.apply {
        isRepeats = false
    }

    private val cardFadeInStartMillis = mutableMapOf<String, Long>()
    private val fadeInTimer = Timer(16) { checkFadeInProgress() }.apply {
        isRepeats = true
    }

    private val correctedFirstSeenMillis = mutableMapOf<String, Long>()
    private val correctionFadeTimer = Timer(250) { checkCorrectionHighlightFade() }.apply {
        isRepeats = true
    }

    override var isProgrammaticScroll: Boolean = false
    override var pointerPosition: Point? = null

    override var newestFirst: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                renderedKey = null
                render(currentSession)
            }
        }

    override var chatLayout: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                renderedKey = null
                render(currentSession)
            }
        }

    override var rtlLayout: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                renderedKey = null
                render(currentSession)
            }
        }

    override var autoTranslate: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                renderedKey = null
                render(currentSession)
            }
        }

    override var windowSize: Int = 10
        set(value) {
            val clamped = value.coerceAtLeast(1)
            if (field != clamped) {
                field = clamped
                loadedPagesCount = 0
                renderedKey = null
                render(currentSession)
            }
        }

    internal var loadedPagesCount: Int = 0

    override fun loadMore() {
        val session = currentSession ?: return
        val totalCount = session.segments.size
        val currentMax = windowSize * (loadedPagesCount + 1)
        if (currentMax < totalCount) {
            val anchor = captureScrollAnchor()
            loadedPagesCount++
            renderedKey = null
            render(session)
            restoreScrollAnchor(anchor)
        }
    }

    override fun ensureSegmentVisible(segmentId: String) {
        val session = currentSession ?: return
        val idx = session.segments.indexOfFirst { it.id == segmentId }
        if (idx < 0) return
        val distFromEnd = session.segments.size - idx
        val neededCards = distFromEnd
        val currentCap = windowSize * (loadedPagesCount + 1)
        if (neededCards > currentCap) {
            val neededPages = ((neededCards - 1) / windowSize)
            loadedPagesCount = maxOf(loadedPagesCount, neededPages)
            renderedKey = null
            render(session)
        }
    }

    override var currentSession: ConversationSession? = null
        private set

    internal var hasMoreOlderCards: Boolean = false
    internal var remainingOlderCardsCount: Int = 0

    private var renderedKey: Any? = null
    private var lastIndexedWidth: Int = 0

    // Single-card selection state (global offsets and owning segmentId)
    private var selectionSegmentId: String? = null
    private var selectionMarkGlobal: Int = 0
    private var selectionDotGlobal: Int = 0
    private var draggingSelection: Boolean = false

    override val selectionStartOffset: Int
        get() = min(selectionMarkGlobal, selectionDotGlobal)

    override val selectionEndOffset: Int
        get() = max(selectionMarkGlobal, selectionDotGlobal)

    private var currentSearchHighlights: List<Pair<String, IntRange>> = emptyList()
    private val searchHighlightsBySegment = HashMap<String, List<IntRange>>()

    override val searchHighlights: List<Pair<String, IntRange>>
        get() = currentSearchHighlights

    init {
        isOpaque = true
        isFocusable = true
        font = (UIManager.getFont("TextPane.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, 13)).let {
            it.deriveFont(it.size2D + 2f)
        }

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                pointerPosition = e.point
            }

            override fun mouseExited(e: MouseEvent) {
                pointerPosition = null
            }

            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                requestFocusInWindow()
                val hit = hitTest(e.point)
                if (hit is TranscriptView.Hit.Text) {
                    selectionSegmentId = hit.span.segment.id
                    val clamped = hit.offset.coerceIn(hit.span.textStart, hit.span.textEnd)
                    selectionMarkGlobal = clamped
                    selectionDotGlobal = clamped
                    draggingSelection = true
                    repaint()
                } else {
                    draggingSelection = false
                    clearSelection()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (draggingSelection) {
                    draggingSelection = false
                    if (selectionStartOffset == selectionEndOffset) {
                        selectionSegmentId = null
                    }
                    repaint()
                }
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                pointerPosition = e.point
            }

            override fun mouseDragged(e: MouseEvent) {
                pointerPosition = e.point
                if (!draggingSelection) return
                val segId = selectionSegmentId ?: return
                val model = cardById[segId] ?: return
                val topY = heightIndex.topOf(segId) ?: return
                val offset = stamp.textOffsetAtPoint(
                    model = model,
                    cardTopY = topY,
                    totalWidth = effectiveWidth(),
                    pointInList = e.point,
                    nowMillis = clock(),
                    correctedFirstSeenMillis = correctedFirstSeenMillis
                )
                if (offset != selectionDotGlobal) {
                    selectionDotGlobal = offset
                    repaint()
                }
            }
        })

        val copyAction = object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                copySelectedTextToClipboard()
            }
        }
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), "copy-selection")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.META_DOWN_MASK), "copy-selection")
        actionMap.put("copy-selection", copyAction)
    }

    /** Copies the currently selected card text (if any) to the system clipboard and returns it. */
    fun copySelectedTextToClipboard(): String? {
        val start = selectionStartOffset
        val end = selectionEndOffset
        if (start >= end) return null
        val selected = textOf(start until end)
        if (selected.isEmpty()) return null
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(selected), null)
        }
        return selected
    }

    /** Selects the global character range `[start, end)` within the card containing [start]. */
    fun select(start: Int, end: Int) {
        if (start >= end) {
            clearSelection()
            return
        }
        val span = spans.firstOrNull { it.textEnd > start && it.playStart <= end }
        if (span == null || end <= span.textStart) {
            clearSelection()
            return
        }
        selectionSegmentId = span.segment.id
        selectionMarkGlobal = start.coerceIn(span.textStart, span.textEnd)
        selectionDotGlobal = end.coerceIn(span.textStart, span.textEnd)
        repaint()
    }

    override fun clearSelection() {
        if (selectionSegmentId != null || selectionMarkGlobal != 0 || selectionDotGlobal != 0) {
            selectionSegmentId = null
            selectionMarkGlobal = 0
            selectionDotGlobal = 0
            repaint()
        }
    }

    override fun render(session: ConversationSession?) {
        currentSession = session
        val key = session?.let { ((it.id to it.segments) to newestFirst) to (windowSize to loadedPagesCount) }
        if (key == renderedKey) return

        val now = clock()
        if (isPartialOnlyUpdate(session)) {
            val elapsed = now - lastRenderMillis
            if (elapsed < TranscriptView.RENDER_THROTTLE_MS) {
                pendingThrottledSession = session
                if (isShowing && !renderThrottleTimer.isRunning) {
                    renderThrottleTimer.initialDelay = (TranscriptView.RENDER_THROTTLE_MS - elapsed).toInt().coerceAtLeast(1)
                    renderThrottleTimer.restart()
                }
                return
            }
        }

        pendingThrottledSession = null
        if (renderThrottleTimer.isRunning) renderThrottleTimer.stop()
        performRender(session, key, now)
    }

    override fun flushThrottledRender() {
        val pending = pendingThrottledSession ?: return
        pendingThrottledSession = null
        val key = ((pending.id to pending.segments) to newestFirst) to (windowSize to loadedPagesCount)
        if (key != renderedKey) {
            performRender(pending, key, clock())
        }
    }

    private fun isPartialOnlyUpdate(session: ConversationSession?): Boolean {
        if (session == null || spans.isEmpty()) return false
        val rawSegments = session.segments
        val ordered = if (newestFirst) rawSegments.reversed() else rawSegments
        if (ordered.size != spans.size) return false
        var hasDraftDiff = false
        for (i in ordered.indices) {
            val newSeg = ordered[i]
            val oldSeg = spans[i].segment
            if (newSeg.id != oldSeg.id) return false
            if (newSeg != oldSeg) {
                if (!newSeg.draft || !oldSeg.draft) return false
                hasDraftDiff = true
            }
        }
        return hasDraftDiff
    }

    private fun findSingleChangedCardIndex(ordered: List<TranscriptSegment>): Int {
        if (ordered.isEmpty() || ordered.size != spans.size) return -1
        var diffIndex = -1
        for (i in ordered.indices) {
            val newSeg = ordered[i]
            val oldSeg = spans[i].segment
            if (newSeg.id != oldSeg.id) return -1
            if (newSeg != oldSeg) {
                if (diffIndex != -1) return -1
                diffIndex = i
            }
        }
        return diffIndex
    }

    private fun performRender(session: ConversationSession?, key: Any?, now: Long) {
        renderedKey = key
        lastRenderMillis = now

        val scrollPane = (parent?.parent as? JScrollPane)
        val viewport = scrollPane?.viewport
        val scrollBar = scrollPane?.verticalScrollBar

        val wasAtTop = scrollBar == null || scrollBar.value <= 24
        val wasAtBottom = scrollBar == null || scrollBar.value + scrollBar.visibleAmount >= scrollBar.maximum - 24

        val pointer = pointerPosition ?: runCatching { mousePosition }.getOrNull()
        val anchor = if (anchoringEnabled) captureScrollAnchor() else null

        // Preserve card-relative selection across renders
        val prevSelSegId = selectionSegmentId
        val prevSelSpan = prevSelSegId?.let { spanById[it] }
        val selRelStart = if (prevSelSpan != null && selectionStartOffset < selectionEndOffset) {
            (selectionStartOffset - prevSelSpan.textStart).coerceAtLeast(0)
        } else null
        val selRelEnd = if (prevSelSpan != null && selectionStartOffset < selectionEndOffset) {
            (selectionEndOffset - prevSelSpan.textStart).coerceAtLeast(0)
        } else null

        val rawSegments = session?.segments.orEmpty()
        val totalRawCount = rawSegments.size
        val maxCardsToRender = windowSize * (loadedPagesCount + 1)
        val visibleRawSegments = if (totalRawCount > maxCardsToRender) {
            rawSegments.takeLast(maxCardsToRender)
        } else {
            rawSegments
        }
        val ordered = if (newestFirst) visibleRawSegments.reversed() else visibleRawSegments
        hasMoreOlderCards = totalRawCount > visibleRawSegments.size
        remainingOlderCardsCount = totalRawCount - visibleRawSegments.size

        val w = effectiveWidth()
        val singleDiffIndex = if (lastIndexedWidth == w) findSingleChangedCardIndex(ordered) else -1

        if (singleDiffIndex >= 0 && cards is MutableList<CardModel> && spans is MutableList<TranscriptView.SegmentSpan>) {
            incrementalRenderCount++
            val mutableCards = cards as MutableList<CardModel>
            val mutableSpans = spans as MutableList<TranscriptView.SegmentSpan>
            val segment = ordered[singleDiffIndex]
            val oldModel = mutableCards[singleDiffIndex]
            val oldSpan = mutableSpans[singleDiffIndex]
            val baseOffset = oldModel.baseGlobalOffset
            val newModel = CardModel(
                segment = segment,
                isLatest = singleDiffIndex == 0,
                chatLayout = chatLayout,
                rtlLayout = rtlLayout,
                autoTranslate = autoTranslate,
                baseGlobalOffset = baseOffset
            )
            val version = newModel.contentVersion()
            val layout = stamp.measureCard(
                model = newModel.copy(baseGlobalOffset = 0),
                totalWidth = w,
                nowMillis = now,
                correctedFirstSeenMillis = correctedFirstSeenMillis
            )
            val entry = CachedCardEntry(
                segment = segment,
                width = w,
                contentVersion = version,
                cardHeight = layout.cardHeight,
                localSpan = layout.localSpan,
                renderedText = layout.renderedText,
                anyActiveCorrectionHighlight = layout.anyActiveCorrectionHighlight
            )
            cachedEntries[segment.id] = entry
            val newSpan = entry.globalSpanAt(baseOffset)
            mutableCards[singleDiffIndex] = newModel
            mutableSpans[singleDiffIndex] = newSpan
            cardById[segment.id] = newModel
            spanById[segment.id] = newSpan
            heightIndex.updateCardAt(singleDiffIndex, segment.id, version, layout.cardHeight)

            if (newSpan.textEnd != oldSpan.textEnd) {
                var runningGlobalOffset = newSpan.textEnd
                for (j in (singleDiffIndex + 1) until ordered.size) {
                    runningGlobalOffset += 2
                    val nextSeg = ordered[j]
                    val nextOldModel = mutableCards[j]
                    val nextModel = if (nextOldModel.baseGlobalOffset == runningGlobalOffset) {
                        nextOldModel
                    } else {
                        nextOldModel.copy(baseGlobalOffset = runningGlobalOffset).also {
                            mutableCards[j] = it
                            cardById[nextSeg.id] = it
                        }
                    }
                    val nextEntry = cachedEntries[nextSeg.id]
                    if (nextEntry != null) {
                        val nextSpan = nextEntry.globalSpanAt(nextModel.baseGlobalOffset)
                        mutableSpans[j] = nextSpan
                        spanById[nextSeg.id] = nextSpan
                        runningGlobalOffset = nextSpan.textEnd
                    }
                }
            }

            if (entry.anyActiveCorrectionHighlight && isShowing) {
                if (!correctionFadeTimer.isRunning) correctionFadeTimer.start()
            }
        } else {
            fullRenderCount++
            val hadExistingCards = cards.isNotEmpty()
            val previousIds = if (hadExistingCards) cardById.keys.toHashSet() else emptySet()

            lastIndexedWidth = w

            val newCards = ArrayList<CardModel>(ordered.size)
            val newSpans = ArrayList<TranscriptView.SegmentSpan>(ordered.size)
            val newSpecs = ArrayList<CardHeightIndex.CardSpec>(ordered.size + 1)
            spanById.clear()
            cardById.clear()

            if (hasMoreOlderCards && !newestFirst) {
                newSpecs.add(CardHeightIndex.CardSpec(LOAD_MORE_ID, 0))
            }

            var anyActiveCorrectionHighlight = false
            var runningGlobalOffset = 0

            for (i in ordered.indices) {
                val segment = ordered[i]
                if (!segment.draft && hadExistingCards && segment.id !in previousIds) {
                    cardFadeInStartMillis.putIfAbsent(segment.id, now)
                }
                if (i > 0) {
                    runningGlobalOffset += 2 // matches "\n\n" separator between cards
                }
                val model = CardModel(
                    segment = segment,
                    isLatest = i == 0,
                    chatLayout = chatLayout,
                    rtlLayout = rtlLayout,
                    autoTranslate = autoTranslate,
                    baseGlobalOffset = runningGlobalOffset
                )
                val version = model.contentVersion()
                val existing = cachedEntries[segment.id]
                val needsCorrectionCheck = existing != null && (
                    existing.anyActiveCorrectionHighlight ||
                        segment.verificationState == VerificationState.CORRECTED ||
                        segment.words.any { it.verificationState == VerificationState.CORRECTED }
                    )

                val entry = if (existing != null &&
                    existing.width == w &&
                    existing.contentVersion == version &&
                    !needsCorrectionCheck
                ) {
                    existing
                } else {
                    val layout = stamp.measureCard(
                        model = model.copy(baseGlobalOffset = 0),
                        totalWidth = w,
                        nowMillis = now,
                        correctedFirstSeenMillis = correctedFirstSeenMillis
                    )
                    CachedCardEntry(
                        segment = segment,
                        width = w,
                        contentVersion = version,
                        cardHeight = layout.cardHeight,
                        localSpan = layout.localSpan,
                        renderedText = layout.renderedText,
                        anyActiveCorrectionHighlight = layout.anyActiveCorrectionHighlight
                    ).also { cachedEntries[segment.id] = it }
                }

                if (entry.anyActiveCorrectionHighlight) {
                    anyActiveCorrectionHighlight = true
                }

                val globalSpan = entry.globalSpanAt(runningGlobalOffset)
                runningGlobalOffset = globalSpan.textEnd

                newCards.add(model)
                newSpans.add(globalSpan)
                newSpecs.add(CardHeightIndex.CardSpec(segment.id, version))
                spanById[segment.id] = globalSpan
                cardById[segment.id] = model
            }

            if (hasMoreOlderCards && newestFirst) {
                newSpecs.add(CardHeightIndex.CardSpec(LOAD_MORE_ID, 0))
            }

            if (cachedEntries.size > newCards.size) {
                val iter = cachedEntries.keys.iterator()
                while (iter.hasNext()) {
                    val k = iter.next()
                    if (!cardById.containsKey(k)) {
                        iter.remove()
                    }
                }
            }

            cards = newCards
            spans = newSpans
            heightIndex.sync(newSpecs, w) { segId ->
                if (segId == LOAD_MORE_ID) LOAD_MORE_HEIGHT else cachedEntries[segId]?.cardHeight ?: 48
            }

            if (cardFadeInStartMillis.values.any { now - it < TranscriptView.CARD_FADE_IN_MS } && isShowing) {
                if (!fadeInTimer.isRunning) fadeInTimer.start()
            } else if (fadeInTimer.isRunning) {
                fadeInTimer.stop()
            }

            if (anyActiveCorrectionHighlight && isShowing) {
                if (!correctionFadeTimer.isRunning) correctionFadeTimer.start()
            } else if (correctionFadeTimer.isRunning) {
                correctionFadeTimer.stop()
            }
        }

        // Restore selection if still valid
        if (prevSelSegId != null && selRelStart != null && selRelEnd != null) {
            val newSpan = spanById[prevSelSegId]
            if (newSpan != null) {
                val restoredStart = (newSpan.textStart + selRelStart).coerceIn(newSpan.textStart, newSpan.textEnd)
                val restoredEnd = (newSpan.textStart + selRelEnd).coerceIn(newSpan.textStart, newSpan.textEnd)
                if (restoredStart < restoredEnd) {
                    selectionSegmentId = prevSelSegId
                    selectionMarkGlobal = restoredStart
                    selectionDotGlobal = restoredEnd
                } else {
                    clearSelection()
                }
            } else {
                clearSelection()
            }
        }

        isProgrammaticScroll = true
        try {
            val vpHeight = viewport?.extentSize?.height ?: 0
            val totalH = maxOf(heightIndex.totalHeight, vpHeight).coerceAtLeast(40)
            if (scrollPane != null) {
                val vpSize = viewport?.extentSize?.takeIf { it.width > 0 && it.height > 0 } ?: scrollPane.size
                val targetW = if (vpSize.width > 0) vpSize.width else w
                if (width != targetW || height != totalH) {
                    setSize(targetW, totalH)
                }
                scrollPane.doLayout()
                viewport?.doLayout()
            } else if (width > 0) {
                if (height != totalH) {
                    setSize(width, totalH)
                }
            }

            val pointerOnCard = pointer != null && spanAtPoint(pointer) != null
            val pinnedToLiveEdge = if (newestFirst) wasAtTop else wasAtBottom
            val followLiveEdge = pinnedToLiveEdge && !pointerOnCard

            if (followLiveEdge) {
                if (newestFirst) {
                    if (viewport != null) viewport.viewPosition = Point(viewport.viewPosition.x, 0)
                    scrollBar?.value = 0
                } else if (scrollBar != null) {
                    val bottomY = (heightIndex.totalHeight - (viewport?.extentSize?.height ?: scrollBar.visibleAmount)).coerceAtLeast(0)
                    if (viewport != null) viewport.viewPosition = Point(viewport.viewPosition.x, bottomY)
                    scrollBar.value = bottomY
                }
            } else if (anchoringEnabled && anchor != null) {
                restoreScrollAnchor(anchor)
            }
        } finally {
            isProgrammaticScroll = false
        }

        revalidate()
        repaint()
    }

    private fun effectiveWidth(): Int {
        val vpWidth = (parent as? JViewport)?.extentSize?.width ?: 0
        if (vpWidth > 0) return vpWidth
        if (width > 0) return width
        val spWidth = (parent?.parent as? JScrollPane)?.width ?: 0
        if (spWidth > 0) return spWidth
        return 600
    }

    @Suppress("DEPRECATION")
    override fun reshape(x: Int, y: Int, w: Int, h: Int) {
        val prevW = width
        super.reshape(x, y, w, h)
        if (w > 0 && w != prevW && w != lastIndexedWidth && cards.isNotEmpty()) {
            val now = clock()
            lastIndexedWidth = w
            val specs = ArrayList<CardHeightIndex.CardSpec>(cards.size)
            for (model in cards) {
                val version = model.contentVersion()
                val layout = stamp.measureCard(
                    model = model.copy(baseGlobalOffset = 0),
                    totalWidth = w,
                    nowMillis = now,
                    correctedFirstSeenMillis = correctedFirstSeenMillis
                )
                cachedEntries[model.id] = CachedCardEntry(
                    segment = model.segment,
                    width = w,
                    contentVersion = version,
                    cardHeight = layout.cardHeight,
                    localSpan = layout.localSpan,
                    renderedText = layout.renderedText,
                    anyActiveCorrectionHighlight = layout.anyActiveCorrectionHighlight
                )
                specs.add(CardHeightIndex.CardSpec(model.id, version))
            }
            heightIndex.sync(specs, w) { segId -> cachedEntries[segId]?.cardHeight ?: 48 }
        }
    }

    private fun stableAnchorAncestor(scrollPane: JScrollPane): Component {
        val parent = scrollPane.parent ?: return scrollPane
        return if (parent is TranscriptOverlayPane) parent.parent ?: parent else parent
    }

    override fun captureScrollAnchor(): TranscriptView.ScrollAnchor? {
        if (cards.isEmpty()) return null
        val scrollPane = (parent?.parent as? JScrollPane)
        val viewport = scrollPane?.viewport
        val currentScrollY = viewport?.viewPosition?.y ?: scrollPane?.verticalScrollBar?.value ?: 0

        val pointer = pointerPosition ?: runCatching { mousePosition }.getOrNull()
        val pointerSpan = pointer?.let(::spanAtPoint)

        val firstVisibleSpan = if (cards.isNotEmpty()) {
            val firstIdx = heightIndex.cardAt(currentScrollY + TOP_PADDING, clampToNearest = true).coerceIn(0, cards.lastIndex)
            val chosenIdx = if (heightIndex.bottomOfIndex(firstIdx) < currentScrollY + 6 && firstIdx + 1 <= cards.lastIndex) {
                firstIdx + 1
            } else {
                firstIdx
            }
            spans.getOrNull(chosenIdx)
        } else null

        val anchorSpan = pointerSpan ?: firstVisibleSpan ?: return null
        val anchorSegmentId = anchorSpan.segment.id
        val cardTop = heightIndex.topOf(anchorSegmentId)?.toDouble() ?: return null
        val yOffsetInViewport = cardTop - currentScrollY
        val viewportYInAncestor = if (scrollPane != null && viewport != null) {
            SwingUtilities.convertPoint(viewport, 0, 0, stableAnchorAncestor(scrollPane)).y.toDouble()
        } else 0.0
        val pointerOffsetInCard = if (pointerSpan != null && pointer != null) {
            pointer.y - cardTop
        } else null

        return TranscriptView.ScrollAnchor(
            segmentId = anchorSegmentId,
            yOffsetInViewport = yOffsetInViewport,
            yInAncestor = viewportYInAncestor + yOffsetInViewport,
            pointerOffsetInCard = pointerOffsetInCard
        )
    }

    override fun restoreScrollAnchor(anchor: TranscriptView.ScrollAnchor?) {
        if (anchor == null || !anchoringEnabled) return
        val scrollPane = (parent?.parent as? JScrollPane) ?: return
        val viewport = scrollPane.viewport
        val scrollBar = scrollPane.verticalScrollBar

        val cardTop = heightIndex.topOf(anchor.segmentId)?.toDouble() ?: return
        val viewportYInAncestor = if (viewport != null) {
            SwingUtilities.convertPoint(viewport, 0, 0, stableAnchorAncestor(scrollPane)).y.toDouble()
        } else 0.0
        val desiredOffsetInViewport = anchor.yInAncestor - viewportYInAncestor

        isProgrammaticScroll = true
        try {
            val totalH = maxOf(height, heightIndex.totalHeight)
            val maxScroll = if (viewport != null && viewport.extentSize.height > 0) {
                (totalH - viewport.extentSize.height).coerceAtLeast(0)
            } else if (scrollBar != null && scrollBar.maximum > 0) {
                (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
            } else Int.MAX_VALUE
            val targetY = round(cardTop - desiredOffsetInViewport).toInt().coerceIn(0, maxScroll)
            if (viewport != null) {
                viewport.viewPosition = Point(viewport.viewPosition.x, targetY)
            }
            scrollBar?.value = targetY
            val currentPointer = pointerPosition
            if (currentPointer != null && anchor.pointerOffsetInCard != null) {
                pointerPosition = Point(
                    currentPointer.x,
                    round(cardTop + anchor.pointerOffsetInCard).toInt()
                )
            }
        } finally {
            isProgrammaticScroll = false
        }
    }

    override fun cardAlpha(segmentId: String, nowMillis: Long): Float {
        val started = cardFadeInStartMillis[segmentId] ?: return 1f
        val elapsed = (nowMillis - started).coerceAtLeast(0L)
        if (elapsed >= TranscriptView.CARD_FADE_IN_MS) return 1f
        return (0.15f + 0.85f * (elapsed.toFloat() / TranscriptView.CARD_FADE_IN_MS.toFloat())).coerceIn(0.15f, 1f)
    }

    private fun checkFadeInProgress() {
        val now = clock()
        cardFadeInStartMillis.entries.removeIf { (_, started) -> now - started >= TranscriptView.CARD_FADE_IN_MS }
        if (cardFadeInStartMillis.isEmpty()) {
            fadeInTimer.stop()
        }
        repaint()
    }

    override fun checkCorrectionHighlightFade() {
        val now = clock()
        val anyStillActive = correctedFirstSeenMillis.values.any { now - it < TranscriptView.CORRECTION_HIGHLIGHT_MS }
        renderedKey = null
        render(currentSession)
        if (!anyStillActive && correctionFadeTimer.isRunning) {
            correctionFadeTimer.stop()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = UIManager.getColor("TextPane.background") ?: background ?: Color(0x1e1e1e)
            g2.color = bg
            val clip = g2.clipBounds ?: Rectangle(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
            g2.fillRect(clip.x, clip.y, clip.width, clip.height)

            if (cards.isEmpty()) {
                stamp.lastPaintCardCount = 0
                g2.color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                g2.font = font.deriveFont(Font.ITALIC)
                g2.drawString(strings.emptyHint, 16, 28)
                return
            }

            val now = clock()
            val w = effectiveWidth()
            val visibleIndices = heightIndex.visibleRange(clip.y, clip.y + clip.height, overscan = OVERSCAN_CARDS)
            var paintedInPass = 0

            for (i in visibleIndices) {
                val model = cards.getOrNull(i) ?: continue
                val topY = heightIndex.topOfIndex(i)
                val cardH = heightIndex.heightOfIndex(i)
                val alpha = cardAlpha(model.id, now)

                val selectionLocalRange = if (selectionSegmentId == model.id && selectionStartOffset < selectionEndOffset) {
                    (selectionStartOffset - model.baseGlobalOffset) until (selectionEndOffset - model.baseGlobalOffset)
                } else null

                val searchLocalRanges = searchHighlightsBySegment[model.id].orEmpty()

                stamp.paintCard(
                    g = g2,
                    host = this,
                    model = model,
                    cardTopY = topY,
                    totalWidth = w,
                    cardHeight = cardH,
                    alpha = alpha,
                    nowMillis = now,
                    correctedFirstSeenMillis = correctedFirstSeenMillis,
                    selectionLocalRange = selectionLocalRange,
                    searchLocalRanges = searchLocalRanges
                )
                paintedInPass++
            }

            if (hasMoreOlderCards) {
                val loadMoreTop = heightIndex.topOf(LOAD_MORE_ID)
                if (loadMoreTop != null && loadMoreTop + LOAD_MORE_HEIGHT >= clip.y && loadMoreTop <= clip.y + clip.height) {
                    paintLoadMoreRow(g2, loadMoreTop, w)
                }
            }
            stamp.lastPaintCardCount = paintedInPass
        } finally {
            g2.dispose()
        }
    }

    private fun paintLoadMoreRow(g: Graphics2D, topY: Int, totalWidth: Int) {
        val h = LOAD_MORE_HEIGHT
        val x = 10
        val w = (totalWidth - 20).coerceAtLeast(100)

        g.color = UIManager.getColor("Button.background") ?: Color(0x2a2a2a)
        g.fillRoundRect(x, topY + 4, w, h - 8, 8, 8)
        g.color = UIManager.getColor("Component.borderColor") ?: Color(0x3a3a3a)
        g.drawRoundRect(x, topY + 4, w, h - 8, 8, 8)

        g.color = UIManager.getColor("Component.linkColor") ?: Color(0x389FD6)
        g.font = font.deriveFont(Font.BOLD, (font.size2D - 1f).coerceAtLeast(11f))
        val text = strings.loadMoreCards(windowSize, remainingOlderCardsCount)
        val fm = g.fontMetrics
        val textW = fm.stringWidth(text)
        val textX = x + (w - textW) / 2
        val textY = topY + 4 + (h - 8 + fm.ascent - fm.descent) / 2
        g.drawString(text, textX, textY)
    }

    override fun spanAtPoint(point: Point): TranscriptView.SegmentSpan? {
        if (cards.isEmpty()) return null
        if (point.y < 0 || point.y >= heightIndex.bottomOfIndex(cards.lastIndex)) return null
        val idx = heightIndex.cardAt(point.y, clampToNearest = true)
        if (idx !in spans.indices) return null
        return spans[idx]
    }

    override fun hitTest(point: Point): TranscriptView.Hit? {
        if (cards.isEmpty()) return null
        if (hasMoreOlderCards) {
            val loadMoreTop = heightIndex.topOf(LOAD_MORE_ID)
            if (loadMoreTop != null && point.y in loadMoreTop until (loadMoreTop + LOAD_MORE_HEIGHT)) {
                val bounds = Rectangle(10, loadMoreTop + 4, (width - 20).coerceAtLeast(100), LOAD_MORE_HEIGHT - 8)
                if (bounds.contains(point)) return TranscriptView.Hit.LoadMore(bounds)
            }
        }
        val idx = heightIndex.cardAt(point.y, clampToNearest = true)
        if (idx !in cards.indices) return null
        val model = cards[idx]
        val topY = heightIndex.topOfIndex(idx)
        return stamp.hitTestCard(
            model = model,
            cardTopY = topY,
            totalWidth = effectiveWidth(),
            pointInList = point,
            nowMillis = clock(),
            correctedFirstSeenMillis = correctedFirstSeenMillis
        )
    }

    override fun tooltipForHit(hit: TranscriptView.Hit?): String? = when (hit) {
        is TranscriptView.Hit.PlayButton -> strings.actionPlayAudio
        is TranscriptView.Hit.StopButton -> strings.actionStopAudio
        is TranscriptView.Hit.RetryButton -> strings.actionRetrySegment
        is TranscriptView.Hit.TranslationRetry -> strings.autoTranslateFailed
        is TranscriptView.Hit.LoadMore -> strings.loadMoreCards(windowSize, remainingOlderCardsCount)
        is TranscriptView.Hit.DeleteButton -> strings.actionDeleteMessage
        is TranscriptView.Hit.VerificationBadge -> {
            val seg = hit.span.segment
            val pct = seg.agreement?.let { round(it * 100.0).toInt().coerceIn(0, 100) }
            if (pct != null && seg.verificationState != VerificationState.UNVERIFIED) {
                strings.verifiedBadgeTooltip(pct)
            } else {
                strings.unverifiedBadgeTooltip
            }
        }
        is TranscriptView.Hit.Text -> {
            val seg = hit.span.segment
            val word = wordRangeAt(hit.span, hit.offset)?.let { timedWordAt(hit.span, it) }
            if (seg.verificationState == VerificationState.UNVERIFIED || word?.verificationState == VerificationState.UNVERIFIED) {
                strings.unverifiedBadgeTooltip
            } else null
        }
        else -> null
    }

    override fun wordRangeAt(span: TranscriptView.SegmentSpan, offset: Int): IntRange? {
        val entry = cachedEntries[span.segment.id] ?: return null
        val localTextStart = entry.localSpan.textStart
        val localTextEnd = entry.localSpan.textEnd
        val text = entry.renderedText.substring(
            localTextStart.coerceIn(0, entry.renderedText.length),
            localTextEnd.coerceIn(0, entry.renderedText.length)
        )
        val local = offset - span.textStart
        if (local !in text.indices) return null
        val words = BreakIterator.getWordInstance()
        words.setText(text)
        val end = words.following(local)
        val start = words.previous()
        if (start == BreakIterator.DONE || end == BreakIterator.DONE) return null
        if (text.substring(start, end).none { it.isLetterOrDigit() }) return null
        return (span.textStart + start) until (span.textStart + end)
    }

    override fun timedWordAt(span: TranscriptView.SegmentSpan, range: IntRange): TranscriptWord? =
        span.words.firstOrNull { (chars, _) -> chars.first <= range.last && range.first <= chars.last }?.second

    override fun textOf(range: IntRange): String {
        if (range.isEmpty()) return ""
        val span = spans.firstOrNull { range.first in it.playStart..it.textEnd } ?: return ""
        val model = cardById[span.segment.id] ?: return ""
        val entry = cachedEntries[span.segment.id] ?: return ""
        val localStart = (range.first - model.baseGlobalOffset).coerceIn(0, entry.renderedText.length)
        val localEnd = (range.last + 1 - model.baseGlobalOffset).coerceIn(localStart, entry.renderedText.length)
        return entry.renderedText.substring(localStart, localEnd)
    }

    override fun boundsOf(range: IntRange): Rectangle? {
        if (range.isEmpty()) return null
        val span = spans.firstOrNull { range.first in it.playStart..it.textEnd } ?: return null
        val model = cardById[span.segment.id] ?: return null
        val topY = heightIndex.topOf(span.segment.id) ?: return null
        return stamp.boundsOfRangeInCard(
            model = model,
            cardTopY = topY,
            totalWidth = effectiveWidth(),
            globalRange = range,
            nowMillis = clock(),
            correctedFirstSeenMillis = correctedFirstSeenMillis
        )
    }

    override fun updateCursor(hit: TranscriptView.Hit?) {
        cursor = when (hit) {
            is TranscriptView.Hit.PlayButton, is TranscriptView.Hit.StopButton, is TranscriptView.Hit.RetryButton, is TranscriptView.Hit.DeleteButton, is TranscriptView.Hit.TranslationRetry, is TranscriptView.Hit.LoadMore ->
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            is TranscriptView.Hit.Text ->
                Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            else ->
                Cursor.getDefaultCursor()
        }
    }

    override fun setHandCursor(hand: Boolean) {
        cursor = if (hand) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
    }

    override fun highlightSearch(query: String) {
        val trimmed = query.trim()
        searchHighlightsBySegment.clear()
        if (trimmed.isEmpty()) {
            currentSearchHighlights = emptyList()
            repaint()
            return
        }
        val allFound = ArrayList<Pair<String, IntRange>>()
        for (span in spans) {
            val entry = cachedEntries[span.segment.id] ?: continue
            val bodyText = entry.renderedText.substring(
                entry.localSpan.textStart.coerceIn(0, entry.renderedText.length),
                entry.localSpan.textEnd.coerceIn(0, entry.renderedText.length)
            )
            var at = bodyText.indexOf(trimmed, ignoreCase = true)
            val cardRanges = ArrayList<IntRange>()
            while (at >= 0) {
                val r = at until (at + trimmed.length)
                cardRanges += r
                allFound += span.segment.id to r
                at = bodyText.indexOf(trimmed, at + trimmed.length, ignoreCase = true)
            }
            if (cardRanges.isNotEmpty()) {
                searchHighlightsBySegment[span.segment.id] = cardRanges
            }
        }
        currentSearchHighlights = allFound
        repaint()
    }

    override fun cardTopY(segmentId: String): Double? =
        heightIndex.topOf(segmentId)?.toDouble()

    override fun scrollToSegment(segmentId: String) {
        val topY = heightIndex.topOf(segmentId) ?: return
        val scrollPane = (parent?.parent as? JScrollPane) ?: return
        scrollPane.viewport.viewPosition = Point(0, topY)
    }

    override fun renderedText(): String {
        if (cards.isEmpty()) return strings.emptyHint
        return cards.joinToString("\n\n") { model ->
            cachedEntries[model.id]?.renderedText.orEmpty()
        }
    }

    override fun characterAttributesAt(offset: Int): AttributeSet? {
        val span = spans.firstOrNull { offset in it.playStart..it.textEnd } ?: return null
        val model = cardById[span.segment.id] ?: return null
        val localOffset = offset - model.baseGlobalOffset
        return stamp.characterAttributesAt(
            model = model,
            totalWidth = effectiveWidth(),
            localOffset = localOffset,
            nowMillis = clock(),
            correctedFirstSeenMillis = correctedFirstSeenMillis
        )
    }

    override fun correctedHighlightColor(): Color = stamp.correctedHighlightColor()

    // Scrollable implementation
    override fun getPreferredSize(): Dimension {
        val w = effectiveWidth()
        val h = heightIndex.totalHeight.coerceAtLeast(40)
        return Dimension(w, h)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 24

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        (visibleRect.height - 32).coerceAtLeast(24)

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean =
        (parent as? JViewport)?.let { it.height > heightIndex.totalHeight } ?: false

    companion object {
        const val CARDS_PROPERTY = "qtranslate.transcript.cards"
        const val TOP_PADDING = 12
        const val BOTTOM_PADDING = 16
        const val OVERSCAN_CARDS = 2
        const val LOAD_MORE_ID = "__load_more__"
        const val LOAD_MORE_HEIGHT = 38

        /** Returns true when the virtualised card list is enabled (default true; override with `-Dqtranslate.transcript.cards`). */
        fun isCardListEnabled(defaultValue: Boolean = true): Boolean =
            System.getProperty(CARDS_PROPERTY)?.toBooleanStrictOrNull() ?: defaultValue

        /** Factory returning the virtualised [TranscriptListView] transcript surface. */
        fun createSurface(strings: ConversationStrings, defaultCardList: Boolean = true): TranscriptSurface =
            TranscriptListView(strings)
    }
}
