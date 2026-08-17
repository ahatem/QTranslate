package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import java.awt.*
import javax.swing.*
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

/**
 * Scrollable panel that renders dictionary entries using native FlatLaf Swing components.
 * Implements [Scrollable] so that the content tracks the viewport width, enabling
 * JTextArea line-wrapping without a fixed pixel width.
 */
class DictionaryResultView(private val iconManager: IconManager) : JScrollPane() {

    /**
     * The speaker beside the headword, kept so playback state can be reflected without rebuilding.
     *
     * A rebuild would clear the panel for a frame and reset the scroll position, which is a poor
     * trade for swapping one icon — and it would happen every time audio started or stopped.
     */
    private var listenButton: JButton? = null

    private var headword: String = ""
    private var isSpeaking = false
    private var listenTooltip = ""
    private var stopTooltip = ""
    private var onListen: ((String) -> Unit)? = null
    private var onStopListening: (() -> Unit)? = null

    private val content = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(12, 16, 16, 16)
    }

    // Cache the last rendered data. Both DictionaryPanel (embedded) and
    // QuickDictionaryDialog use this view, and their parents call render()
    // on every state emission even when the entries haven't changed.
    // removeAll() + full rebuild causes the panel to flash blank for one
    // frame before repainting — the equality guard prevents that entirely.
    private var lastEntries: List<DictionaryEntry> = emptyList()
    private var lastSynonymsLabel: String = ""

    init {
        setViewportView(content)
        clearBorder()
        horizontalScrollBarPolicy = HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = VERTICAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.unitIncrement = 16
    }

    fun render(
        entries: List<DictionaryEntry>,
        synonymsLabel: String,
        onSynonymClicked: ((String) -> Unit)? = null,
        listenTooltip: String = "",
        stopTooltip: String = "",
        onListen: ((String) -> Unit)? = null,
        onStopListening: (() -> Unit)? = null
    ) {
        // Assigned before the early return, so a caller that re-renders with the same entries but
        // fresh callbacks does not leave the speaker wired to the previous ones.
        this.listenTooltip = listenTooltip
        this.stopTooltip = stopTooltip
        this.onListen = onListen
        this.onStopListening = onStopListening

        if (entries == lastEntries && synonymsLabel == lastSynonymsLabel) return
        lastEntries = entries
        lastSynonymsLabel = synonymsLabel
        content.removeAll()
        listenButton = null

        if (entries.isEmpty()) {
            content.revalidate()
            content.repaint()
            return
        }

        val first = entries.first()
        headword = first.word

        addRow(headwordRow(first.word))
        addGap(4)

        first.phonetic?.let {
            addRow(muteLabel(it))
            addGap(12)
        } ?: addGap(8)

        entries.forEach { entry ->
            addRow(posDivider(entry.partOfSpeech))
            addGap(8)

            entry.definitions.forEachIndexed { i, def ->
                addRow(definitionRow(i + 1, def.text))
                def.example?.let { ex ->
                    addGap(2)
                    addRow(exampleRow(ex))
                }
                addGap(6)
            }

            if (entry.synonyms.isNotEmpty()) {
                addGap(2)
                addRow(synonymsRow(synonymsLabel, entry.synonyms, onSynonymClicked))
                addGap(10)
            }
        }

        content.revalidate()
        content.repaint()
        SwingUtilities.invokeLater { verticalScrollBar.value = 0 }
    }

    private fun addRow(c: JComponent) {
        c.alignmentX = Component.LEFT_ALIGNMENT
        content.add(c)
    }

    private fun addGap(h: Int) { content.add(Box.createVerticalStrut(h)) }

    private fun wordLabel(text: String): JLabel = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD, font.size + 9f)
    }

    /**
     * The headword, followed by a speaker when the caller supplied a listen callback.
     *
     * The button sits on the baseline row rather than in the toolbar because it belongs to the
     * word, not to the window: the standalone dictionary dialog has no toolbar of its own.
     */
    private fun headwordRow(word: String): JComponent {
        val label = wordLabel(word)
        val listen = onListen ?: return label

        val button = createButtonWithIcon(iconManager, LISTEN_ICON, LISTEN_ICON_SIZE).apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            isFocusable = false
            addActionListener {
                if (isSpeaking) onStopListening?.invoke() else listen(headword)
            }
        }
        listenButton = button
        applySpeakingState()

        return JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0)).apply {
            isOpaque = false
            // BoxLayout hands a row its maximum height, which would stretch a FlowLayout panel
            // and float the word away from the definitions below it.
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            add(label)
            add(button)
        }
    }

    /**
     * Reflects playback in the speaker without rebuilding the entry list.
     *
     * Safe to call before anything is rendered; it does nothing until a headword exists.
     */
    fun setSpeaking(speaking: Boolean) {
        if (speaking == isSpeaking) return
        isSpeaking = speaking
        applySpeakingState()
    }

    private fun applySpeakingState() {
        val button = listenButton ?: return
        val iconPath = if (isSpeaking) STOP_ICON else LISTEN_ICON
        button.icon = (iconManager.getIcon(iconPath, LISTEN_ICON_SIZE, LISTEN_ICON_SIZE)
            as FlatSVGIcon).applyForegroundColorFilter()
        button.toolTipText = if (isSpeaking) stopTooltip else listenTooltip
    }

    private fun muteLabel(text: String): JLabel = JLabel(text).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
    }

    private fun posDivider(pos: String): JPanel {
        val accent = UIManager.getColor("Component.accentColor") ?: Color(86, 156, 214)
        val muted  = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 28)
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, muted)
            add(JLabel(pos.uppercase()).apply {
                foreground = accent
                font = font.deriveFont(Font.BOLD, font.size - 1f)
                border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
            }, BorderLayout.LINE_START)
        }
    }

    private fun definitionRow(num: Int, text: String): JPanel {
        val muted = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        return JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            add(JLabel("$num.").apply {
                foreground = muted
                verticalAlignment = SwingConstants.TOP
                preferredSize = Dimension(UIScale.scale(22), preferredSize.height)
            }, BorderLayout.LINE_START)
            add(wrappingArea(text), BorderLayout.CENTER)
        }
    }

    private fun exampleRow(text: String): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        border = BorderFactory.createEmptyBorder(0, 28, 0, 0)
        add(wrappingArea("“$text”", muted = true, italic = true), BorderLayout.CENTER)
    }

    private fun synonymsRow(
        label: String,
        synonyms: List<String>,
        onClick: ((String) -> Unit)?
    ): JPanel {
        val muted = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            add(JLabel("$label:").apply {
                foreground = muted
                font = font.deriveFont(Font.BOLD)
            })
            synonyms.take(12).forEachIndexed { i, syn ->
                if (i > 0) add(JLabel("·").apply { foreground = muted })
                if (onClick != null) {
                    add(JButton(syn).apply {
                        putClientProperty("JButton.buttonType", "borderless")
                        foreground = muted
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        margin = Insets(0, 0, 0, 0)
                        addActionListener { onClick(syn) }
                    })
                } else {
                    add(JLabel(syn).apply { foreground = muted })
                }
            }
        }
    }

    private fun wrappingArea(text: String, muted: Boolean = false, italic: Boolean = false): JTextArea {
        return JTextArea(text).apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            isOpaque = false
            isFocusable = false
            clearBorder()
            background = null
            if (muted) foreground = UIManager.getColor("Label.disabledForeground")
            if (italic) font = font.deriveFont(Font.ITALIC)
        }
    }

    private companion object {
        val LISTEN_ICON = Icons.SPEAK
        val STOP_ICON = Icons.CLOSE
        const val LISTEN_ICON_SIZE = 14
    }
}
