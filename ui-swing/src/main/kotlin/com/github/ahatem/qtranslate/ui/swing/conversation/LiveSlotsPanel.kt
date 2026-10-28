package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.LiveLine
import com.github.ahatem.qtranslate.core.conversation.VerificationState
import com.github.ahatem.qtranslate.core.conversation.formatTimestamp
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.UIManager
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Fixed live area with two permanent slots ("Me" and "Them") positioned at the live edge of the transcript.
 *
 * Each slot has a stable minimum height (2 lines), grows up to a cap (4 lines), and scrolls internally
 * if an utterance is long so incoming live words never push settled archive cards up and down.
 */
class LiveSlotsPanel(private val strings: ConversationStrings) : JPanel(
    MigLayout("fillx, insets 6 12 6 12, gap 8", "[grow,fill,50%][grow,fill,50%]")
) {
    class SpeakerSlot(
        val source: AudioSource,
        private val speakerLabel: String,
        private val strings: ConversationStrings
    ) : JPanel(BorderLayout(0, 2)) {
        val headerLabel = JLabel(speakerLabel.uppercase()).apply {
            font = font.deriveFont(java.awt.Font.BOLD, (font.size2D - 2f).coerceAtLeast(10f))
            foreground = TranscriptView.sourceColor(source)
        }
        val badgeLabel = JLabel().apply {
            font = font.deriveFont(java.awt.Font.BOLD, (font.size2D - 2f).coerceAtLeast(10f))
            foreground = UIManager.getColor("Actions.Green") ?: Color(0x59A869)
            isVisible = false
        }
        val textPane = JTextPane().apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
            font = font.deriveFont(font.size2D + 1f)
        }
        val scrollPane = object : JScrollPane(
            textPane,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        ) {
            private fun slotHeight(): Int {
                val lineHeight = textPane.getFontMetrics(textPane.font).height.coerceAtLeast(16)
                return lineHeight * FIXED_LINES + 8
            }

            override fun getMinimumSize(): Dimension =
                Dimension(120, slotHeight())

            override fun getPreferredSize(): Dimension =
                Dimension(super.getPreferredSize().width.coerceAtLeast(120), slotHeight())

            override fun getMaximumSize(): Dimension =
                Dimension(Int.MAX_VALUE, slotHeight())
        }.apply {
            isOpaque = false
            viewport.isOpaque = false
            border = null
        }

        private var lastRenderedLine: LiveLine? = null

        init {
            val accent = TranscriptView.sourceColor(source)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
                BorderFactory.createEmptyBorder(4, 8, 4, 6)
            )
            val headerRow = JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(headerLabel, BorderLayout.WEST)
                add(badgeLabel, BorderLayout.EAST)
            }
            add(headerRow, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            render(null)
        }

        fun render(line: LiveLine?) {
            if (line == lastRenderedLine && textPane.document.length > 0) return
            lastRenderedLine = line

            val doc = textPane.styledDocument
            doc.remove(0, doc.length)

            if (line == null || line.text.isBlank()) {
                headerLabel.text = speakerLabel.uppercase()
                badgeLabel.isVisible = false
                badgeLabel.toolTipText = null
                return
            }

            headerLabel.text = "${speakerLabel.uppercase()} · ${formatTimestamp(line.startMillis)}"
            val pct = line.agreement?.let { kotlin.math.round(it * 100.0).toInt().coerceIn(0, 100) }
            if (pct != null && line.confirmedText.isNotBlank()) {
                badgeLabel.text = strings.verifiedBadge(pct)
                badgeLabel.toolTipText = strings.verifiedBadgeTooltip(pct)
                badgeLabel.isVisible = true
            } else {
                badgeLabel.isVisible = false
                badgeLabel.toolTipText = null
            }

            val confirmed = line.confirmedText
            val liveTail = line.liveText

            if (confirmed.isNotBlank()) {
                val confirmedAttr = SimpleAttributeSet().apply {
                    StyleConstants.setForeground(
                        this,
                        if (line.verificationState == VerificationState.UNVERIFIED) {
                            UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                        } else {
                            UIManager.getColor("TextPane.foreground") ?: textPane.foreground ?: Color.WHITE
                        }
                    )
                    StyleConstants.setItalic(this, false)
                }
                doc.insertString(doc.length, confirmed, confirmedAttr)
                if (liveTail.isNotBlank()) {
                    doc.insertString(doc.length, " ", null)
                }
            }

            if (liveTail.isNotBlank()) {
                val tailStart = doc.length
                val liveAttr = SimpleAttributeSet().apply {
                    StyleConstants.setForeground(this, UIManager.getColor("Label.disabledForeground") ?: Color.GRAY)
                    StyleConstants.setItalic(this, true)
                }
                doc.insertString(doc.length, liveTail, liveAttr)

                // Underline low-confidence live words (< 0.6)
                var cursor = 0
                line.liveWords.forEach { word ->
                    if (TranscriptView.isLowConfidenceLiveWord(word)) {
                        val needle = word.text.trim { !it.isLetterOrDigit() }
                        if (needle.isNotEmpty()) {
                            val at = liveTail.indexOf(needle, cursor, ignoreCase = true)
                            if (at >= 0) {
                                cursor = at + needle.length
                                val ulAttr = SimpleAttributeSet(liveAttr).apply {
                                    StyleConstants.setUnderline(this, true)
                                }
                                doc.setCharacterAttributes(tailStart + at, needle.length, ulAttr, false)
                            }
                        }
                    }
                }
            }

            textPane.caretPosition = doc.length
        }

        companion object {
            const val FIXED_LINES = 4
            const val MIN_LINES = FIXED_LINES
            const val MAX_LINES = FIXED_LINES
        }
    }

    val micSlot = SpeakerSlot(AudioSource.MICROPHONE, strings.speakerMe, strings)
    val systemSlot = SpeakerSlot(AudioSource.SYSTEM, strings.speakerCall, strings)

    init {
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor") ?: Color.DARK_GRAY)
        add(micSlot, "wmin 120, growx")
        add(systemSlot, "wmin 120, growx")
        isVisible = false
    }

    fun render(live: Map<AudioSource, LiveLine>, visible: Boolean) {
        isVisible = visible
        micSlot.render(live[AudioSource.MICROPHONE])
        systemSlot.render(live[AudioSource.SYSTEM])
        revalidate()
        repaint()
    }

    fun slotFor(source: AudioSource): SpeakerSlot = when (source) {
        AudioSource.MICROPHONE -> micSlot
        AudioSource.SYSTEM -> systemSlot
    }

    fun slotText(source: AudioSource): String {
        val doc = slotFor(source).textPane.styledDocument
        return doc.getText(0, doc.length)
    }
}
