package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * A hairline progress bar for work happening inside a popup that already has something on screen.
 *
 * The popups used to blank their content while reloading, which threw away the very thing the
 * reader was in the middle of — the previous translation or definition, still perfectly useful
 * while the next one arrives. This says "working" without taking anything away.
 *
 * The bar occupies a fixed height whether or not it is showing, so appearing and disappearing
 * never moves the content beneath it. A progress bar that shoves the text down by three pixels
 * every time it runs is worse than no progress bar.
 */
class InlineLoadingBar : JPanel(BorderLayout()) {

    private val bar = JProgressBar().apply {
        isIndeterminate = true
        // Hidden rather than absent, so this panel keeps its height either way.
        isVisible = false
        preferredSize = Dimension(0, UIScale.scale(BAR_HEIGHT))
        border = null
    }

    var isLoading: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Animating an indeterminate bar costs a repaint timer, so it only runs while shown.
            bar.isIndeterminate = value
            bar.isVisible = value
        }

    init {
        isOpaque = false
        preferredSize = Dimension(0, UIScale.scale(BAR_HEIGHT))
        minimumSize = Dimension(0, UIScale.scale(BAR_HEIGHT))
        add(bar, BorderLayout.CENTER)
    }

    private companion object {
        /** Thin enough to read as a hint rather than a component. */
        const val BAR_HEIGHT = 3
    }
}
