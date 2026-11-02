package com.github.ahatem.qtranslate.ui.swing.main.history

import com.github.ahatem.qtranslate.core.ocr.OcrWorkflowCommand
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Insets
import java.awt.Color
import java.awt.Component
import javax.swing.border.AbstractBorder
import com.formdev.flatlaf.util.UIScale
import javax.swing.UIManager
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke

/** Main window entry point. Resolution of DEFAULT remains with the OCR workflow. */
internal class OcrSplitButton(
    private val dispatch: (OcrWorkflowCommand) -> Unit,
    private val openSettings: () -> Unit,
) : JPanel(BorderLayout()) {
    internal val primary = JButton("OCR")
    internal val arrow = JButton("▾")
    private var labels: OcrSplitLabels? = null

    init {
        isOpaque = false
        listOf(primary, arrow).forEach {
            it.putClientProperty("JButton.buttonType", "toolBarButton")
            it.putClientProperty("JComponent.minimumWidth", 0)
            it.margin = Insets(2, 6, 2, 6)
        }
        border = object : AbstractBorder() {
            override fun getBorderInsets(c: Component, insets: Insets): Insets = insets.apply {
                val size = UIScale.scale(1)
                set(size, size, size, size)
            }
            override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
                g.color = UIManager.getColor("Component.borderColor") ?: UIManager.getColor("Separator.foreground") ?: Color.GRAY
                g.drawRect(x, y, width - 1, height - 1)
            }
        }
        primary.isEnabled = false
        arrow.isEnabled = false
        primary.addActionListener { dispatch(OcrWorkflowCommand.DEFAULT) }
        arrow.addActionListener { if (arrow.isShowing) menu().show(arrow, 0, arrow.height) }
        primary.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ocrDefault")
        primary.actionMap.put("ocrDefault", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { primary.doClick() }
        })
        primary.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK), "ocrMenu")
        primary.actionMap.put("ocrMenu", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { arrow.doClick() }
        })
        add(primary, BorderLayout.CENTER)
        add(arrow, BorderLayout.LINE_END)
    }

    override fun paintChildren(g: Graphics) {
        super.paintChildren(g)
        val seam = if (componentOrientation.isLeftToRight) arrow.x else arrow.x + arrow.width - 1
        g.color = UIManager.getColor("Component.borderColor") ?: UIManager.getColor("Separator.foreground") ?: Color.GRAY
        g.drawLine(seam, insets.top, seam, height - insets.bottom - 1)
    }

    fun render(labels: OcrSplitLabels, enabled: Boolean) {
        this.labels = labels
        primary.toolTipText = labels.defaultTooltip
        primary.accessibleContext.accessibleName = labels.defaultTooltip
        arrow.toolTipText = labels.chooseAction
        arrow.accessibleContext.accessibleName = labels.chooseAction
        primary.isEnabled = enabled
        arrow.isEnabled = enabled
    }

    internal fun menu(): JPopupMenu = JPopupMenu().apply {
        val text = requireNotNull(labels)
        listOf(
            text.showActions to OcrWorkflowCommand.SHOW_ACTIONS,
            text.translate to OcrWorkflowCommand.TRANSLATE,
            text.copyText to OcrWorkflowCommand.COPY_TEXT,
            text.copyImage to OcrWorkflowCommand.COPY_IMAGE,
            text.workspace to OcrWorkflowCommand.OPEN_WORKSPACE,
        ).forEach { (label, command) ->
            add(JMenuItem(label).apply {
                if (command == OcrWorkflowCommand.TRANSLATE) toolTipText = text.translateTooltip
                addActionListener { dispatch(command) }
            })
        }
        addSeparator()
        add(JMenuItem(text.settings).apply { addActionListener { openSettings() } })
    }
}

data class OcrSplitLabels(
    val defaultTooltip: String,
    val chooseAction: String,
    val showActions: String,
    val translate: String,
    val translateTooltip: String,
    val copyText: String,
    val copyImage: String,
    val workspace: String,
    val settings: String,
)
