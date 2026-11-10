package com.github.ahatem.qtranslate.ui.swing.conversation

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.TranscriptWord
import com.github.ahatem.qtranslate.core.conversation.VerificationState
import com.github.ahatem.qtranslate.core.conversation.formatTimestamp
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.CellRendererPane
import javax.swing.JComponent
import javax.swing.JTextPane
import javax.swing.UIManager
import javax.swing.text.AttributeSet
import javax.swing.text.DefaultHighlighter
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Immutable view model for a single card in the virtualised transcript card list.
 */
data class CardModel(
    val segment: TranscriptSegment,
    val isLatest: Boolean = false,
    val chatLayout: Boolean = true,
    val rtlLayout: Boolean = false,
    val isPlaying: Boolean = false,
    val autoTranslate: Boolean = false,
    val translation: String? = segment.translation,
    val translationError: String? = segment.translationError,
    val baseGlobalOffset: Int = 0
) {
    val id: String get() = segment.id
    val isLiveDraft: Boolean get() = segment.id.startsWith("live-")

    /**
     * Content version key used by [CardHeightIndex] so a card is only re-measured when its own
     * text, status, badge, or layout flags change.
     */
    fun contentVersion(): Any = ContentKey(
        source = segment.source,
        startMillis = segment.startMillis,
        text = segment.text,
        confirmedText = segment.confirmedText,
        pending = segment.pending,
        failed = segment.failed,
        draft = segment.draft,
        verificationState = segment.verificationState,
        agreement = segment.agreement,
        chatLayout = chatLayout,
        rtlLayout = rtlLayout,
        autoTranslate = autoTranslate,
        translation = translation,
        translationError = translationError
    )

    private data class ContentKey(
        val source: AudioSource,
        val startMillis: Long,
        val text: String,
        val confirmedText: String,
        val pending: Boolean,
        val failed: Boolean,
        val draft: Boolean,
        val verificationState: VerificationState,
        val agreement: Double?,
        val chatLayout: Boolean,
        val rtlLayout: Boolean,
        val autoTranslate: Boolean,
        val translation: String?,
        val translationError: String?
    )
}

/**
 * Reusable single-card "stamp" renderer (backed by [CellRendererPane], like `JList`/`JTable`).
 *
 * Measures, hit-tests, and paints one [CardModel] at a time without creating per-card Swing
 * components in the container hierarchy.
 */
class TranscriptCardStamp(private val strings: ConversationStrings) {

    data class StampLayout(
        val localSpan: TranscriptView.SegmentSpan,
        val globalSpan: TranscriptView.SegmentSpan,
        val cardHeight: Int,
        val horizontalBounds: IntRange,
        val paragraphAlignment: Int,
        val anyActiveCorrectionHighlight: Boolean,
        val renderedText: String
    )

    val rendererPane = CellRendererPane()
    internal val stampPane = JTextPane().apply {
        isEditable = false
        isOpaque = false
        componentOrientation = ComponentOrientation.LEFT_TO_RIGHT
        border = BorderFactory.createEmptyBorder(CARD_INNER_TOP_PAD, 16, CARD_INNER_BOTTOM_PAD, 16)
        font = font.deriveFont(font.size2D + 2f)
    }

    init {
        rendererPane.add(stampPane)
    }

    /** Current playback tag being played, or null. Used by stamp to render stop button on active line. */
    var playingTag: Any? = null

    internal fun isSegmentPlaying(segmentId: String): Boolean {
        val tag = playingTag ?: return false
        return when (tag) {
            is String -> tag == segmentId
            is com.github.ahatem.qtranslate.core.conversation.PlaybackTag -> tag.segmentId == segmentId
            else -> tag.toString() == segmentId
        }
    }

    /** Number of card measurements executed since creation or [resetCounters]. */
    var measureCount: Int = 0
        private set

    /** Number of individual card paint calls executed since creation or [resetCounters]. */
    var paintCount: Int = 0
        private set

    /** Number of cards painted during the most recent `TranscriptListView.paintComponent` call. */
    var lastPaintCardCount: Int = 0
        internal set

    fun resetCounters() {
        measureCount = 0
        paintCount = 0
        lastPaintCardCount = 0
    }

    /**
     * Measures [model] at [totalWidth] and returns its [StampLayout] (including `cardHeight` and
     * both card-local and global [TranscriptView.SegmentSpan]s).
     */
    fun measureCard(
        model: CardModel,
        totalWidth: Int,
        nowMillis: Long = System.currentTimeMillis(),
        correctedFirstSeenMillis: MutableMap<String, Long> = mutableMapOf()
    ): StampLayout {
        measureCount++
        return populateAndLayout(model, totalWidth, nowMillis, correctedFirstSeenMillis)
    }

    /**
     * Configures the stamp for [model] at [totalWidth] without incrementing [measureCount]
     * (used during painting and hit-testing of already-indexed visible cards).
     */
    internal fun configureForCard(
        model: CardModel,
        totalWidth: Int,
        nowMillis: Long,
        correctedFirstSeenMillis: MutableMap<String, Long>,
        selectionLocalRange: IntRange? = null,
        searchLocalRanges: List<IntRange> = emptyList()
    ): StampLayout {
        val layout = populateAndLayout(model, totalWidth, nowMillis, correctedFirstSeenMillis)
        applyHighlights(layout.localSpan, selectionLocalRange, searchLocalRanges)
        return layout
    }

    /**
     * Paints [model] at vertical position [cardTopY] with height [cardHeight] inside [host].
     */
    fun paintCard(
        g: Graphics2D,
        host: JComponent,
        model: CardModel,
        cardTopY: Int,
        totalWidth: Int,
        cardHeight: Int,
        alpha: Float = 1f,
        nowMillis: Long = System.currentTimeMillis(),
        correctedFirstSeenMillis: MutableMap<String, Long> = mutableMapOf(),
        selectionLocalRange: IntRange? = null,
        searchLocalRanges: List<IntRange> = emptyList()
    ): StampLayout {
        paintCount++
        val safeWidth = totalWidth.coerceAtLeast(140)
        val layout = configureForCard(
            model = model,
            totalWidth = safeWidth,
            nowMillis = nowMillis,
            correctedFirstSeenMillis = correctedFirstSeenMillis,
            selectionLocalRange = selectionLocalRange,
            searchLocalRanges = searchLocalRanges
        )

        val bg = UIManager.getColor("TextPane.background") ?: host.background ?: Color(0x1e1e1e)
        val dark = isDark(bg)
        val defaultCardBg = if (dark) {
            Color((bg.red + 14).coerceAtMost(255), (bg.green + 14).coerceAtMost(255), (bg.blue + 16).coerceAtMost(255))
        } else {
            Color((bg.red - 8).coerceAtLeast(0), (bg.green - 8).coerceAtLeast(0), (bg.blue - 8).coerceAtLeast(0))
        }
        val defaultBorderColor = if (dark) Color(255, 255, 255, 22) else Color(0, 0, 0, 20)
        val latestCardBg = if (dark) Color(46, 38, 18) else Color(255, 248, 205)
        val latestBorderColor = if (dark) Color(245, 190, 45, 220) else Color(215, 160, 20, 220)

        val gCard = g.create() as Graphics2D
        try {
            gCard.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (alpha < 1f) {
                gCard.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0.15f, 1f))
            }

            val cardBg = if (model.isLatest) latestCardBg else defaultCardBg
            val borderColor = if (model.isLatest) latestBorderColor else defaultBorderColor
            val hBounds = layout.horizontalBounds
            val x = hBounds.first
            val y = cardTopY + 2
            val w = (hBounds.last - hBounds.first).coerceAtLeast(100)
            val h = (cardHeight - CARD_VERTICAL_GAP).coerceAtLeast(28)

            // Rounded card bubble
            gCard.color = cardBg
            gCard.fillRoundRect(x, y, w, h, 10, 10)

            // Accent stripe (left in LTR for Me, right in LTR for Them; mirrored in RTL)
            val isLeftStripe = isStripeOnLeft(model.segment.source, model.chatLayout, model.rtlLayout)
            val stripeX = if (isLeftStripe) x else x + w - 4
            gCard.color = if (model.isLatest) Color(245, 175, 35) else TranscriptView.sourceColor(model.segment.source)
            gCard.fillRoundRect(stripeX, y, 4, h, 4, 4)

            // Subtle card border
            gCard.color = borderColor
            gCard.drawRoundRect(x, y, w, h, 10, 10)

            // Stamp the text and icons into the card's vertical slot
            rendererPane.paintComponent(gCard, stampPane, host, 0, cardTopY, safeWidth, h, true)

            // Dotted underline under UNVERIFIED words or unverified settled segment body
            val localSpan = layout.localSpan
            gCard.color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            gCard.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(2f, 2f), 0f)
            if (localSpan.words.isNotEmpty()) {
                localSpan.words.forEach { (range, word) ->
                    if (word.verificationState == VerificationState.UNVERIFIED) {
                        localBoundsOf(range)?.let { box ->
                            val yLine = cardTopY + box.y + box.height - 2
                            gCard.drawLine(box.x, yLine, box.x + box.width, yLine)
                        }
                    }
                }
            } else if (!localSpan.segment.draft &&
                localSpan.segment.verificationState == VerificationState.UNVERIFIED &&
                localSpan.textEnd > localSpan.textStart
            ) {
                localBoundsOf(localSpan.textStart until localSpan.textEnd)?.let { box ->
                    val yLine = cardTopY + box.y + box.height - 2
                    gCard.drawLine(box.x, yLine, box.x + box.width, yLine)
                }
            }
        } finally {
            gCard.dispose()
        }
        return layout
    }

    /**
     * Hit-tests [pointInList] against [model] positioned at [cardTopY], returning a [TranscriptView.Hit]
     * whose span and text offset use [CardModel.baseGlobalOffset].
     */
    fun hitTestCard(
        model: CardModel,
        cardTopY: Int,
        totalWidth: Int,
        pointInList: Point,
        nowMillis: Long = System.currentTimeMillis(),
        correctedFirstSeenMillis: MutableMap<String, Long> = mutableMapOf()
    ): TranscriptView.Hit? {
        val layout = configureForCard(model, totalWidth.coerceAtLeast(140), nowMillis, correctedFirstSeenMillis)
        val localPoint = Point(pointInList.x, pointInList.y - cardTopY)
        if (localPoint.y < 0 || localPoint.y > layout.cardHeight) return null

        val localSpan = layout.localSpan
        val globalSpan = layout.globalSpan
        val playing = model.isPlaying || isSegmentPlaying(model.id)

        val playBox = localBoundsOf(localSpan.playStart until localSpan.playEnd)
        if (playBox != null && playBox.grow(4).contains(localPoint)) {
            return if (playing) TranscriptView.Hit.StopButton(globalSpan) else TranscriptView.Hit.PlayButton(globalSpan)
        }

        if (localSpan.badgeEnd > localSpan.badgeStart) {
            val badgeBox = localBoundsOf(localSpan.badgeStart until localSpan.badgeEnd)
            if (badgeBox != null && badgeBox.grow(4).contains(localPoint)) return TranscriptView.Hit.VerificationBadge(globalSpan)
        }

        if (localSpan.retryEnd > localSpan.retryStart) {
            val retryBox = localBoundsOf(localSpan.retryStart until localSpan.retryEnd)
            if (retryBox != null && retryBox.grow(4).contains(localPoint)) return TranscriptView.Hit.RetryButton(globalSpan)
        }

        if (localSpan.deleteEnd > localSpan.deleteStart) {
            val deleteBox = localBoundsOf(localSpan.deleteStart until localSpan.deleteEnd)
            if (deleteBox != null && deleteBox.grow(4).contains(localPoint)) return TranscriptView.Hit.DeleteButton(globalSpan)
        }

        if (localSpan.isTranslationRetry && localSpan.translationEnd > localSpan.translationStart) {
            val transBox = localBoundsOf(localSpan.translationStart until localSpan.translationEnd)
            if (transBox != null && transBox.grow(4).contains(localPoint)) return TranscriptView.Hit.TranslationRetry(globalSpan)
        }

        val docLen = stampPane.document.length
        if (docLen <= 0) return null
        val localOffset = stampPane.viewToModel2D(localPoint).takeIf { it in 0 until docLen } ?: return null

        val box = stampPane.modelToView2D(localOffset) ?: return null
        val next = stampPane.modelToView2D((localOffset + 1).coerceAtMost(docLen))
        val right = if (next != null && next.y == box.y) next.x else box.x + 12
        if (localPoint.x < minOf(box.x, right) - 6 || localPoint.x > maxOf(box.x, right) + 6) return null

        return when {
            localOffset in localSpan.playStart until localSpan.playEnd ->
                if (playing) TranscriptView.Hit.StopButton(globalSpan) else TranscriptView.Hit.PlayButton(globalSpan)
            localOffset in localSpan.badgeStart until localSpan.badgeEnd -> TranscriptView.Hit.VerificationBadge(globalSpan)
            localOffset in localSpan.retryStart until localSpan.retryEnd -> TranscriptView.Hit.RetryButton(globalSpan)
            localOffset in localSpan.deleteStart until localSpan.deleteEnd -> TranscriptView.Hit.DeleteButton(globalSpan)
            localSpan.isTranslationRetry && localOffset in localSpan.translationStart until localSpan.translationEnd ->
                TranscriptView.Hit.TranslationRetry(globalSpan)
            localOffset < localSpan.headerEnd -> TranscriptView.Hit.Header(globalSpan)
            else -> TranscriptView.Hit.Text(globalSpan, model.baseGlobalOffset + localOffset)
        }
    }

    /**
     * Computes the bounding rectangle (in list component coordinates, shifted by [cardTopY]) of
     * [globalRange] inside [model].
     */
    fun boundsOfRangeInCard(
        model: CardModel,
        cardTopY: Int,
        totalWidth: Int,
        globalRange: IntRange,
        nowMillis: Long = System.currentTimeMillis(),
        correctedFirstSeenMillis: MutableMap<String, Long> = mutableMapOf()
    ): Rectangle? {
        configureForCard(model, totalWidth.coerceAtLeast(140), nowMillis, correctedFirstSeenMillis)
        val docLen = stampPane.document.length
        val localFirst = (globalRange.first - model.baseGlobalOffset).coerceIn(0, docLen)
        val localLast = (globalRange.last - model.baseGlobalOffset).coerceIn(localFirst, docLen)
        val rect = localBoundsOf(localFirst..localLast) ?: return null
        return Rectangle(rect.x, cardTopY + rect.y, rect.width, rect.height)
    }

    /**
     * Maps [pointInList] to a global character offset clamped to [model]'s spoken text range
     * (`globalSpan.textStart..globalSpan.textEnd`), used during mouse drag selection.
     */
    fun textOffsetAtPoint(
        model: CardModel,
        cardTopY: Int,
        totalWidth: Int,
        pointInList: Point,
        nowMillis: Long = System.currentTimeMillis(),
        correctedFirstSeenMillis: MutableMap<String, Long> = mutableMapOf()
    ): Int {
        val layout = configureForCard(model, totalWidth.coerceAtLeast(140), nowMillis, correctedFirstSeenMillis)
        val localY = (pointInList.y - cardTopY).coerceIn(0, (layout.cardHeight - 1).coerceAtLeast(0))
        val localOffset = stampPane.viewToModel2D(Point(pointInList.x, localY))
        val clampedLocal = localOffset.coerceIn(layout.localSpan.textStart, layout.localSpan.textEnd)
        return model.baseGlobalOffset + clampedLocal
    }

    /** Returns the character attributes at [localOffset] after configuring the stamp for [model]. */
    fun characterAttributesAt(
        model: CardModel,
        totalWidth: Int,
        localOffset: Int,
        nowMillis: Long = System.currentTimeMillis(),
        correctedFirstSeenMillis: MutableMap<String, Long> = mutableMapOf()
    ): AttributeSet? {
        configureForCard(model, totalWidth.coerceAtLeast(140), nowMillis, correctedFirstSeenMillis)
        val doc = stampPane.styledDocument
        if (localOffset !in 0..doc.length) return null
        return doc.getCharacterElement(localOffset).attributes
    }

    private fun populateAndLayout(
        model: CardModel,
        totalWidth: Int,
        nowMillis: Long,
        correctedFirstSeenMillis: MutableMap<String, Long>
    ): StampLayout {
        val safeWidth = totalWidth.coerceAtLeast(140)
        val segment = model.segment
        val doc = stampPane.styledDocument
        stampPane.highlighter.removeAllHighlights()
        if (doc.length > 0) {
            doc.remove(0, doc.length)
        }

        val playStart = 0
        val iconSize = (stampPane.font.size - 2).coerceIn(12, 16)
        val playing = model.isPlaying || isSegmentPlaying(segment.id)
        val playIcon = FlatSVGIcon(if (playing) Icons.STOP else Icons.PLAY, iconSize, iconSize).apply {
            colorFilter = FlatSVGIcon.ColorFilter { TranscriptView.sourceColor(segment.source) }
        }
        doc.insertString(doc.length, " ", SimpleAttributeSet().apply { StyleConstants.setIcon(this, playIcon) })
        doc.insertString(doc.length, " ", null)
        val playEnd = doc.length

        val headerStart = doc.length
        val speaker = if (segment.source == AudioSource.MICROPHONE) strings.speakerMe else strings.speakerCall
        doc.insertString(
            doc.length,
            "${speaker.uppercase()} · ${formatTimestamp(segment.startMillis)}",
            headerStyle(segment.source)
        )
        val headerEnd = doc.length

        var badgeStart = doc.length
        var badgeEnd = badgeStart
        val agreementPct = segment.agreement?.let { kotlin.math.round(it * 100.0).toInt().coerceIn(0, 100) }
        if (agreementPct != null && (
                segment.verificationState == VerificationState.VERIFIED ||
                    segment.verificationState == VerificationState.CORRECTED ||
                    segment.confirmedText.isNotBlank()
                )
        ) {
            doc.insertString(doc.length, "  ", null)
            badgeStart = doc.length
            doc.insertString(doc.length, strings.verifiedBadge(agreementPct), verifiedBadgeStyle(agreementPct))
            badgeEnd = doc.length
        } else if (!segment.draft && segment.verificationState == VerificationState.UNVERIFIED) {
            doc.insertString(doc.length, "  ", null)
            badgeStart = doc.length
            doc.insertString(doc.length, strings.unverifiedBadge, unverifiedBadgeStyle())
            badgeEnd = doc.length
        }

        var retryStart = doc.length
        var retryEnd = retryStart
        var deleteStart = doc.length
        var deleteEnd = deleteStart

        if (!model.isLiveDraft) {
            doc.insertString(doc.length, "    ", null)
            retryStart = doc.length
            val retryIcon = FlatSVGIcon(Icons.RETRY, iconSize, iconSize).apply {
                colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Actions.Blue") ?: Color(0x389FD6) }
            }
            doc.insertString(doc.length, " ", SimpleAttributeSet().apply { StyleConstants.setIcon(this, retryIcon) })
            doc.insertString(doc.length, " ", null)
            retryEnd = doc.length

            deleteStart = doc.length
            val deleteIcon = FlatSVGIcon(Icons.DELETE, iconSize, iconSize).apply {
                colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Actions.Red") ?: Color(0xE05555) }
            }
            doc.insertString(doc.length, " ", SimpleAttributeSet().apply { StyleConstants.setIcon(this, deleteIcon) })
            doc.insertString(doc.length, " ", null)
            deleteEnd = doc.length
        }
        doc.insertString(doc.length, "\n", null)

        val textStart = doc.length
        val (localWords, anyHighlight) = insertBody(doc, segment, textStart, nowMillis, correctedFirstSeenMillis)
        val textEnd = doc.length

        val alignment = alignParagraph(doc, playStart, textEnd - playStart, segment.text, segment.source, safeWidth, model.chatLayout, model.rtlLayout)

        var translationStart = doc.length
        var translationEnd = translationStart
        var isTranslationRetry = false

        if (model.autoTranslate && !model.isLiveDraft && !segment.draft && segment.text.isNotBlank()) {
            doc.insertString(doc.length, "\n", null)
            translationStart = doc.length
            val transText = when {
                model.translation != null -> model.translation
                model.translationError != null -> {
                    isTranslationRetry = true
                    strings.autoTranslateFailed
                }
                else -> "…"
            }
            val transStyle = if (isTranslationRetry) linkStyle() else translationStyle()
            doc.insertString(doc.length, transText, transStyle)
            translationEnd = doc.length
            alignParagraph(doc, translationStart, translationEnd - translationStart, transText, segment.source, safeWidth, model.chatLayout, model.rtlLayout)
        }

        stampPane.setSize(safeWidth, Int.MAX_VALUE / 4)
        stampPane.doLayout()
        val pref = stampPane.preferredSize
        val bottomBox = stampPane.modelToView2D(doc.length)
        val contentHeight = if (bottomBox != null) {
            (bottomBox.y + bottomBox.height).toInt() + CARD_INNER_BOTTOM_PAD
        } else {
            pref.height
        }
        val paneHeight = maxOf(pref.height, contentHeight).coerceAtLeast(32)
        stampPane.setSize(safeWidth, paneHeight)
        stampPane.doLayout()

        val cardHeight = paneHeight + CARD_VERTICAL_GAP
        val base = model.baseGlobalOffset
        val localSpan = TranscriptView.SegmentSpan(
            segment = segment,
            playStart = playStart,
            playEnd = playEnd,
            headerStart = headerStart,
            headerEnd = headerEnd,
            retryStart = retryStart,
            retryEnd = retryEnd,
            deleteStart = deleteStart,
            deleteEnd = deleteEnd,
            textStart = textStart,
            textEnd = textEnd,
            words = localWords,
            badgeStart = badgeStart,
            badgeEnd = badgeEnd,
            translationStart = translationStart,
            translationEnd = translationEnd,
            isTranslationRetry = isTranslationRetry
        )
        val globalSpan = if (base == 0) {
            localSpan
        } else {
            TranscriptView.SegmentSpan(
                segment = segment,
                playStart = base + playStart,
                playEnd = base + playEnd,
                headerStart = base + headerStart,
                headerEnd = base + headerEnd,
                retryStart = base + retryStart,
                retryEnd = base + retryEnd,
                deleteStart = base + deleteStart,
                deleteEnd = base + deleteEnd,
                textStart = base + textStart,
                textEnd = base + textEnd,
                words = localWords.map { (r, w) -> ((base + r.first)..(base + r.last)) to w },
                badgeStart = base + badgeStart,
                badgeEnd = base + badgeEnd,
                translationStart = base + translationStart,
                translationEnd = base + translationEnd,
                isTranslationRetry = isTranslationRetry
            )
        }
        val hBounds = TranscriptView.cardHorizontalBounds(segment.source, safeWidth, model.chatLayout, model.rtlLayout)
        val rendered = doc.getText(0, doc.length)
        return StampLayout(
            localSpan = localSpan,
            globalSpan = globalSpan,
            cardHeight = cardHeight,
            horizontalBounds = hBounds,
            paragraphAlignment = alignment,
            anyActiveCorrectionHighlight = anyHighlight,
            renderedText = rendered
        )
    }

    private fun applyHighlights(
        localSpan: TranscriptView.SegmentSpan,
        selectionLocalRange: IntRange?,
        searchLocalRanges: List<IntRange>
    ) {
        val hl = stampPane.highlighter
        hl.removeAllHighlights()
        if (searchLocalRanges.isNotEmpty()) {
            val searchPainter = DefaultHighlighter.DefaultHighlightPainter(
                UIManager.getColor("Actions.Yellow")?.let { Color(it.red, it.green, it.blue, 90) }
                    ?: Color(255, 220, 0, 90)
            )
            for (r in searchLocalRanges) {
                val start = (localSpan.textStart + r.first).coerceIn(localSpan.textStart, localSpan.textEnd)
                val end = (localSpan.textStart + r.last + 1).coerceIn(start, localSpan.textEnd)
                if (end > start) {
                    hl.addHighlight(start, end, searchPainter)
                }
            }
        }
        if (selectionLocalRange != null && !selectionLocalRange.isEmpty()) {
            val selColor = UIManager.getColor("TextPane.selectionBackground")
                ?: UIManager.getColor("Component.focusColor")
                ?: Color(0x2675BF)
            val selPainter = DefaultHighlighter.DefaultHighlightPainter(
                Color(selColor.red, selColor.green, selColor.blue, 110)
            )
            val start = selectionLocalRange.first.coerceIn(localSpan.textStart, localSpan.textEnd)
            val end = (selectionLocalRange.last + 1).coerceIn(start, localSpan.textEnd)
            if (end > start) {
                hl.addHighlight(start, end, selPainter)
            }
        }
    }

    private fun insertBody(
        doc: StyledDocument,
        segment: TranscriptSegment,
        textStart: Int,
        now: Long,
        correctedFirstSeenMillis: MutableMap<String, Long>
    ): Pair<List<Pair<IntRange, TranscriptWord>>, Boolean> {
        var cursor = doc.length
        fun insert(str: String, attrs: SimpleAttributeSet?) {
            doc.insertString(cursor, str, attrs)
            cursor += str.length
        }
        when {
            segment.pending -> insert(strings.transcribing, mutedStyle(italic = true))
            segment.failed -> insert(strings.failedRetry, linkStyle())
            segment.draft -> {
                val confirmed = segment.confirmedText
                val liveTail = when {
                    confirmed.isBlank() -> segment.text
                    segment.text.startsWith(confirmed, ignoreCase = true) -> segment.text.substring(confirmed.length).trimStart()
                    else -> segment.text
                }
                if (confirmed.isNotBlank()) {
                    val confirmedAttr = when (segment.verificationState) {
                        VerificationState.UNVERIFIED -> mutedStyle(italic = false)
                        else -> bodyStyle()
                    }
                    insert(confirmed, confirmedAttr)
                    if (liveTail.isNotBlank()) {
                        insert(" ", null)
                    }
                }
                if (liveTail.isNotBlank()) {
                    insert(liveTail, mutedStyle(italic = true))
                }
            }
            else -> {
                val baseAttr = when (segment.verificationState) {
                    VerificationState.LIVE -> mutedStyle(italic = true)
                    VerificationState.UNVERIFIED -> mutedStyle(italic = false)
                    VerificationState.VERIFIED, VerificationState.CORRECTED -> bodyStyle()
                }
                insert(segment.text, baseAttr)
            }
        }
        val textEnd = cursor
        val wordSpans = if (segment.pending || segment.failed) emptyList() else locateWords(segment, textStart)
        var anyActiveCorrectionHighlight = false
        wordSpans.forEach { (range, word) ->
            val len = range.last - range.first + 1
            if (len <= 0) return@forEach
            when (word.verificationState) {
                VerificationState.CORRECTED -> {
                    val key = "${segment.id}:${range.first - textStart}:${word.text}"
                    val firstSeen = correctedFirstSeenMillis.getOrPut(key) { now }
                    if (now - firstSeen < TranscriptView.CORRECTION_HIGHLIGHT_MS) {
                        doc.setCharacterAttributes(range.first, len, correctedStyle(), false)
                        anyActiveCorrectionHighlight = true
                    } else {
                        doc.setCharacterAttributes(range.first, len, bodyStyle(), false)
                    }
                }
                VerificationState.UNVERIFIED -> {
                    doc.setCharacterAttributes(range.first, len, mutedStyle(italic = false), false)
                }
                VerificationState.LIVE -> {
                    val attr = mutedStyle(italic = true)
                    if (TranscriptView.isLowConfidenceLiveWord(word)) {
                        StyleConstants.setUnderline(attr, true)
                    }
                    doc.setCharacterAttributes(range.first, len, attr, false)
                }
                VerificationState.VERIFIED -> {
                    doc.setCharacterAttributes(range.first, len, bodyStyle(), false)
                }
            }
        }
        if (wordSpans.isEmpty() && segment.verificationState == VerificationState.CORRECTED && textEnd > textStart) {
            val key = "${segment.id}:segment"
            val firstSeen = correctedFirstSeenMillis.getOrPut(key) { now }
            if (now - firstSeen < TranscriptView.CORRECTION_HIGHLIGHT_MS) {
                doc.setCharacterAttributes(textStart, textEnd - textStart, correctedStyle(), false)
                anyActiveCorrectionHighlight = true
            }
        }
        return wordSpans to anyActiveCorrectionHighlight
    }

    private fun locateWords(segment: TranscriptSegment, textStart: Int): List<Pair<IntRange, TranscriptWord>> {
        var cursor = 0
        return segment.words.mapNotNull { word ->
            val needle = word.text.trim { !it.isLetterOrDigit() }
            if (needle.isEmpty()) return@mapNotNull null
            val at = segment.text.indexOf(needle, cursor, ignoreCase = true)
            if (at < 0) return@mapNotNull null
            cursor = at + needle.length
            (textStart + at until textStart + at + needle.length) to word
        }
    }

    private fun isStripeOnLeft(source: AudioSource, chatLayout: Boolean, rtlLayout: Boolean): Boolean {
        if (!chatLayout) return true
        val isMe = source == AudioSource.MICROPHONE
        return if (!rtlLayout) isMe else !isMe
    }

    private fun alignParagraph(
        doc: StyledDocument,
        start: Int,
        length: Int,
        text: String,
        source: AudioSource,
        totalWidth: Int,
        chatLayout: Boolean,
        rtlLayout: Boolean
    ): Int {
        val rtl = isRtlText(text)
        val alignment = if (rtl) StyleConstants.ALIGN_RIGHT else StyleConstants.ALIGN_LEFT
        val attributes = SimpleAttributeSet().apply {
            StyleConstants.setAlignment(this, alignment)
            StyleConstants.setSpaceBelow(this, 3f)
        }
        doc.setParagraphAttributes(start, length.coerceAtLeast(1), attributes, false)
        return alignment
    }

    private fun localBoundsOf(range: IntRange): Rectangle? {
        val docLen = stampPane.document.length
        val first = range.first.coerceIn(0, docLen)
        val endOffset = (range.last + 1).coerceIn(first, docLen)
        val startBox = stampPane.modelToView2D(first)?.bounds ?: return null
        val endBox = stampPane.modelToView2D(endOffset)?.bounds ?: return startBox
        return if (startBox.y == endBox.y) {
            Rectangle(startBox.x, startBox.y, (endBox.x - startBox.x).coerceAtLeast(4), startBox.height)
        } else {
            startBox
        }
    }

    fun correctedHighlightColor(): Color {
        val bg = UIManager.getColor("TextPane.background") ?: stampPane.background ?: Color(0x1e1e1e)
        return if (isDark(bg)) Color(85, 70, 22) else Color(255, 240, 175)
    }

    private fun isDark(color: Color): Boolean =
        (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) < 128

    private fun Rectangle.grow(pad: Int): Rectangle =
        Rectangle(x - pad, y - pad, width + pad * 2, height + pad * 2)

    private fun bodyStyle() = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, UIManager.getColor("TextPane.foreground") ?: stampPane.foreground)
        StyleConstants.setItalic(this, false)
    }

    private fun correctedStyle() = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, UIManager.getColor("TextPane.foreground") ?: stampPane.foreground)
        StyleConstants.setItalic(this, false)
        StyleConstants.setBackground(this, correctedHighlightColor())
    }

    private fun verifiedBadgeStyle(agreementPct: Int?) = SimpleAttributeSet().apply {
        StyleConstants.setBold(this, true)
        StyleConstants.setFontSize(this, (stampPane.font.size - 3).coerceAtLeast(10))
        val color = if (agreementPct != null && agreementPct < 60) {
            UIManager.getColor("Actions.Yellow") ?: Color(0xE09B2D)
        } else {
            UIManager.getColor("Actions.Green") ?: Color(0x59A869)
        }
        StyleConstants.setForeground(this, color)
    }

    private fun unverifiedBadgeStyle() = SimpleAttributeSet().apply {
        StyleConstants.setItalic(this, false)
        StyleConstants.setFontSize(this, (stampPane.font.size - 3).coerceAtLeast(10))
        StyleConstants.setForeground(this, UIManager.getColor("Label.disabledForeground") ?: Color.GRAY)
    }

    private fun mutedStyle(italic: Boolean) = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, UIManager.getColor("Label.disabledForeground") ?: Color.GRAY)
        StyleConstants.setItalic(this, italic)
    }

    private fun translationStyle() = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, UIManager.getColor("Label.disabledForeground") ?: Color.GRAY)
        StyleConstants.setFontSize(this, (stampPane.font.size - 2).coerceAtLeast(11))
    }

    private fun linkStyle() = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, UIManager.getColor("Component.linkColor") ?: Color(0x2675BF))
        StyleConstants.setUnderline(this, true)
    }

    private fun headerStyle(source: AudioSource) = SimpleAttributeSet().apply {
        StyleConstants.setBold(this, true)
        StyleConstants.setFontSize(this, (stampPane.font.size - 3).coerceAtLeast(10))
        StyleConstants.setForeground(this, TranscriptView.sourceColor(source))
    }

    companion object {
        const val CARD_INNER_TOP_PAD = 8
        const val CARD_INNER_BOTTOM_PAD = 8
        const val CARD_VERTICAL_GAP = 8

        fun isRtlText(text: String): Boolean =
            text.firstOrNull { Character.isLetter(it) }?.let {
                Character.getDirectionality(it) in setOf(
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                )
            } ?: false
    }
}
