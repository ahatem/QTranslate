package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JWindow
import javax.swing.Timer
import javax.swing.UIManager

internal class SelectionTranslateButton(
    owner: Window,
    iconManager: IconManager,
    tooltip: String,
    private val onTranslate: (String) -> Unit
) : JWindow(owner) {
    private var selectedText = ""
    private val hideTimer = Timer(4_000) { dismiss() }.apply { isRepeats = false }

    init {
        type = Window.Type.POPUP
        isAlwaysOnTop = true
        focusableWindowState = false

        val button = JButton(iconManager.getIcon("icons/lucide/languages.svg", 17, 17)).apply {
            preferredSize = Dimension(34, 34)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isFocusable = false
            toolTipText = tooltip
            putClientProperty("JButton.buttonType", "roundRect")
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)
            )
            addActionListener {
                val text = selectedText
                dismiss()
                if (text.isNotBlank()) onTranslate(text)
            }
        }
        contentPane = button
        pack()
    }

    fun showAt(pointer: Point, text: String) {
        if (text.isBlank()) return
        selectedText = text

        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
            .firstOrNull { it.contains(pointer) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val x = (pointer.x + 10).coerceIn(screen.x, screen.x + screen.width - width)
        val y = (pointer.y + 10).coerceIn(screen.y, screen.y + screen.height - height)
        location = Point(x, y)
        isVisible = true
        hideTimer.restart()
    }

    fun dismiss() {
        hideTimer.stop()
        isVisible = false
        selectedText = ""
    }

    override fun dispose() {
        hideTimer.stop()
        super.dispose()
    }
}
