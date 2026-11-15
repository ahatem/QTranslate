package com.github.ahatem.qtranslate.ui.swing.settings.panels

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * The line under the recorder's capture field that names a combination already in use.
 *
 * It is a text area rather than a label because the sentence names a command, and command names
 * run long — "Screen OCR — Default Version" is already wide enough to push a single-line label
 * past the dialog's edge, where it was cut off with the rest of the name invisible. Wrapping
 * keeps the whole name readable inside the dialog's own width.
 *
 * The row keeps its height when empty: a hidden component takes no room in the dialog's vertical
 * box, so the packed dialog reserved nothing for it and the warning was clipped as soon as one
 * appeared. Reserving the full wrapped height up front is what keeps the sentence whole.
 */
internal class HotkeyConflictRow(
    warningColor: Color,
    baseFont: java.awt.Font,
) : JPanel(BorderLayout()) {

    private val message = JTextArea().apply {
        isEditable    = false
        isOpaque      = false
        lineWrap      = true
        wrapStyleWord = true
        font          = baseFont.deriveFont(baseFont.size - 1f)
        foreground    = warningColor
        text          = ""
    }

    init {
        isOpaque = false
        add(message, BorderLayout.CENTER)
    }

    /** Shows [text] when it names something, and keeps the row's height when it does not. */
    fun show(text: String?) {
        message.text = text?.takeIf { it.isNotBlank() }.orEmpty()
        revalidate()
    }

    /**
     * Reports the height the row needs at the width it is being given.
     *
     * A text area reports the height of its *unwrapped* text, and that height is passed straight
     * up to the dialog's pack: the row claimed one line, then rendered two once it had a width to
     * wrap against, and the second line fell outside the dialog's edge.
     *
     * The measurement is taken against [RESERVED_TEXT] rather than the live text on purpose. The
     * reserved height has to hold whatever warning arrives later, and measuring the live text
     * would make the row collapse to nothing while it is empty. The row is blank until there is
     * something to report; only its height is reserved.
     */
    override fun getPreferredSize(): Dimension {
        val available = (width - insets.left - insets.right)
            .takeIf { it > 0 }
            ?: placeholderWidth
        val original = message.text
        message.text = RESERVED_TEXT
        message.setSize(available, Short.MAX_VALUE.toInt())
        val height = message.preferredSize.height
        message.text = original
        message.setSize(available, height)
        return Dimension(available, height)
    }

    private companion object {
        /**
         * The warning the reserved height is measured against.
         *
         * It is as long as a typical warning sentence, so the reserved height matches what a real
         * message needs. A shorter sample would under-reserve and the warning would be clipped
         * again. It is never displayed.
         */
        const val RESERVED_TEXT = "⚠  Already assigned to another command. You can still save it."
    }

    /** The width [RESERVED_TEXT] needs on one line; the row never measures itself narrower. */
    private val placeholderWidth: Int get() = message.getFontMetrics(message.font).stringWidth(RESERVED_TEXT)
}
