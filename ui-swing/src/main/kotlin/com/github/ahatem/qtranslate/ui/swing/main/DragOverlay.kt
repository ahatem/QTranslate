package com.github.ahatem.qtranslate.ui.swing.main

import com.formdev.flatlaf.util.UIScale
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.JLabel
import javax.swing.UIManager

/**
 * The "drop it here" sheet drawn over the whole window while a file is dragged across it.
 *
 * Worth having only because there is now a single drop target: when images and documents were
 * handled in two places that disagreed about what they accepted, no honest overlay could have been
 * drawn — it would have promised a drop the pane underneath would then discard.
 *
 * @param onLabel supplies the message, read fresh each time so it follows the interface language.
 */
class DragOverlay(private val frame: JFrame, private val onLabel: () -> String) {

    private val label = JLabel("", SwingConstants.CENTER).apply {
        putClientProperty("FlatLaf.styleClass", "h2")
        isOpaque = false
    }

    private val sheet = object : JComponent() {
        init {
            isOpaque = false
            layout = java.awt.BorderLayout()
            add(label, java.awt.BorderLayout.CENTER)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val background = UIManager.getColor("Panel.background") ?: Color.DARK_GRAY
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.88f)
                g2.color = background
                g2.fillRect(0, 0, width, height)

                // A dashed inset frame, which is the conventional "this area accepts a drop".
                val accent = UIManager.getColor("Component.focusedBorderColor")
                    ?: UIManager.getColor("Component.accentColor")
                    ?: Color.GRAY
                val inset = UIScale.scale(16)
                g2.composite = AlphaComposite.SrcOver
                g2.color = accent
                g2.stroke = BasicStroke(
                    UIScale.scale(2).toFloat(),
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                    0f,
                    floatArrayOf(UIScale.scale(8).toFloat(), UIScale.scale(6).toFloat()),
                    0f
                )
                g2.drawRect(inset, inset, width - inset * 2, height - inset * 2)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * Hides the sheet shortly after the drag stops being reported.
     *
     * `TransferHandler` has no "the pointer left" callback — it only asks, repeatedly, whether the
     * current drag can be imported. So the overlay is kept alive by those questions and fades out
     * when they stop, which also covers a drag abandoned outside the window, where no drop event
     * ever arrives and a sheet left showing would strand the UI behind it.
     */
    private val expiry = Timer(HIDE_DELAY_MS) { hide() }.apply { isRepeats = false }

    /**
     * The sheet itself, so the caller can give it the same drop handling as everything else.
     *
     * A visible glass pane is the component under the pointer, and one without a `DropTarget`
     * silently swallows the drop it was drawn to invite — the overlay appears, the file is
     * released, and nothing happens.
     */
    val component: JComponent get() = sheet

    /** Called for each drag-over. Shows the sheet the first time and restarts the expiry. */
    fun keepShowing() {
        if (!sheet.isVisible) {
            label.text = onLabel()
            frame.glassPane = sheet
            sheet.isVisible = true
        }
        expiry.restart()
    }

    fun hide() {
        expiry.stop()
        if (sheet.isVisible) sheet.isVisible = false
    }

    private companion object {
        /** Long enough to survive the gap between drag-over events, short enough not to linger. */
        const val HIDE_DELAY_MS = 260
    }
}
