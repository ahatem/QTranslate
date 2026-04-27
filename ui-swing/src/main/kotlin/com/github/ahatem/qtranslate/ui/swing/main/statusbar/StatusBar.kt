package com.github.ahatem.qtranslate.ui.swing.main.statusbar

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import java.awt.geom.Arc2D
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.Timer
import javax.swing.border.MatteBorder

class StatusBar(
    iconManager: IconManager,
    private val onNotificationsClicked: () -> Unit
) : JPanel(BorderLayout()), Renderable<StatusBarState> {

    private val chip = StatusChip()
    private val spinner = LoadingSpinner(14)

    private val notificationButton = createButtonWithIcon(iconManager, "icons/lucide/notification.svg", 14).apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        addActionListener { onNotificationsClicked() }
    }

    init {
        border = MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor"))

        val leftPanel = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(spinner, BorderLayout.LINE_START)
            add(chip, BorderLayout.CENTER)
        }

        val contentPanel = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            isOpaque = false
            add(leftPanel, BorderLayout.CENTER)
            add(notificationButton, BorderLayout.LINE_END)
        }

        add(contentPanel, BorderLayout.CENTER)
    }

    override fun render(state: StatusBarState) {
        border = MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor"))
        chip.update(state.message, state.type)
        spinner.setLoading(state.isLoading)
        notificationButton.toolTipText = state.notificationTooltip
        notificationButton.isEnabled = state.isNotificationButtonEnabled
    }

    fun text(): String = chip.currentText

    /**
     * A pill-shaped label that draws a semi-transparent colored background for
     * WARNING/ERROR/SUCCESS types and uses the type color for the text.
     * INFO type renders as plain text with no background fill.
     */
    private class StatusChip : JComponent() {
        var currentText: String = ""
            private set
        private var currentType: NotificationType = NotificationType.INFO

        init {
            isOpaque = false
        }

        fun update(text: String, type: NotificationType) {
            currentText = text
            currentType = type
            revalidate()
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (currentText.isBlank()) return

            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val f = UIManager.getFont("Label.font") ?: font
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
                g2.fill(RoundRectangle2D.Float(chipX.toFloat(), chipY.toFloat(), chipW.toFloat(), chipH.toFloat(), chipH.toFloat(), chipH.toFloat()))
            }

            g2.color = color
            g2.drawString(currentText, chipX + 8, chipY + fm.ascent + 3)
            g2.dispose()
        }

        override fun getPreferredSize(): Dimension {
            val f = UIManager.getFont("Label.font") ?: font
            val fm = getFontMetrics(f)
            val w = if (currentText.isNotEmpty()) fm.stringWidth(currentText) + 16 else 0
            val h = fm.height + 8
            return Dimension(maxOf(w, 0), h)
        }

        private fun typeColor(): Color = when (currentType) {
            NotificationType.SUCCESS -> UIManager.getColor("Actions.Green") ?: Color(0x59A869)
            NotificationType.WARNING -> UIManager.getColor("Actions.Yellow") ?: Color(0xE2A53A)
            NotificationType.ERROR -> UIManager.getColor("Actions.Red") ?: Color(0xE05555)
            NotificationType.INFO -> UIManager.getColor("Label.foreground") ?: Color.GRAY
        }
    }

    /**
     * A small circular spinner that rotates while [isLoading] is true.
     * Renders a faint background ring and a brighter 80° arc that advances
     * every 40 ms — about 375 RPM, consistent with system spinner conventions.
     */
    private class LoadingSpinner(private val size: Int) : JComponent() {
        private var angle = 0
        private val timer = Timer(40) {
            angle = (angle + 15) % 360
            repaint()
        }

        init {
            val dim = Dimension(size, size)
            preferredSize = dim
            minimumSize = dim
            maximumSize = dim
            isVisible = false
            isOpaque = false
        }

        fun setLoading(loading: Boolean) {
            if (loading == isVisible) return
            isVisible = loading
            if (loading) timer.start() else timer.stop()
            revalidate()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val pad = 2
            val d = size - pad * 2
            val base = UIManager.getColor("Label.foreground") ?: Color.GRAY

            g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            g2.color = Color(base.red, base.green, base.blue, 40)
            g2.drawOval(pad, pad, d, d)

            g2.color = base
            g2.draw(Arc2D.Float(pad.toFloat(), pad.toFloat(), d.toFloat(), d.toFloat(), angle.toFloat(), 80f, Arc2D.OPEN))

            g2.dispose()
        }
    }
}
