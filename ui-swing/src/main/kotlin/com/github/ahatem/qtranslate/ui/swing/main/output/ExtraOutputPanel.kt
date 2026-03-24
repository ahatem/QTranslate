package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.extras.components.FlatButton
import com.github.ahatem.qtranslate.api.rewriter.RewriteStyle
import com.github.ahatem.qtranslate.api.summarizer.SummaryLength
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.ui.swing.main.widgets.ReadOnlyTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.widgets.ReadOnlyTextPanelState
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Insets
import javax.swing.*

/**
 * Why callbacks on the state, not constructor lambdas?
 * The callbacks close over `config.copy(...)` in [com.github.ahatem.qtranslate.ui.swing.main.MainContentView.renderComponents].
 * Putting them on the constructor would require holding a stale config reference.
 * State-owned callbacks are re-evaluated on every render, always fresh.
 */
class ExtraOutputPanel(
    private val iconManager: IconManager,
    onListen: (text: String) -> Unit,
    onTranslateRequest: (text: String) -> Unit
) : JPanel(BorderLayout()), Renderable<ExtraOutputState> {

    private val textPane = AdvancedTextPane(
        onTextChanged = { /* Read-only */ },
        onListenRequest = onListen,
        onTranslateRequest = onTranslateRequest
    )
    private val actionsPanel = TextActionsPanel(iconManager)
    private val readOnlyPanel = ReadOnlyTextPanel(textPane, actionsPanel)

    private val backwardBtn = makeToggle()
    private val summaryBtn = makeToggle()
    private val rewriteBtn = makeToggle()

    init {
        ButtonGroup().also {
            it.add(backwardBtn); it.add(summaryBtn); it.add(rewriteBtn)
        }
    }

    private val gearBtn = createButtonWithIcon(iconManager, "icons/lucide/settings.svg", 16).apply {
        buttonType = FlatButton.ButtonType.toolBarButton
        isFocusable = false
        isVisible = false
        margin = Insets(2, 4, 2, 4)
    }

    private val headerBar = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(
            2, 0, 2, 0,
        )
        val togglesPanel = JPanel(FlowLayout(FlowLayout.LEADING, 2, 2)).apply {
            isOpaque = false
            add(backwardBtn)
            add(summaryBtn)
            add(rewriteBtn)
        }
        val gearPanel = JPanel(FlowLayout(FlowLayout.TRAILING, 0, 2)).apply {
            isOpaque = false
            add(gearBtn)
        }
        add(togglesPanel, BorderLayout.LINE_START)
        add(gearPanel, BorderLayout.LINE_END)
    }

    private var currentState: ExtraOutputState? = null

    init {
        add(headerBar, BorderLayout.NORTH)
        add(readOnlyPanel, BorderLayout.CENTER)

        backwardBtn.addActionListener {
            if (backwardBtn.isSelected)
                currentState?.onTypeChanged?.invoke(ExtraOutputType.BackwardTranslate)
        }
        summaryBtn.addActionListener {
            if (summaryBtn.isSelected)
                currentState?.onTypeChanged?.invoke(ExtraOutputType.Summarize)
        }
        rewriteBtn.addActionListener {
            if (rewriteBtn.isSelected)
                currentState?.onTypeChanged?.invoke(ExtraOutputType.Rewrite)
        }
        gearBtn.addActionListener {
            val state = currentState ?: return@addActionListener
            buildConfigPopup(state).show(gearBtn, 0, gearBtn.height)
        }
    }

    override fun render(state: ExtraOutputState) {
        isVisible = state.isVisible
        if (!isVisible) return

        currentState = state

        backwardBtn.text = state.labelBackward
        summaryBtn.text = state.labelSummary
        rewriteBtn.text = state.labelRewrite
        gearBtn.toolTipText = state.labelConfigure

        backwardBtn.model.isSelected = state.activeType == ExtraOutputType.BackwardTranslate
        summaryBtn.model.isSelected = state.activeType == ExtraOutputType.Summarize
        rewriteBtn.model.isSelected = state.activeType == ExtraOutputType.Rewrite

        gearBtn.isVisible = state.activeType == ExtraOutputType.Summarize || state.activeType == ExtraOutputType.Rewrite

        readOnlyPanel.render(
            ReadOnlyTextPanelState(
                text = state.text,
                isVisible = true,
                isLoading = state.isLoading,
                fontConfig = state.fontConfig,
                fallbackFontConfig = state.fallbackFontConfig,
                actionsState = state.actionsState
            )
        )
    }

    private fun buildConfigPopup(state: ExtraOutputState): JPopupMenu = JPopupMenu().apply {
        when (state.activeType) {
            ExtraOutputType.Summarize -> {
                SummaryLength.entries.forEachIndexed { i, length ->
                    val label = state.summaryLengthLabels.getOrElse(i) { length.name }
                    add(JMenuItem(label).apply {
                        isEnabled = length != state.summaryLength
                        addActionListener { state.onSummaryLengthChanged(length) }
                    })
                }
            }

            ExtraOutputType.Rewrite -> {
                RewriteStyle.entries.forEachIndexed { i, style ->
                    val label = state.rewriteStyleLabels.getOrElse(i) { style.name }
                    add(JMenuItem(label).apply {
                        isEnabled = style != state.rewriteStyle
                        addActionListener { state.onRewriteStyleChanged(style) }
                    })
                }
            }

            else -> Unit
        }
    }

    private fun makeToggle() = JToggleButton().apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        margin = Insets(3, 8, 3, 8)
    }
}