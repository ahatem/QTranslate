package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/** What the Comparison board says before anything has been translated. */
data class CompareEmptyState(val title: String, val message: String)

/**
 * A small centered stack of an optional icon, an optional title and a message, drawn directly on
 * the surface behind it. Text wraps to the width it is given and the stack is centered in the
 * space, so it follows the interface direction and the theme without any painting of its own.
 */
class CenteredStateView(iconManager: IconManager? = null, iconPath: String? = null) : JPanel() {

    private val iconLabel = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        isVisible = false
        val icon = iconPath?.let { iconManager?.getIcon(it, ICON_SIZE, ICON_SIZE) } as? FlatSVGIcon
        if (icon != null) {
            icon.colorFilter = FlatSVGIcon.ColorFilter { color -> UIManager.getColor(MUTED) ?: color }
            this.icon = icon
            isVisible = true
        }
    }
    private val titleText = CenteredText(PRIMARY_TEXT)
    private val messageText = CenteredText(MUTED, small = true)

    init {
        isOpaque = false
        layout = StackLayout()
        add(iconLabel)
        add(titleText)
        add(messageText)
    }

    fun render(title: String?, message: String) {
        titleText.setContent(title.orEmpty())
        titleText.isVisible = !title.isNullOrBlank()
        messageText.setContent(message)
        revalidate()
        repaint()
    }

    fun titleForTest(): JTextPane = titleText
    fun messageForTest(): JTextPane = messageText
    fun iconForTest(): JLabel = iconLabel

    private inner class StackLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit

        private fun textWidth(parent: Container): Int {
            val available = parent.width - parent.insets.left - parent.insets.right - 2 * UIScale.scale(SIDE_PAD)
            return available.coerceIn(1, UIScale.scale(MAX_TEXT_WIDTH))
        }

        private fun heightOf(component: Component, width: Int): Int {
            component.setSize(width, Int.MAX_VALUE)
            return component.preferredSize.height
        }

        private fun stackHeight(parent: Container, width: Int): Int {
            val gap = UIScale.scale(GAP)
            val heights = parent.components.filter { it.isVisible }.map { heightOf(it, width) }
            return heights.sum() + gap * (heights.size - 1).coerceAtLeast(0)
        }

        override fun preferredLayoutSize(parent: Container): Dimension {
            val width = if (parent.width > 0) textWidth(parent) else UIScale.scale(MAX_TEXT_WIDTH)
            val insets = parent.insets
            val height = stackHeight(parent, width) + 2 * UIScale.scale(VERTICAL_PAD) + insets.top + insets.bottom
            return Dimension(width + 2 * UIScale.scale(SIDE_PAD) + insets.left + insets.right, height)
        }

        override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, 0)

        override fun layoutContainer(parent: Container) {
            val insets = parent.insets
            val width = textWidth(parent)
            val gap = UIScale.scale(GAP)
            val visible = parent.components.filter { it.isVisible }
            val heights = visible.map { heightOf(it, width) }
            val total = heights.sum() + gap * (heights.size - 1).coerceAtLeast(0)
            val innerHeight = parent.height - insets.top - insets.bottom
            var y = insets.top + ((innerHeight - total) / 2).coerceAtLeast(UIScale.scale(VERTICAL_PAD))
            val centerX = insets.left + (parent.width - insets.left - insets.right) / 2
            visible.forEachIndexed { index, component ->
                component.setBounds(centerX - width / 2, y, width, heights[index])
                y += heights[index] + gap
            }
        }
    }

    private companion object {
        const val ICON_SIZE = 20
        const val GAP = 6
        const val SIDE_PAD = 16
        const val VERTICAL_PAD = 16
        const val MAX_TEXT_WIDTH = 360
        const val PRIMARY_TEXT = "Label.foreground"
        const val MUTED = "Label.disabledForeground"
    }
}

/** Wrapping, centered, non-interactive text that takes its color from the theme. */
private class CenteredText(private val colorKey: String, small: Boolean = false) : JTextPane() {
    init {
        isEditable = false
        isFocusable = false
        highlighter = null
        if (small) putClientProperty("FlatLaf.styleClass", "small")
        applyTheme()
    }

    fun setContent(content: String) {
        text = content
        styledDocument.setParagraphAttributes(
            0, styledDocument.length, SimpleAttributeSet().also { StyleConstants.setAlignment(it, StyleConstants.ALIGN_CENTER) }, false
        )
    }

    override fun updateUI() {
        super.updateUI()
        applyTheme()
    }

    private fun applyTheme() {
        isOpaque = false
        border = BorderFactory.createEmptyBorder()
        // updateUI also runs from the JTextPane constructor, before colorKey is assigned.
        @Suppress("SENSELESS_COMPARISON")
        if (colorKey != null) UIManager.getColor(colorKey)?.let { foreground = it }
    }
}
