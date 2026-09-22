package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout

/** One bounded viewport for the primary result, its definition, and comparison cards. */
internal class QuickTranslateResultsView(
    primaryPane: JComponent,
    definitionStrip: JComponent,
    comparisonPanel: JComponent
) : JPanel(BorderLayout()) {
    private val providerLabel = JLabel()
    private val badgeLabel = JLabel().apply {
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
    }
    private val primarySurface = JPanel(BorderLayout()).apply {
        isOpaque = false
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(
            JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(6), UIScale.scale(4))).apply {
                isOpaque = false
                add(providerLabel)
                add(badgeLabel)
            },
            BorderLayout.NORTH
        )
        add(primaryPane, BorderLayout.CENTER)
        add(definitionStrip, BorderLayout.SOUTH)
    }
    private val content = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
        add(primarySurface)
        add(comparisonPanel)
    }
    val viewport = JScrollPane(content).apply {
        putClientProperty(
            FlatClientProperties.STYLE,
            "borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; innerOutlineWidth: 0;"
        )
        viewport.border = null
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        isFocusable = false
    }

    init {
        isOpaque = false
        add(viewport, BorderLayout.CENTER)
    }

    fun setPrimaryLabel(providerName: String, badge: String) {
        providerLabel.text = providerName
        badgeLabel.text = badge
    }
}
