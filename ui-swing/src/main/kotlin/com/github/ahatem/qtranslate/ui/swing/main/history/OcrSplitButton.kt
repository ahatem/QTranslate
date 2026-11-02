package com.github.ahatem.qtranslate.ui.swing.main.history

import com.formdev.flatlaf.extras.components.FlatButton
import com.github.ahatem.qtranslate.core.ocr.OcrWorkflowCommand
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import com.github.ahatem.qtranslate.ui.swing.shared.util.ThemeIcon
import java.awt.BorderLayout
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.UIManager

/** Main window entry point. Resolution of DEFAULT remains with the OCR workflow. */
internal class OcrSplitButton(
    private val dispatch: (OcrWorkflowCommand) -> Unit,
    private val openSettings: () -> Unit,
) : JPanel(BorderLayout()) {
    internal val primary = FlatButton().apply {
        text = "OCR"
        icon = IconSet.load(Icons.OCR, 16, 16).applyForegroundColorFilter()
    }
    internal val arrow = FlatButton().apply {
        icon = ThemeIcon("Table.descendingSortIcon")
    }
    private var labels: OcrSplitLabels? = null

    init {
        isOpaque = false
        listOf(primary, arrow).forEach {
            it.buttonType = FlatButton.ButtonType.toolBarButton
            it.isFocusable = true
            it.isRequestFocusEnabled = false
            it.putClientProperty("JComponent.minimumWidth", 0)
        }
        val margin = UIManager.getInsets("Button.toolbar.margin") ?: Insets(3, 3, 3, 3)
        primary.margin = Insets(margin.top, margin.left, margin.bottom, 2)
        arrow.margin = Insets(margin.top, 2, margin.bottom, margin.right)
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
