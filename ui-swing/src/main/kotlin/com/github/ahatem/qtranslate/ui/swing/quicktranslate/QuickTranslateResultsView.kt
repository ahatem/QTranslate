package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.ui.swing.main.output.ResultSurfaceStyle
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
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
    comparisonPanel: JComponent,
    private val iconManager: IconManager? = null
) : JPanel(BorderLayout()) {
    private var initialized = false
    private val providerIcon = JLabel().apply {
        horizontalAlignment = JLabel.CENTER
        preferredSize = Dimension(UIScale.scale(20), 0)
    }
    private val providerLabel = JLabel()
    private val badgeLabel = ResultSurfaceStyle.createBadge()
    private val identity = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0)).apply {
        isOpaque = false
        add(providerIcon)
        add(providerLabel)
    }
    private val primarySurface = JPanel(BorderLayout()).apply {
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        ResultSurfaceStyle.apply(this, primary = true)
        val header = JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
            isOpaque = false
            border = javax.swing.BorderFactory.createEmptyBorder(
                UIScale.scale(8), UIScale.scale(11), UIScale.scale(4), UIScale.scale(11)
            )
            add(identity, BorderLayout.LINE_START)
            add(badgeLabel, BorderLayout.LINE_END)
        }
        add(header, BorderLayout.NORTH)
        add(primaryPane.apply { border = javax.swing.BorderFactory.createEmptyBorder(4, 11, 6, 11) }, BorderLayout.CENTER)
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
        initialized = true
    }

    override fun updateUI() {
        super.updateUI()
        if (initialized) {
            ResultSurfaceStyle.apply(primarySurface, primary = true)
            ResultSurfaceStyle.refreshBadgeColors(badgeLabel)
        }
    }

    fun setPrimaryLabel(provider: ServiceInfo?, fallbackName: String, badge: String) {
        providerLabel.text = provider?.name ?: fallbackName
        providerIcon.icon = provider?.iconPath?.let { path ->
            iconManager?.getIcon(provider.id, path, UIScale.scale(18), UIScale.scale(18))
        }
        providerIcon.isVisible = providerIcon.icon != null
        badgeLabel.text = badge
        ResultSurfaceStyle.refreshBadgeColors(badgeLabel)
    }
}
