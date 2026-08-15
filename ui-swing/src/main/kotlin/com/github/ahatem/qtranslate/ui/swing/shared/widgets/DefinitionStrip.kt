package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import java.awt.BorderLayout
import java.awt.Color
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
 * Styled as an aside — smaller, dimmer, above a hairline — because it is a detail attached to the
 * answer and not the answer. It hides itself entirely when there is nothing to say, so a
 * multi-word translation is laid out exactly as it was before this existed.
 */
class DefinitionStrip : JPanel(BorderLayout()) {

    private val borderColor: Color get() = UIManager.getColor("Component.borderColor") ?: Color.GRAY

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

    /** Blank hides the strip; anything else shows it. */
    fun render(definition: String) {
        val wanted = definition.isNotBlank()
        if (wanted && text.text != definition) text.text = definition
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

    private fun applyBorder() {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor),
            BorderFactory.createEmptyBorder(
                UIScale.scale(6),
                UIScale.scale(8),
                UIScale.scale(6),
                UIScale.scale(8)
            )
        )
    }
}
