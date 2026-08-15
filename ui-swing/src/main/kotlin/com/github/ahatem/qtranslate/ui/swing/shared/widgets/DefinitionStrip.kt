package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import java.awt.BorderLayout
import java.awt.ComponentOrientation
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.UIManager

/**
 * A short definition shown under a translation, for single words.
 *
 * Deliberately a separate strip rather than text appended to the translation itself, which is how
 * this used to be done elsewhere. Appended text ends up in the clipboard: copying a translated
 * word would hand you the word *and* a dictionary entry, which is almost never what was wanted.
 * Keeping it out of the output pane keeps copy honest.
 *
 * Styled as an aside — smaller, dimmer, indented to the same measure as the text above — because
 * it is a detail attached to the answer and not the answer. It hides itself entirely when there is
 * nothing to say, so a multi-word translation is laid out exactly as it was before this existed.
 */
class DefinitionStrip : JPanel(BorderLayout()) {

    private val text = JTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        // Not focusable and not in the tab order: it is something to glance at, and stopping on
        // it while tabbing between the real controls would be a nuisance.
        isFocusable = false
        putClientProperty("FlatLaf.styleClass", "small")
        foreground = UIManager.getColor("Label.disabledForeground")
    }

    init {
        isOpaque = false
        isVisible = false
        applyBorder()
        add(text, BorderLayout.CENTER)
    }

    /**
     * Height measured against the width this strip has actually been given.
     *
     * A wrapping `JTextArea` reports a preferred size based on its own current width, which in
     * `BorderLayout.SOUTH` is whatever it was last set to rather than what it is about to get.
     * Left alone it asks for one line and gets one line, clipping a two-line definition — or asks
     * for its full unwrapped width and is squeezed flat. Measuring at the real width avoids both.
     */
    override fun getPreferredSize(): Dimension {
        val insets = insets
        val available = (width - insets.left - insets.right).coerceAtLeast(1)
        text.setSize(available, Int.MAX_VALUE)
        val textHeight = text.preferredSize.height
        return Dimension(0, textHeight + insets.top + insets.bottom)
    }

    /** Blank hides the strip; anything else shows it. */
    fun render(definition: String) {
        val wanted = definition.isNotBlank()
        if (wanted && text.text != definition) {
            text.text = definition
            // Follows the definition's own script, not the interface language. A definition of an
            // Arabic word is Arabic, and left-aligning it under a right-aligned translation reads
            // as a stray line of text rather than a note about the word above it.
            val orientation =
                if (definition.isRTL()) ComponentOrientation.RIGHT_TO_LEFT
                else ComponentOrientation.LEFT_TO_RIGHT
            if (text.componentOrientation != orientation) {
                text.componentOrientation = orientation
                componentOrientation = orientation
            }
        }
        if (isVisible != wanted) {
            isVisible = wanted
            revalidate()
            repaint()
        }
    }

    /** Re-reads the theme's colours, which borders and foregrounds do not do by themselves. */
    fun refreshTheme() {
        text.foreground = UIManager.getColor("Label.disabledForeground")
        applyBorder()
        repaint()
    }

    /**
     * Spacing only — no rule of its own.
     *
     * The output pane it sits beneath already draws a border, and a second hairline immediately
     * below that read as a doubled line and made the definition look like a separate band rather
     * than a note about the translation. Indented to the same measure as the text above so the
     * two line up instead of the definition running to the panel edge.
     */
    private fun applyBorder() {
        border = BorderFactory.createEmptyBorder(
            UIScale.scale(4),
            UIScale.scale(10),
            UIScale.scale(6),
            UIScale.scale(10)
        )
    }
}
