package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.TranscriptWord
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.text.AttributeSet

/**
 * Common contract for the Conversation transcript component, implemented by both the legacy
 * single-document [TranscriptView] and the virtualised card list [TranscriptListView].
 */
interface TranscriptSurface {
    val asComponent: JComponent
        get() = this as JComponent

    var newestFirst: Boolean
    var chatLayout: Boolean
    var rtlLayout: Boolean
    var autoTranslate: Boolean
    var windowSize: Int
    var isProgrammaticScroll: Boolean
    var pointerPosition: Point?
    var clock: () -> Long
    var playingTag: Any?

    val currentSession: ConversationSession?
    val spans: List<TranscriptView.SegmentSpan>

    val selectionStartOffset: Int
    val selectionEndOffset: Int

    val fullRenderCount: Int
    val incrementalRenderCount: Int

    fun render(session: ConversationSession?)
    fun loadMore()
    fun ensureSegmentVisible(segmentId: String)
    fun flushThrottledRender()

    fun captureScrollAnchor(): TranscriptView.ScrollAnchor?
    fun restoreScrollAnchor(anchor: TranscriptView.ScrollAnchor?)

    fun spanAtPoint(point: Point): TranscriptView.SegmentSpan?
    fun hitTest(point: Point): TranscriptView.Hit?
    fun tooltipForHit(hit: TranscriptView.Hit?): String?
    fun updateCursor(hit: TranscriptView.Hit?)
    fun setHandCursor(hand: Boolean)

    fun wordRangeAt(span: TranscriptView.SegmentSpan, offset: Int): IntRange?
    fun timedWordAt(span: TranscriptView.SegmentSpan, range: IntRange): TranscriptWord?
    fun textOf(range: IntRange): String
    fun boundsOf(range: IntRange): Rectangle?

    fun clearSelection()
    fun scrollToSegment(segmentId: String)

    /** Highlights all occurrences of [query] across rendered cards (or clears highlights when blank). */
    fun highlightSearch(query: String)

    /** Active search highlight character ranges `(segmentId, localRangeInSegmentText)`. */
    val searchHighlights: List<Pair<String, IntRange>>

    /** Top Y coordinate (in component space) of the card for [segmentId], or null if not present. */
    fun cardTopY(segmentId: String): Double?

    /** Full rendered text across all cards (or the empty-state hint when no segments exist). */
    fun renderedText(): String

    /** Character attributes at global document offset [offset], used by style verification tests. */
    fun characterAttributesAt(offset: Int): AttributeSet?

    fun cardAlpha(segmentId: String, nowMillis: Long = clock()): Float
    fun checkCorrectionHighlightFade()
    fun correctedHighlightColor(): Color
}

val TranscriptSurface.selectionStart: Int
    get() = selectionStartOffset

val TranscriptSurface.selectionEnd: Int
    get() = selectionEndOffset
