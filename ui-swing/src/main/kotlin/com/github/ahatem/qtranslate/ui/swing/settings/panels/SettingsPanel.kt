package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import javax.swing.*

/**
 * Base class for all settings panels.
 *
 * ### Layout
 * Built with [GridBag]. Each panel calls [addSeparator], [addCheckbox], [addRow],
 * and [finishLayout] in sequence inside its own `buildUI()`.
 *
 * ### State dispatch
 * Use [applyDraft] for every setting change — it reads state atomically from the
 * store and dispatches a single [SettingsIntent.UpdateDraft], preventing the
 * stale-read race that occurs when reading `store.state.value` directly in listeners:
 *
 * ```kotlin
 * applyDraft(store) { it.copy(isHistoryEnabled = enabled) }
 * ```
 *
 * ### Render guard
 * Wrap all state-driven UI updates in [withoutTrigger] to prevent listener
 * callbacks from firing while the UI is being populated from state.
 */
abstract class SettingsPanel : JPanel(), Renderable<SettingsState> {

    @Volatile
    protected var isUpdatingFromState = false

    protected val gb = GridBag(this, horizontalGap = 8, verticalGap = 4)

    init {
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        gb.defaultAnchor(GridBagConstraints.LINE_START)
        gb.defaultFill(GridBagConstraints.NONE)
    }

    // -------------------------------------------------------------------------
    // Safe state dispatch
    // -------------------------------------------------------------------------

    /**
     * Reads the current working configuration atomically from [store] and dispatches
     * an [SettingsIntent.UpdateDraft] with the result of [transform].
     *
     * Always use this instead of `store.state.value.workingConfiguration.copy(...)` directly,
     * which risks reading stale state if two changes fire in rapid succession.
     */
    protected fun applyDraft(store: SettingsStore, transform: (Configuration) -> Configuration) {
        val current = store.state.value.workingConfiguration
        store.dispatch(SettingsIntent.UpdateDraft(transform(current)))
    }

    // -------------------------------------------------------------------------
    // Render guard
    // -------------------------------------------------------------------------

    protected inline fun <R> withoutTrigger(block: () -> R): R {
        val prev = isUpdatingFromState
        isUpdatingFromState = true
        return try { block() } finally { isUpdatingFromState = prev }
    }

    // -------------------------------------------------------------------------
    // Layout helpers
    // -------------------------------------------------------------------------

    /**
     * Adds a section header: a bold title with a subtle left accent bar.
     * Adds extra top spacing for all sections after the first.
     */
    protected fun addSeparator(title: String) {
        val isFirst = gb.currentY == 0

        // Accent bar + title in a single row
        val accentColor = UIManager.getColor("Component.accentColor")
            ?: UIManager.getColor("Button.default.background")
            ?: Color(0x4A90D9)

        val accentBar = object : JPanel() {
            override fun getPreferredSize() = java.awt.Dimension(3, 16)
            override fun paintComponent(g: java.awt.Graphics) {
                super.paintComponent(g)
                g.color = accentColor
                g.fillRect(0, 0, width, height)
            }
        }.apply { isOpaque = false }

        val titleLabel = JLabel(title).apply {
            font = font.deriveFont(Font.BOLD, font.size + 0.5f)
        }

        val headerRow = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(accentBar,  BorderLayout.LINE_START)
            add(titleLabel, BorderLayout.CENTER)
        }

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(if (isFirst) 0 else 20, 0, 6, 0)
            .add(headerRow)
    }

    /**
     * Adds a full-width checkbox that fires [onChange] only when the user acts
     * (not when [withoutTrigger] is active).
     */
    protected fun addCheckbox(
        text: String,
        selected: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit
    ): JCheckBox {
        val cb = JCheckBox(text, selected).apply {
            isEnabled = enabled
            addActionListener { if (!isUpdatingFromState) onChange(isSelected) }
        }
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .add(cb)
        return cb
    }

    /**
     * Adds a `label : component` row, with an optional trailing [suffix] label.
     */
    protected fun addRow(label: String, component: JComponent, suffix: String? = null) {
        gb.nextRow().add(JLabel(label))
        if (suffix != null) {
            gb.weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(component)
            gb.weightX(0.0).fill(GridBagConstraints.NONE).add(JLabel(suffix))
        } else {
            gb.weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(component)
        }
    }

    /**
     * Adds a helper/description label below the current row in muted, smaller text.
     */
    protected fun addHint(text: String) {
        val html = text.replace("\n", "<br>")
        val hint = JLabel("<html><i>$html</i></html>").apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            font = font.deriveFont(font.size - 1f)
        }
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(0, 2, 0, 0)
            .add(hint)
    }

    /**
     * Pushes remaining space to the bottom so content stays top-aligned.
     * Always call at the end of `buildUI()`.
     */
    protected fun finishLayout() {
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .weightY(1.0)
            .fill(GridBagConstraints.BOTH)
            .add(Box.createVerticalGlue())
    }
}