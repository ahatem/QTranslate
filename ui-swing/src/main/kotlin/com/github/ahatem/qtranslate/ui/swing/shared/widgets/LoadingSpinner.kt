package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.UIManager

/**
 * A small circular busy spinner for status rows where a horizontal bar would
 * dwarf the surrounding text.
 *
 * The spinner occupies a fixed square whether or not it is running, so
 * appearing and disappearing never moves the status text beside it.
 * The timer only runs while [isRunning] is true, so an idle dialog costs
 * no repaints.
 */
class LoadingSpinner : JComponent() {

    private var angle = 0

    private val timer = Timer(TICK_MS) {
        angle = (angle + STEP_DEGREES) % 360
        repaint()
    }

    /** When true the spinner shows and animates; when false it is hidden and stopped. */
    var isRunning: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            isVisible = value
            if (value) {
                timer.start()
            } else {
                timer.stop()
            }
        }

    init {
        isVisible = false
        isOpaque = false
        preferredSize = Dimension(UIScale.scale(SIZE), UIScale.scale(SIZE))
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val inset = UIScale.scale(2)
            g2.color = UIManager.getColor("Component.accentColor")
                ?: UIManager.getColor("Label.foreground")
                ?: Color.GRAY
            g2.stroke = BasicStroke(
                UIScale.scale(STROKE),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )
            g2.drawArc(
                inset,
                inset,
                width - 2 * inset,
                height - 2 * inset,
                angle,
                300,
            )
        } finally {
            g2.dispose()
        }
    }

    override fun updateUI() {
        super.updateUI()
        preferredSize = Dimension(UIScale.scale(SIZE), UIScale.scale(SIZE))
        revalidate()
    }

    private companion object {
        const val SIZE = 14
        const val STROKE = 2f
        const val TICK_MS = 80
        const val STEP_DEGREES = 30
    }
}
