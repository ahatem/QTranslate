package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.TranscriptWord
import com.github.ahatem.qtranslate.core.conversation.VerificationState
import java.awt.Color
import java.awt.Component
import java.awt.Point
import java.awt.Rectangle
import javax.swing.UIManager

/**
 * Shared transcript domain types, hit-testing results, scroll anchor value object, and card geometry/style constants
 * used by [TranscriptSurface], [TranscriptListView], and [TranscriptCardStamp].
 */
object TranscriptView {

    /** Character offset span of a rendered [TranscriptSegment] card. */
    class SegmentSpan(
        val segment: TranscriptSegment,
        val playStart: Int,
        val playEnd: Int,
        val headerStart: Int,
        val headerEnd: Int,
        val retryStart: Int,
        val retryEnd: Int,
        val deleteStart: Int,
        val deleteEnd: Int,
        val textStart: Int,
        val textEnd: Int,
        /** Recognised words with their character ranges, for replaying or translating a single word. */
        val words: List<Pair<IntRange, TranscriptWord>>,
        val badgeStart: Int = headerEnd,
        val badgeEnd: Int = headerEnd,
        val translationStart: Int = textEnd,
        val translationEnd: Int = textEnd,
        val isTranslationRetry: Boolean = false
    )

    sealed interface Hit {
        val span: SegmentSpan

        data class PlayButton(override val span: SegmentSpan) : Hit
        data class StopButton(override val span: SegmentSpan) : Hit
        data class RetryButton(override val span: SegmentSpan) : Hit
        data class DeleteButton(override val span: SegmentSpan) : Hit
        data class VerificationBadge(override val span: SegmentSpan) : Hit
        data class Header(override val span: SegmentSpan) : Hit
        data class Text(override val span: SegmentSpan, val offset: Int) : Hit
        data class TranslationRetry(override val span: SegmentSpan) : Hit
        data class LoadMore(val bounds: Rectangle) : Hit {
            override val span: SegmentSpan get() = throw UnsupportedOperationException("LoadMore has no segment span")
        }
    }

    data class ScrollAnchor(
        val segmentId: String,
        val yOffsetInViewport: Double,
        val yInAncestor: Double = yOffsetInViewport,
        val pointerOffsetInCard: Double? = null
    )

    const val CORRECTION_HIGHLIGHT_MS = 3_000L
    const val RENDER_THROTTLE_MS = 250L
    const val CARD_FADE_IN_MS = 150L
    const val LOW_CONFIDENCE_THRESHOLD = 0.6

    /** True when [word] is in LIVE state and has a reported recognizer confidence below [LOW_CONFIDENCE_THRESHOLD]. */
    fun isLowConfidenceLiveWord(word: TranscriptWord): Boolean =
        word.verificationState == VerificationState.LIVE &&
            word.confidence != null &&
            word.confidence!! < LOW_CONFIDENCE_THRESHOLD

    const val CARD_HORIZONTAL_MARGIN = 10

    /**
     * Horizontal pixel range (`x..(x + w)`) for a card bubble inside [totalWidth].
     * Cards use the full width of the list (10 px margins) in both layouts.
     */
    fun cardHorizontalBounds(
        source: AudioSource,
        totalWidth: Int,
        chatLayout: Boolean = false,
        rtl: Boolean = false
    ): IntRange {
        val safeWidth = totalWidth.coerceAtLeast(140)
        val x = CARD_HORIZONTAL_MARGIN
        val w = (safeWidth - CARD_HORIZONTAL_MARGIN * 2).coerceAtLeast(100)
        return x..(x + w)
    }

    /** One colour per source, from the theme's action palette so it suits light and dark. */
    fun sourceColor(source: AudioSource): Color = when (source) {
        AudioSource.MICROPHONE -> UIManager.getColor("Actions.Blue") ?: Color(0x389FD6)
        AudioSource.SYSTEM -> UIManager.getColor("Actions.Green") ?: Color(0x59A869)
    }
}
