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
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

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
        override fun getMinimumSize() = Dimension(0, 3)
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, 3)
    }.apply {
        isIndeterminate = true
        isVisible = false
        isBorderPainted = false
        isOpaque = false
    }

    private val notificationButton = createButtonWithIcon(iconManager, Icons.NOTIFICATION, 14).apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        addActionListener { onNotificationsClicked() }
    }

    init {
        val contentPanel = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            isOpaque = false
            add(chip, BorderLayout.CENTER)
            add(notificationButton, BorderLayout.LINE_END)
        }

        add(contentPanel, BorderLayout.CENTER)
        add(progressBar, BorderLayout.SOUTH)

        UIManager.addPropertyChangeListener { evt ->
            if (evt.propertyName == "lookAndFeel") {
                updateBorder()
            }
        }
    }

    override fun render(state: StatusBarState) {
        updateBorder()
        chip.update(state.message, state.type)
        progressBar.isVisible = state.isLoading
        notificationButton.toolTipText = state.notificationTooltip
        notificationButton.isEnabled = state.isNotificationButtonEnabled
    }

    override fun updateUI() {
        super.updateUI()
        updateBorder()
    }

    private fun updateBorder() {
        val borderColor = UIManager.getColor("Component.borderColor")
            ?: UIManager.getColor("Panel.background")?.darker()
            ?: Color.GRAY

        border = MatteBorder(1, 0, 0, 0, borderColor)
        revalidate()
        repaint()
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

        private val chipBackgroundAlpha = 40  // 15% opacity for chip backgrounds
        private val cornerArcFraction = 1.0   // Fully round (pill shape)

        init {
            isOpaque = false
            font = UIManager.getFont("Label.font") ?: font

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

        override fun updateUI() {
            super.updateUI()
            font = UIManager.getFont("Label.font") ?: font
            revalidate()
            repaint()
        }

        private fun isClickable() =
            currentText.isNotBlank() &&
                    (currentType == NotificationType.ERROR || currentType == NotificationType.WARNING)

        fun update(text: String, type: NotificationType) {
            val changed = currentText != text || currentType != type
            currentText = text
            currentType = type

            // Full text as tooltip so even truncated messages are fully readable on hover.
            toolTipText = if (text.isNotBlank() && type != NotificationType.INFO) text else null

            if (changed) {
                revalidate()
                repaint()
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (currentText.isBlank()) return

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

                g2.font = font
                val fm = g2.fontMetrics

                val chipH = fm.height + 6
                val chipW = fm.stringWidth(currentText) + 16
                val arcH = (chipH * cornerArcFraction).toFloat()
                val chipY = (height - chipH) / 2

                val isLtr = componentOrientation.isLeftToRight
                val chipX = if (isLtr) 0 else width - chipW

                val color = resolveColor()

                // Draw background pill (only for non-INFO types)
                if (currentType != NotificationType.INFO) {
                    // Use theme-aware alpha: darker themes get slightly more opaque backgrounds
                    val alpha = if (isDarkTheme()) chipBackgroundAlpha + 10 else chipBackgroundAlpha
                    g2.color = Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
                    g2.fill(
                        RoundRectangle2D.Float(
                            chipX.toFloat(), chipY.toFloat(),
                            chipW.toFloat(), chipH.toFloat(),
                            arcH, arcH
                        )
                    )

                    // Draw subtle border for better contrast on some themes
                    g2.color = Color(color.red, color.green, color.blue, alpha + 40)
                    g2.stroke = BasicStroke(1f)
                    g2.draw(
                        RoundRectangle2D.Float(
                            chipX.toFloat(), chipY.toFloat(),
                            chipW.toFloat(), chipH.toFloat(),
                            arcH, arcH
                        )
                    )
                }

                // Draw text
                g2.color = color
                val textY = chipY + fm.ascent + 3
                g2.drawString(currentText, chipX + 8, textY)
            } finally {
                g2.dispose()
            }
        }

        override fun getPreferredSize(): Dimension {
            val f = font
            val fm = getFontMetrics(f)
            val w = if (currentText.isNotEmpty()) fm.stringWidth(currentText) + 16 else 0
            return Dimension(maxOf(w, 0), fm.height + 8)
        }

        private fun resolveColor(): Color {
            return when (currentType) {
                NotificationType.SUCCESS -> getThemeColor(
                    "Actions.Green",
                    "FlatLaf.accentColor",
                    defaultColor = Color(0x59A869)
                )

                NotificationType.WARNING -> getThemeColor(
                    "Actions.Yellow",
                    "Component.warning.focusedBorderColor",
                    defaultColor = Color(0xE2A53A)
                )

                NotificationType.ERROR -> getThemeColor(
                    "Actions.Red",
                    "Component.error.focusedBorderColor",
                    defaultColor = Color(0xE05555)
                )

                NotificationType.INFO -> UIManager.getColor("Label.foreground")
                    ?: Color.GRAY
            }
        }

        /**
         * Tries a list of UIManager keys in order, falling back to [defaultColor]
         * if none are found. This handles theme variations gracefully.
         */
        private fun getThemeColor(vararg keys: String, defaultColor: Color): Color {
            for (key in keys) {
                val color = UIManager.getColor(key)
                if (color != null) {
                    // Adjust brightness if needed for the current theme
                    return if (isDarkTheme() && isTooDark(color)) {
                        color.brighter()
                    } else if (!isDarkTheme() && isTooBright(color)) {
                        color.darker()
                    } else {
                        color
                    }
                }
            }
            return defaultColor
        }

        private fun isDarkTheme(): Boolean {
            return UIManager.getLookAndFeel().isNativeLookAndFeel && // fallback check
                    (UIManager.getColor("Panel.background")?.let { bg ->
                        val luminance = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
                        luminance < 128
                    } ?: false)
        }

        private fun isTooDark(color: Color): Boolean =
            0.299 * color.red + 0.587 * color.green + 0.114 * color.blue < 40

        private fun isTooBright(color: Color): Boolean =
            0.299 * color.red + 0.587 * color.green + 0.114 * color.blue > 220
    }
}
