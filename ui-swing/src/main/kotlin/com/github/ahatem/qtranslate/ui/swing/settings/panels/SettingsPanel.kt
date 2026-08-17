package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import javax.swing.*
import javax.swing.border.AbstractBorder
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet

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

    /**
     * One searchable setting.
     *
     * @param label    what the user reads: a checkbox's text or a row's label.
     * @param section  the [addSeparator] heading it sits under, for disambiguating rows that
     *                 share a label across popups.
     * @param hint     the explanatory text beneath it, if any. Searched too, so "fades" finds the
     *                 idle timeout even though the word appears nowhere in its label.
     * @param anchor   the component to reveal when the result is chosen.
     */
    data class SettingEntry(
        val label: String,
        val section: String,
        val hint: String,
        val anchor: JComponent
    )

    /**
     * Every setting on this page, collected as it is built.
     *
     * Gathered by instrumenting the layout helpers rather than by hand-registering each setting,
     * so a new row is searchable by virtue of being added at all. A registry maintained
     * separately would be wrong the first time someone forgot it, and silently.
     */
    val searchEntries: List<SettingEntry> get() = entries

    private val entries = mutableListOf<SettingEntry>()
    private var currentSection: String = ""

    /** Every hint and its source text, so the markup can be regenerated per layout direction. */
    private val hintLabels = mutableListOf<Pair<JLabel, String>>()

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
        currentSection = title
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
        // Sub-sections refine the section rather than replacing it, so search results read
        // "Popups › Dictionary Popup" and the three identical "Hide after idle" rows stay apart.
        currentSection = currentSection.substringBefore(SECTION_SEPARATOR).trim()
            .let { if (it.isEmpty()) title else "$it $SECTION_SEPARATOR $title" }
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

                // The line fills whatever the label leaves, so it runs from the label's
                // trailing edge to the panel's trailing edge. FlowLayout.LEADING puts the
                // label on the right in a right-to-left interface, so measuring from the
                // label's right edge and drawing to `width` produced a start beyond the
                // panel: the guard below returned every time and the line simply never
                // appeared, leaving the heading looking unfinished.
                val (startX, endX) = if (componentOrientation.isLeftToRight) {
                    (titleLabel.x + titleLabel.width + gap) to width
                } else {
                    0 to (titleLabel.x - gap)
                }
                if (endX <= startX) return
                g.color = UIManager.getColor("Separator.foreground")
                    ?: UIManager.getColor("Component.borderColor")
                    ?: Color.GRAY
                g.drawLine(startX, centerY, endX, centerY)
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
        entries += SettingEntry(text, currentSection, hint = "", anchor = cb)
        return cb
    }

    /**
     * Adds a `label : component` row, with an optional trailing [suffix] label.
     */
    protected fun addRow(label: String, component: JComponent, suffix: String? = null) {
        val labelComponent = JLabel(label)
        gb.nextRow().add(labelComponent)
        if (suffix != null) {
            gb.weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(component)
            gb.weightX(0.0).fill(GridBagConstraints.NONE).add(JLabel(suffix))
        } else {
            gb.weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(component)
        }
        // Anchored on the label, not the control: the label is what carries the words being
        // searched for, and revealing it brings the control beside it into view anyway.
        entries += SettingEntry(label, currentSection, hint = "", anchor = labelComponent)
    }

    /**
     * A picker with the actions that operate on it, on one row.
     *
     * Two panels solved this independently and differently — one put labelled buttons on a row of
     * its own beneath the combo, the other put icon buttons inline — so the settings dialog
     * offered two answers to one question, two clicks apart. There was no helper for it, which is
     * how the drift happened, so this is the helper.
     *
     * The actions sit against the control they act on, which is what makes "edit" read as "edit
     * this one" rather than as a feature of the page.
     */
    protected fun addPickerRow(label: String, picker: JComponent, actions: List<JComponent>) {
        val row = JPanel(BorderLayout(UIScale.scale(6), 0)).apply {
            isOpaque = false
            add(picker, BorderLayout.CENTER)
            if (actions.isNotEmpty()) {
                add(JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(4), 0)).apply {
                    isOpaque = false
                    actions.forEach { add(it) }
                }, BorderLayout.LINE_END)
            }
        }
        addRow(label, row)
    }

    /**
     * A square, borderless action for [addPickerRow].
     *
     * Pinned to a fixed square because a toolbar button otherwise takes its size from the glyph
     * inside it, and a dense icon then sits visibly larger than a sparse one beside it. Focusable,
     * because these are sometimes the only route to a feature.
     */
    protected fun pickerAction(iconPath: String, tooltip: String, onClick: () -> Unit) =
        JButton(
            // Unscaled on purpose. FlatSVGIcon scales the size it is given by the user scale
            // factor itself, in scaleSize(), so passing UIScale.scale() here applies it twice:
            // correct at 100% and half again too big at 150%, crammed into a button sized once.
            IconSet.load(iconPath, PICKER_ICON, PICKER_ICON)
                .applyForegroundColorFilter()
        ).apply {
            toolTipText = tooltip
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
            val side = UIScale.scale(PICKER_BUTTON)
            preferredSize = Dimension(side, side)
            minimumSize = preferredSize
            maximumSize = preferredSize
            addActionListener { onClick() }
        }

    /**
     * Adds a helper/description label below the current row in muted, smaller text.
     * Uses an HTML width constraint so long text wraps instead of growing the dialog.
     */
    protected fun addHint(text: String) {
        val hint = JLabel(hintHtml(text)).apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            font = font.deriveFont(font.size - 1f)
        }
        hintLabels += hint to text
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(0, 2, 4, 0)
            .add(hint)

        // Attached to the setting above rather than indexed on its own, so searching for a word
        // that only appears in the explanation still finds the control it explains.
        entries.lastOrNull()?.let { entries[entries.lastIndex] = it.copy(hint = text) }
    }

    /**
     * Renders a hint's HTML for the current layout direction.
     *
     * The fixed measure is what made this direction-sensitive. The label stretches across the row
     * but the HTML block inside it does not, so the block sits at the leading edge while the text
     * inside it stays aligned to the left whatever the component does. In Arabic that left the
     * words starting a full measure in from the right edge, which reads as centred rather than as
     * text that has simply been aligned the wrong way.
     *
     * `dir` is set as well as `text-align` so punctuation and any embedded Latin resolve against
     * the right base direction instead of being reordered at the end of the line.
     */
    private fun hintHtml(text: String): String {
        val html = text.replace("\n", "<br>")
        // Swing does not scale pixel widths inside HTML, so an unscaled figure here wraps the
        // hint at half the intended measure on a 200% display while the font around it doubles.
        val width = UIScale.scale(HINT_WIDTH)
        val leftToRight = componentOrientation.isLeftToRight
        val dir = if (leftToRight) "ltr" else "rtl"
        val align = if (leftToRight) "left" else "right"
        return "<html><body dir='$dir' style='width:${width}px; text-align:$align'>" +
            "<i>$html</i></body></html>"
    }

    /**
     * Rebuilds anything whose rendering was derived from the layout direction.
     *
     * Panels are built before the dialog flips them, so every hint is first laid out
     * left-to-right. `applyComponentOrientation` moves components but cannot know that a string of
     * HTML was generated from the old direction, so the markup is regenerated here. This also
     * covers the user changing the interface language while the dialog is open.
     */
    override fun applyComponentOrientation(orientation: ComponentOrientation) {
        super.applyComponentOrientation(orientation)
        hintLabels.forEach { (label, text) -> label.text = hintHtml(text) }
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

    protected companion object {
        /** Measure a hint wraps at, before scaling. Roughly a comfortable line of prose. */
        const val HINT_WIDTH = 460

        /** Separates a section from a sub-section in a search result's path. */
        const val SECTION_SEPARATOR = "›"

        /** Glyph size for a picker's inline actions. */
        const val PICKER_ICON = 14

        /** Square side for those actions, so a dense glyph never outgrows a sparse one. */
        const val PICKER_BUTTON = 26
    }
}
