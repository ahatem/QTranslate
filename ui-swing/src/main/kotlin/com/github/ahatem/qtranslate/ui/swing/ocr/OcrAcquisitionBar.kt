package com.github.ahatem.qtranslate.ui.swing.ocr

import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

/** The acquisition row retains the same actions in its overflow when width is constrained. */
internal class OcrAcquisitionBar(
    private val labels: Labels,
    onCapture: () -> Unit,
    private val onOpen: () -> Unit,
    private val onPaste: () -> Unit,
    private val onCopy: () -> Unit,
    private val onSave: () -> Unit,
    private val onSettings: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEADING, 3, 2)) {
    internal data class Labels(val capture: String, val open: String, val paste: String,
        val copy: String, val save: String, val settings: String, val more: String)

    private val capture = JButton(labels.capture).apply { addActionListener { onCapture() } }
    internal val open = JButton(labels.open).apply { addActionListener { onOpen() } }
    internal val paste = JButton(labels.paste).apply { addActionListener { onPaste() } }
    internal val more = JButton("⋯").apply {
        toolTipText = labels.more
        accessibleContext.accessibleName = labels.more
        addActionListener { if (isShowing) menu().show(this, 0, height) }
    }
    private var imageAvailable = false

    init {
        add(capture); add(open); add(paste); add(more)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { updateForWidth(width) }
        })
    }

    fun setImageAvailable(available: Boolean) { imageAvailable = available }

    internal fun updateForWidth(width: Int) {
        val available = width - insets.left - insets.right - 24
        if (available <= 0) return
        val full = listOf(capture, open, paste, more).sumOf { it.preferredSize.width } + 12
        open.isVisible = available >= full - paste.preferredSize.width - 3
        paste.isVisible = available >= full
    }

    internal fun menu(): JPopupMenu = JPopupMenu().apply {
        if (!open.isVisible) add(JMenuItem(labels.open).apply { addActionListener { onOpen() } })
        if (!paste.isVisible) add(JMenuItem(labels.paste).apply { addActionListener { onPaste() } })
        if (!open.isVisible || !paste.isVisible) addSeparator()
        add(JMenuItem(labels.copy).apply { isEnabled = imageAvailable; addActionListener { onCopy() } })
        add(JMenuItem(labels.save).apply { isEnabled = imageAvailable; addActionListener { onSave() } })
        addSeparator()
        add(JMenuItem(labels.settings).apply { addActionListener { onSettings() } })
    }
}
