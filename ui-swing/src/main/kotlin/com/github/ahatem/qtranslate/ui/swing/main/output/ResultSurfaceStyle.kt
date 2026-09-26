package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.border.AbstractBorder

/** Shared, deliberately quiet visual language for primary and comparison result surfaces. */
internal object ResultSurfaceStyle {
    fun apply(panel: JPanel, primary: Boolean) {
        panel.isOpaque = true
        panel.background = surfaceBackground()
        panel.border = ResultSurfaceBorder(primary)
    }

    fun reset(panel: JPanel) {
        panel.isOpaque = false
        panel.background = null
        panel.border = null
    }

    fun surfaceBackground(): Color =
        UIManager.getColor("Panel.background")
            ?: UIManager.getColor("TextField.background")
            ?: Color.BLACK

    fun createBadge(): JLabel = JLabel().apply {
        putClientProperty(
            FlatClientProperties.STYLE,
            "arc: 999; border: 0, 8, 0, 8;"
        )
        border = BorderFactory.createEmptyBorder(
            UIScale.scale(2), UIScale.scale(7), UIScale.scale(2), UIScale.scale(7)
        )
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
        refreshBadgeColors(this)
    }

    fun refreshBadgeColors(label: JLabel) {
        label.background = UIManager.getColor("Component.focusColor")
            ?: UIManager.getColor("Component.accentColor")
            ?: UIManager.getColor("Label.disabledForeground")
        label.foreground = UIManager.getColor("Component.focusedBorderColor")
            ?: UIManager.getColor("Label.foreground")
        label.isOpaque = true
    }

    private class ResultSurfaceBorder(private val primary: Boolean) : AbstractBorder() {
        override fun getBorderInsets(c: Component): Insets {
            val inset = UIScale.scale(if (primary) 2 else 1)
            return Insets(inset, inset, inset, inset)
        }

        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val colorKey = if (primary) "Component.focusedBorderColor" else "Component.borderColor"
            val color = UIManager.getColor(colorKey) ?: UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            val graphics = g.create() as Graphics2D
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.color = color
                val inset = UIScale.scale(if (primary) 1 else 0)
                graphics.drawRoundRect(
                    x + inset,
                    y + inset,
                    width - inset * 2 - 1,
                    height - inset * 2 - 1,
                    UIScale.scale(12),
                    UIScale.scale(12)
                )
            } finally {
                graphics.dispose()
            }
        }
    }
}
