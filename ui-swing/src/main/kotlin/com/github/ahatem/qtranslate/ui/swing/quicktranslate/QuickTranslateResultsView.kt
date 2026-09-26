package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoard
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * One bounded viewport for the primary provider, its definition, and every
 * comparison in a single column. The board owns all three, so Quick Translate
 * never splits into disconnected scroll regions.
 */
internal class QuickTranslateResultsView(
    board: CompareBoard
) : JPanel(BorderLayout()) {
    val viewport = JScrollPane(board).apply {
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
}
