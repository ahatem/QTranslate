package com.github.ahatem.qtranslate.ui.swing.main.history

import com.formdev.flatlaf.extras.components.FlatButton
import com.formdev.flatlaf.ui.FlatLineBorder
import com.github.ahatem.qtranslate.core.ocr.OcrWorkflowCommand
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import com.github.ahatem.qtranslate.ui.swing.shared.util.ThemeIcon
import com.formdev.flatlaf.util.UIScale
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.JSeparator
import javax.swing.SwingConstants
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

    /** The button face: the background and outline, drawn inside the room [updateUI] leaves for them. */
    private val face = object : JPanel(BorderLayout()) {
        override fun updateUI() {
            super.updateUI()
            background = UIManager.getColor("Button.background") ?: UIManager.getColor("Panel.background")
        }
    }

    init {
        isOpaque = false
        face.border = object : FlatLineBorder(Insets(1, 1, 1, 1), null, 1f, -1) {
            override fun getLineColor() = UIManager.getColor("Button.startBorderColor")
                ?: UIManager.getColor("Component.borderColor")
            override fun getArc(): Int = UIManager.getInt("Button.arc")
        }
        listOf(primary, arrow).forEach {
            it.buttonType = FlatButton.ButtonType.toolBarButton
            it.isFocusable = true
            it.isRequestFocusEnabled = false
            it.putClientProperty("JComponent.minimumWidth", 0)
        }
        // No vertical margin; the height the row sees is taken from an icon button (see
        // getPreferredSize), so the split button stands exactly as tall as the icon buttons beside it.
        val margin = UIManager.getInsets("Button.toolbar.margin") ?: Insets(3, 3, 3, 3)
        primary.margin = Insets(0, margin.left, 0, margin.left)
        arrow.margin = Insets(0, 2, 0, 2)
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
        face.add(primary, BorderLayout.LINE_START)
        face.add(JSeparator(SwingConstants.VERTICAL), BorderLayout.CENTER)
        face.add(arrow, BorderLayout.LINE_END)
        add(face, BorderLayout.CENTER)
    }

    /** The height of an icon button, which this button matches. Measured again after a theme change. */
    private var iconButtonHeight = 0

    override fun updateUI() {
        super.updateUI()
        iconButtonHeight = 0
        // A FlatLaf button keeps room for its focus ring inside its bounds and draws its outline
        // inside that room. The face is drawn the same way, so its outline is exactly as tall as an
        // icon button's.
        val room = UIScale.scale(UIManager.getInt("Component.focusWidth"))
        border = BorderFactory.createEmptyBorder(room, room, room, room)
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        if (iconButtonHeight == 0) {
            iconButtonHeight = FlatButton().apply { icon = IconSet.load(Icons.OCR, 16, 16) }.preferredSize.height
        }
        // The application font is taller than a 16px icon, so left to the text this button would
        // stand above its neighbours. The text is centred and its glyphs are shorter than the
        // button, so only the line's spare height is given up.
        return Dimension(size.width, iconButtonHeight)
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
