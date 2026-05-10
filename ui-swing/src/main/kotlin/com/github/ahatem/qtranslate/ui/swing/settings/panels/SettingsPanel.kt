package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import javax.swing.*
import javax.swing.border.AbstractBorder

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
     * A [javax.swing.border.Border] that reads its color from [UIManager] at paint time,
     * so it always matches the active FlatLaf theme without any explicit update call.
     *
     * Drop-in replacement for `BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"))`
     * wherever the color needs to stay correct across theme switches.
     */
    protected fun themeAwareBorder(colorKey: String = "Component.borderColor"): javax.swing.border.Border =
        object : AbstractBorder() {
            override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
                g.color = UIManager.getColor(colorKey) ?: Color.GRAY
                g.drawRect(x, y, w - 1, h - 1)
            }
            override fun getBorderInsets(c: Component) = Insets(1, 1, 1, 1)
            override fun getBorderInsets(c: Component, insets: Insets): Insets {
                insets.set(1, 1, 1, 1); return insets
            }
            override fun isBorderOpaque() = false
        }

    /**
     * Adds a clean section header: bold title with a horizontal separator line that
     * extends from the end of the text to the right edge.
     *
     * The line is drawn via [paintComponent] after layout, so it is pixel-perfectly
     * centered with the label's vertical center regardless of font metrics or L&F.
     * Color is read at paint time — fully theme-aware without any manual refresh.
     *
     * Extra top spacing is added for all sections after the first so panels read as
     * clearly separated groups.
     */
    protected fun addSeparator(title: String) {
        val isFirst = gb.currentY == 0
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(if (isFirst) 0 else 22, 0, 6, 0)
            .add(buildSeparatorRow(title, bold = true, muted = false, gap = 10))
    }

    /**
     * Adds a lightweight sub-section label that visually subordinates to [addSeparator].
     *
     * Intentionally different from [addSeparator] — no extending line, just muted
     * text — so the two levels of hierarchy are immediately distinguishable:
     *
     *   `Section ──────────────`   ← addSeparator  (bold, full line, prominent)
     *   `  Sub-section`            ← addSubSeparator (muted label, indented, no line)
     */
    protected fun addSubSeparator(title: String) {
        val label = JLabel(title).apply {
            font       = font.deriveFont(Font.BOLD, font.size - 0.5f)
            foreground = UIManager.getColor("Label.disabledForeground")
        }
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(14, 4, 2, 0)
            .add(label)
    }

    /**
     * Shared factory for separator rows used by [addSeparator] and any subclass that
     * needs a consistent section header inside a nested panel (e.g. a detail pane).
     *
     * Returns a [JPanel] that:
     * - Renders [title] as a [JLabel] (bold / muted per params) using [FlowLayout.LEADING].
     * - Draws a horizontal line from the end of the label to the right edge in
     *   [paintComponent], at the exact vertical center of the label — guaranteeing
     *   visual alignment after layout regardless of font height or L&F specifics.
     * - Reads `Separator.foreground` (or `Component.borderColor` as fallback) at paint
     *   time — fully theme-aware with no property-change listeners needed.
     */
    protected fun buildSeparatorRow(
        title: String,
        bold: Boolean,
        muted: Boolean,
        gap: Int
    ): JPanel {
        val fontSize = if (muted) font.size - 0.5f else font.size.toFloat()
        val titleLabel = JLabel(title).apply {
            font = font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, fontSize)
            if (muted) foreground = UIManager.getColor("Label.disabledForeground")
        }
        return object : JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)) {
            init { isOpaque = false; add(titleLabel) }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                // Read the label center after layout — this is the Y coordinate of the
                // separator line that makes it appear visually aligned with the text.
                val centerY = titleLabel.y + titleLabel.height / 2
                val startX  = titleLabel.x + titleLabel.width + gap
                if (startX >= width) return
                g.color = UIManager.getColor("Separator.foreground")
                    ?: UIManager.getColor("Component.borderColor")
                    ?: Color.GRAY
                g.drawLine(startX, centerY, width, centerY)
            }
        }
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
     * Uses an HTML width constraint so long text wraps instead of growing the dialog.
     */
    protected fun addHint(text: String) {
        val html = text.replace("\n", "<br>")
        val hint = JLabel("<html><body style='width:460px'><i>$html</i></body></html>").apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            font = font.deriveFont(font.size - 1f)
        }
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(0, 2, 4, 0)
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
