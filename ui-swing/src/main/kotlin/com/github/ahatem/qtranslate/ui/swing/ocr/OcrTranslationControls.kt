package com.github.ahatem.qtranslate.ui.swing.ocr

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Keeps translation direction and its action together, wrapping the action when space is tight. */
internal class OcrTranslationControls(
    source: JComboBox<*>,
    target: JComboBox<*>,
    internal val translate: JButton,
    rtl: Boolean,
) : JPanel(BorderLayout(4, 2)) {
    private val direction = JLabel(if (rtl) " ← " else " → ")
    private val languages = JPanel(GridBagLayout()).apply {
        val c = GridBagConstraints().apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL }
        add(source, c)
        c.weightx = 0.0; c.fill = GridBagConstraints.NONE
        add(direction, c)
        c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL
        add(target, c)
    }
    private val wrappedAction = JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply { isOpaque = false }
    internal var isActionWrapped = false
        private set

    init {
        add(languages, BorderLayout.CENTER)
        add(translate, BorderLayout.LINE_END)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { adaptToWidth(width) }
        })
    }

    internal fun adaptToWidth(width: Int) {
        if (width <= 0) return
        val readableLanguageWidth = getFontMetrics(font).height * 7
        val needsWrap = width < readableLanguageWidth * 2 + direction.preferredSize.width + translate.preferredSize.width + 12
        if (needsWrap == isActionWrapped) return
        val restoreFocus = translate.isFocusOwner
        remove(translate)
        wrappedAction.remove(translate)
        if (needsWrap) {
            wrappedAction.add(translate)
            add(wrappedAction, BorderLayout.SOUTH)
        } else {
            remove(wrappedAction)
            add(translate, BorderLayout.LINE_END)
        }
        isActionWrapped = needsWrap
        revalidate()
        repaint()
        if (restoreFocus) SwingUtilities.invokeLater { translate.requestFocusInWindow() }
    }
}
