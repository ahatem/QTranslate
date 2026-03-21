package com.github.ahatem.qtranslate.ui.swing.main.statusbar

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.border.MatteBorder

class StatusBar(
    iconManager: IconManager,
    private val onNotificationsClicked: () -> Unit
) : JPanel(BorderLayout()), Renderable<StatusBarState> {

    companion object Colors {
        val INFO: Color = UIManager.getColor("Label.foreground")
        val SUCCESS: Color = UIManager.getColor("Actions.Green")
        val WARNING: Color = UIManager.getColor("Actions.Yellow")
        val ERROR: Color = UIManager.getColor("Actions.Red")
    }

    private val statusLabel = JLabel().apply {
        putClientProperty(FlatClientProperties.STYLE_CLASS, "medium")
    }

    private val notificationButton = createButtonWithIcon(iconManager, "icons/lucide/notification.svg", 14).apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false

        addActionListener { onNotificationsClicked() }
    }

    init {
        border = MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor"))

        val contentPanel = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)

            add(statusLabel, BorderLayout.CENTER)
            add(notificationButton, BorderLayout.EAST)
        }

        add(contentPanel, BorderLayout.CENTER)
    }

    override fun render(state: StatusBarState) {
        statusLabel.text = state.message
        statusLabel.foreground = when (state.type) {
            NotificationType.SUCCESS -> SUCCESS
            NotificationType.WARNING -> WARNING
            NotificationType.ERROR -> ERROR
            NotificationType.INFO -> INFO
        }

        notificationButton.toolTipText = state.notificationTooltip
        notificationButton.isEnabled = state.isNotificationButtonEnabled
    }

    fun text(): String = statusLabel.text
}