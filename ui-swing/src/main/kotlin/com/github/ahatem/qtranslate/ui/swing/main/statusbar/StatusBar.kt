package com.github.ahatem.qtranslate.ui.swing.main.statusbar

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.MatteBorder

class StatusBar(
    iconManager: IconManager,
    private val onNotificationsClicked: () -> Unit,
) : JPanel(BorderLayout()), Renderable<StatusBarState> {

    /**
     * Invoked when the user clicks an ERROR or WARNING chip.
     * Receives the full message text so the caller can display it
     * (e.g. in an [ErrorDetailPopup]).
     */
    var onErrorClicked: ((message: String) -> Unit)? = null

    private val chip = StatusChip(onClicked = { message -> onErrorClicked?.invoke(message) })

    /** Thin indeterminate bar that hugs the bottom edge while a translation is in progress. */
    private val progressBar = object : JProgressBar() {
        override fun getPreferredSize() = Dimension(super.getPreferredSize().width, 3)
        override fun getMinimumSize()   = Dimension(0, 3)
        override fun getMaximumSize()   = Dimension(Int.MAX_VALUE, 3)
    }.apply {
        isIndeterminate  = true
        isVisible        = false
        isBorderPainted  = false
        isOpaque         = false
    }

    private val notificationButton = createButtonWithIcon(iconManager, "icons/lucide/notification.svg", 14).apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        addActionListener { onNotificationsClicked() }
    }

    init {
        border = MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor"))

        val contentPanel = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            isOpaque = false
            add(chip, BorderLayout.CENTER)
            add(notificationButton, BorderLayout.LINE_END)
        }

        add(contentPanel, BorderLayout.CENTER)
        add(progressBar,  BorderLayout.SOUTH)
    }

    override fun render(state: StatusBarState) {
        border = MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor"))
        chip.update(state.message, state.type)
        progressBar.isVisible = state.isLoading
        notificationButton.toolTipText = state.notificationTooltip
        notificationButton.isEnabled   = state.isNotificationButtonEnabled
    }

    fun text(): String = chip.currentText

    /**
     * A pill-shaped label that draws a semi-transparent colored background for
     * WARNING/ERROR/SUCCESS types and uses the type color for the text.
     * INFO type renders as plain text with no background fill.
     *
     * When [onClicked] is set and the current type is ERROR or WARNING the chip
     * shows a hand cursor and fires [onClicked] on click; it also sets a tooltip
     * with the full message text so long strings are readable on hover.
     */
    private class StatusChip(private val onClicked: (message: String) -> Unit) : JComponent() {
        var currentText: String = ""
            private set
        private var currentType: NotificationType = NotificationType.INFO

        init {
            isOpaque = false
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (isClickable()) onClicked(currentText)
                }
                override fun mouseEntered(e: MouseEvent) {
                    cursor = if (isClickable()) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                             else Cursor.getDefaultCursor()
                }
                override fun mouseExited(e: MouseEvent) {
                    cursor = Cursor.getDefaultCursor()
                }
            })
        }

        private fun isClickable() =
            currentText.isNotBlank() &&
            (currentType == NotificationType.ERROR || currentType == NotificationType.WARNING)

        fun update(text: String, type: NotificationType) {
            currentText = text
            currentType = type
            // Full text as tooltip so even truncated messages are fully readable on hover.
            toolTipText = if (text.isNotBlank() && type != NotificationType.INFO) text else null
            revalidate()
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (currentText.isBlank()) return

            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val f  = UIManager.getFont("Label.font") ?: font
            g2.font = f
            val fm = g2.fontMetrics

            val chipH = fm.height + 6
            val chipW = fm.stringWidth(currentText) + 16
            val chipY = (height - chipH) / 2

            val isLtr = componentOrientation.isLeftToRight
            val chipX = if (isLtr) 0 else width - chipW

            val color = typeColor()

            if (currentType != NotificationType.INFO) {
                g2.color = Color(color.red, color.green, color.blue, 28)
                g2.fill(RoundRectangle2D.Float(
                    chipX.toFloat(), chipY.toFloat(),
                    chipW.toFloat(), chipH.toFloat(),
                    chipH.toFloat(), chipH.toFloat()
                ))
            }

            g2.color = color
            g2.drawString(currentText, chipX + 8, chipY + fm.ascent + 3)
            g2.dispose()
        }

        override fun getPreferredSize(): Dimension {
            val f  = UIManager.getFont("Label.font") ?: font
            val fm = getFontMetrics(f)
            val w  = if (currentText.isNotEmpty()) fm.stringWidth(currentText) + 16 else 0
            return Dimension(maxOf(w, 0), fm.height + 8)
        }

        private fun typeColor(): Color = when (currentType) {
            NotificationType.SUCCESS -> UIManager.getColor("Actions.Green")  ?: Color(0x59A869)
            NotificationType.WARNING -> UIManager.getColor("Actions.Yellow") ?: Color(0xE2A53A)
            NotificationType.ERROR   -> UIManager.getColor("Actions.Red")    ?: Color(0xE05555)
            NotificationType.INFO    -> UIManager.getColor("Label.foreground") ?: Color.GRAY
        }
    }
}
