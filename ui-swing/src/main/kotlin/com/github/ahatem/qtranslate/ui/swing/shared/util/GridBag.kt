package com.github.ahatem.qtranslate.ui.swing.shared.util

import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField

class GridBag(
    private val panel: JPanel,
    private val horizontalGap: Int = 0,
    private val verticalGap: Int = 0
) {
    private val defaultConstraints = GridBagConstraints().apply {
        gridwidth = 1
        gridheight = 1
        weightx = 0.0
        weighty = 0.0
        anchor = GridBagConstraints.LINE_START
        fill = GridBagConstraints.NONE
//        insets = Insets(4, 8, 4, 8)
    }

    private var temporaryConstraints = GridBagConstraints()
    private var cursorX = 0
    private var cursorY = 0

    val currentY: Int get() = cursorY

    init {
        panel.layout = GridBagLayout()
        resetTemporaryConstraints()
    }

    // --- Defaults for all components ---
    fun defaultFill(fill: Int): GridBag {
        defaultConstraints.fill = fill
        return this
    }

    fun defaultAnchor(anchor: Int): GridBag {
        defaultConstraints.anchor = anchor
        return this
    }

    fun defaultInsets(insets: Insets): GridBag {
        defaultConstraints.insets = insets
        return this
    }

    // --- Temporary per-component settings ---
    fun width(cells: Int): GridBag {
        temporaryConstraints.gridwidth = cells
        return this
    }

    fun height(cells: Int): GridBag {
        temporaryConstraints.gridheight = cells
        return this
    }

    fun spanLine(): GridBag {
        temporaryConstraints.gridwidth = GridBagConstraints.REMAINDER
        return this
    }

    fun weightX(weight: Double): GridBag {
        temporaryConstraints.weightx = weight
        return this
    }

    fun weightY(weight: Double): GridBag {
        temporaryConstraints.weighty = weight
        return this
    }

    fun fill(fill: Int): GridBag {
        temporaryConstraints.fill = fill
        return this
    }

    fun anchor(anchor: Int): GridBag {
        temporaryConstraints.anchor = anchor
        return this
    }

    fun insets(top: Int, left: Int, bottom: Int, right: Int): GridBag {
        temporaryConstraints.insets = Insets(top, left, bottom, right)
        return this
    }

    fun add(component: Component): GridBag {
        applyGaps()

        temporaryConstraints.gridx = cursorX
        temporaryConstraints.gridy = cursorY

        if (component is JComponent) {
            normalizeComponentSize(component)
        }

        panel.add(component, temporaryConstraints)
        cursorX += temporaryConstraints.gridwidth

        resetTemporaryConstraints()
        return this
    }

    fun nextRow(): GridBag {
        cursorX = 0
        cursorY++
        return this
    }

    private fun resetTemporaryConstraints() {
        temporaryConstraints = defaultConstraints.clone() as GridBagConstraints
    }

    private fun applyGaps() {
        val currentInsets = temporaryConstraints.insets ?: Insets(0, 0, 0, 0)
        val top = if (cursorY > 0) verticalGap else 0
        val left = if (cursorX > 0) horizontalGap else 0

        if (top > 0 || left > 0) {
            temporaryConstraints.insets = Insets(
                currentInsets.top + top,
                currentInsets.left + left,
                currentInsets.bottom,
                currentInsets.right
            )
        }
    }

    // --- Component normalization ---
    private fun normalizeComponentSize(comp: JComponent) {
        when (comp) {
            is JComboBox<*> -> {
                val pref = comp.preferredSize
                comp.preferredSize = Dimension(260, pref.height)
                comp.maximumSize = Dimension(300, pref.height)
            }
            is JSpinner -> {
                val pref = comp.preferredSize
                comp.preferredSize = Dimension(60, pref.height)
                comp.maximumSize = Dimension(80, pref.height)
            }
            is JTextField -> {
                val pref = comp.preferredSize
                comp.preferredSize = Dimension(260, pref.height)
                comp.maximumSize = Dimension(300, pref.height)
            }
        }
    }
}