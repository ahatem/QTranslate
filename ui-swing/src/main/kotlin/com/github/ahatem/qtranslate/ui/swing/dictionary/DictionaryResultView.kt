package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import java.awt.*
import javax.swing.*

/**
 * Scrollable panel that renders dictionary entries using native FlatLaf Swing components.
 * Implements [Scrollable] so that the content tracks the viewport width, enabling
 * JTextArea line-wrapping without a fixed pixel width.
 */
class DictionaryResultView : JScrollPane() {

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
        onSynonymClicked: ((String) -> Unit)? = null
    ) {
        if (entries == lastEntries && synonymsLabel == lastSynonymsLabel) return
        lastEntries = entries
        lastSynonymsLabel = synonymsLabel
        content.removeAll()

        if (entries.isEmpty()) {
            content.revalidate()
            content.repaint()
            return
        }

        val first = entries.first()

        addRow(wordLabel(first.word))
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
}
