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
 * store and dispatches a single [SettingsIntent.TransformDraft], preventing the
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

    /** Each explanation and lead paragraph, with the lines it was broken into. */
    private val textLabels = mutableListOf<TextEntry>()

    @Volatile
    protected var isUpdatingFromState = false

    /**
     * The layout receiving rows. Normally the panel itself; [addCollapsibleSection] points it at a
     * child container while that section's rows are built, so the same helpers can fill an area
     * that hides as a unit.
     */
    protected var gb = GridBag(this, horizontalGap = 8, verticalGap = ROW_GAP)

    init {
        border = BorderFactory.createEmptyBorder(UIScale.scale(EDGE), UIScale.scale(EDGE), UIScale.scale(EDGE), UIScale.scale(EDGE))
        gb.defaultAnchor(GridBagConstraints.LINE_START)
        gb.defaultFill(GridBagConstraints.NONE)
    }

    // -------------------------------------------------------------------------
    // Safe state dispatch
    // -------------------------------------------------------------------------

    /**
     * Reads the current working configuration atomically from [store] and dispatches
     * an [SettingsIntent.TransformDraft] with the result of [transform].
     *
     * Always use this instead of `store.state.value.workingConfiguration.copy(...)` directly,
     * which risks reading stale state if two changes fire in rapid succession.
     */
    protected fun applyDraft(store: SettingsStore, transform: (Configuration) -> Configuration) {
        store.dispatch(SettingsIntent.TransformDraft(transform))
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
     * Adds a section header: the page's largest text, with a hairline rule running from the end of
     * the title to the trailing edge.
     *
     * This is the top of the page's hierarchy, and it has to be recognisable as such at a glance —
     * see [SettingsVisuals] for the four levels and why they were separated. The rule is drawn in
     * `paintComponent` after layout, so it is centred on the label's own middle whatever the font
     * metrics or theme, and its color is read at paint time so a theme change cannot leave it stale.
     *
     * Sections are separated by [SECTION_GAP], which is the only gap on the page meant to be
     * noticed by the eye rather than merely tolerated.
     *
     * [trailing] is a control shown at the heading's trailing end, such as a section's Enabled checkbox.
     */
    protected fun addSeparator(title: String, trailing: JComponent? = null) {
        currentSection = title
        val isFirst = gb.currentY == 0
        val header = buildSeparatorRow(title, gap = UIScale.scale(SECTION_RULE_GAP))
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(if (isFirst) 0 else UIScale.scale(SECTION_GAP), 0, UIScale.scale(SECTION_RULE_BOTTOM), 0)
            .add(
                if (trailing == null) header
                else JPanel(BorderLayout(UIScale.scale(10), 0)).apply {
                    isOpaque = false
                    add(header, BorderLayout.CENTER)
                    add(trailing, BorderLayout.LINE_END)
                }
            )
    }

    /**
     * Adds a sub-section: a group heading plus the settings it owns, nested beneath it.
     *
     * The old helper here was a muted label with no container, so the rows under it were
     * indistinguishable from rows above it and the label itself sat within a shade and a point of
     * an ordinary setting's name. A heading that cannot be told from the thing it heads is not a
     * heading. So a sub-section now has three things an ordinary label does not:
     *
     * - **weight** — bold at body size (not shrunk, which is what made it look de-emphasised),
     * - **a marker** — a short accent-colored tick at the leading edge, in the application's gold,
     * - **ownership** — its settings are laid out in a container indented past the heading and
     *   joined to it by a hairline rail, so each row visibly belongs to the group above it.
     *
     * The rows are built by [build] with the usual helpers, and are still registered with Settings
     * search under the `Section › Sub-section` path, so the two levels of grouping are searchable
     * (three "Hide after idle" rows on the Popups page stay distinguishable).
     */
    protected fun addSubSection(title: String, build: () -> Unit) {
        // Entries registered while the group is built carry the sub-section in their path; rows
        // added after it belongs to the parent section again, so the path is restored afterwards.
        val parentSection = currentSection
        currentSection = if (parentSection.isEmpty()) title else "$parentSection $SECTION_SEPARATOR $title"

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(SUB_SECTION_GAP), 0, 0, 0)
            .add(SubSectionHeader(title))

        try {
            addNested(build)
        } finally {
            currentSection = parentSection
        }
    }

    /**
     * Adds a group of rows that are subordinate to whatever precedes them, without a heading.
     *
     * Some pages have a setting whose meaning depends on the one above it — a read-aloud voice that
     * only applies to one selection behaviour, history storage and the bar that shows it. Listed as
     * peers they read as independent switches that happen to sit next to each other, and the user
     * has to deduce the dependency from which control greys out. The rail is the difference between
     * "these two are related" and "these two are on the same page".
     */
    protected fun addNested(build: () -> Unit) {
        val container = SubSectionBody()
        val outer = gb
        gb = GridBag(container, horizontalGap = 8, verticalGap = ROW_GAP).apply {
            defaultAnchor(GridBagConstraints.LINE_START)
            defaultFill(GridBagConstraints.NONE)
        }
        try {
            build()
        } finally {
            gb = outer
        }

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .add(container)
    }

    /**
     * Shared factory for separator rows used by [addSeparator] and any subclass that
     * needs a consistent section header inside a nested panel (e.g. a detail pane).
     *
     * Returns a [JPanel] that:
     * - Renders [title] with the section font from [SettingsVisuals] using [FlowLayout.LEADING].
     * - Draws a horizontal line from the end of the label to the right edge in
     *   [paintComponent], at the exact vertical center of the label — guaranteeing
     *   visual alignment after layout regardless of font height or L&F specifics.
     * - Reads `Separator.foreground` (or `Component.borderColor` as fallback) at paint
     *   time — fully theme-aware with no property-change listeners needed.
     */
    protected fun buildSeparatorRow(title: String, gap: Int): JPanel {
        val titleLabel = JLabel(title).apply {
            font = SettingsVisuals.sectionTitleFont(font)
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
     * Adds a section that can be expanded and collapsed.
     *
     * Expert controls belong on the page but not in everyone's way, and a section that folds away
     * behind a named control says so without a second navigation level. Rows inside are built by
     * [build] with the usual helpers; search entries registered there still work, because they are
     * collected exactly as on any other page — the anchor simply lives in the folded area.
     */
    protected fun addCollapsibleSection(
        title: String,
        initiallyExpanded: Boolean = false,
        build: () -> Unit,
    ) {
        val content = JPanel(GridBagLayout()).apply { isOpaque = false }
        val outer = gb
        gb = GridBag(content, horizontalGap = 8, verticalGap = ROW_GAP).apply {
            defaultAnchor(GridBagConstraints.LINE_START)
            defaultFill(GridBagConstraints.NONE)
        }
        try {
            build()
        } finally {
            gb = outer
        }

        var expanded = initiallyExpanded
        content.isVisible = expanded

        val toggle = JCheckBox(title, expanded).apply {
            isOpaque = false
            addActionListener {
                expanded = isSelected
                content.isVisible = expanded
                revalidate()
                repaint()
            }
        }
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(SECTION_GAP), 0, UIScale.scale(2), 0)
            .add(toggle)
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .add(content)

        entries += SettingEntry(title, currentSection, hint = "", anchor = toggle)
    }

    /**
     * Adds a `label : component` row, with an optional trailing [suffix] label.
     *
     * The label is associated with the control through [JLabel.setLabelFor], which is what lets a
     * screen reader say "Proxy address, text field" instead of reading a floating word and an
     * unlabelled field. For a composite cell the association goes to its first focusable child, so
     * the row still names the control the user lands on.
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
        labelComponent.labelFor = firstFocusable(component) ?: component
        // Anchored on the label, not the control: the label is what carries the words being
        // searched for, and revealing it brings the control beside it into view anyway.
        entries += SettingEntry(label, currentSection, hint = "", anchor = labelComponent)
    }

    /**
     * The first focusable control inside [root], so a row can name the thing a user lands on.
     *
     * Two passes, because several pickers are built disabled and enabled once their contents have
     * loaded (the interface-language list, the font families). `JComboBox.isFocusable()` answers
     * `false` while it is disabled, so a single pass finds nothing and the label ends up pointing
     * at the row's container instead of at the picker, which a screen reader announces as
     * "Interface Language, panel". The second pass therefore accepts the
     * input controls that will take focus once enabled.
     */
    /**
     * The control a row's label should point at, so the row names the thing a user lands on.
     *
     * Two passes, because several pickers are built disabled and enabled once their contents have
     * loaded (the interface-language list, the font families). `JComboBox.isFocusable()` answers
     * `false` while it is disabled, so a single pass over "focusable components" finds nothing and
     * the label ends up pointing at the row's container, which a screen reader announces as
     * "Interface Language, panel".
     *
     * The first pass refuses containers. A composite row (a picker with its actions beside it, a
     * field with a button) is itself a `JPanel`, and a `JPanel` can report itself focusable; taking
     * it as "the control" is exactly the wrong answer, because the label then names the box rather
     * than what is inside it.
     */
    private fun firstFocusable(root: Component): JComponent? {
        val focusable = firstIn(root) { it.isFocusable && it !is Container }
        if (focusable != null) return focusable
        return firstIn(root) { component ->
            component is JComboBox<*> || component is JTextField || component is JSpinner ||
                component is JButton || component is JCheckBox || component is JTextArea
        }
    }

    /** Breadth-first search for the first [JComponent] (other than a label) matching [accept]. */
    private fun firstIn(root: Component, accept: (JComponent) -> Boolean): JComponent? {
        val queue = ArrayDeque<Component>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current is JComponent && current !is JLabel && accept(current)) return current
            if (current is Container) queue.addAll(current.components)
        }
        return null
    }

    /**
     * Registers a setting assembled outside [addCheckbox] or [addRow] with Settings search.
     *
     * Some controls live inside a composite widget, such as the service-role cards. They still
     * need a searchable label even though there is no standalone row label to anchor.
     */
    protected fun registerSearchEntry(label: String, anchor: JComponent, hint: String = "") {
        entries += SettingEntry(label, currentSection, hint, anchor)
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
            // A glyph with a tooltip is unlabelled to a screen reader; the tooltip's own words are
            // the only name this button has, so give them to the accessibility layer too.
            accessibleContext.accessibleName = tooltip
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
            val side = UIScale.scale(PICKER_BUTTON)
            preferredSize = Dimension(side, side)
            minimumSize = preferredSize
            maximumSize = preferredSize
            addActionListener { onClick() }
        }

    /**
     * Adds a helper/description label below the current row, in the measured secondary color.
     *
     * The color is [SettingsVisuals.secondaryText] rather than `Label.disabledForeground`: the
     * disabled color is chosen by the look and feel to mean "this control cannot be used", and it
     * is only incidentally dim. On some themes it came out close enough to body text that the
     * explanation competed with the setting it explained; on others it dropped under 4.5:1 and
     * stopped being comfortably readable. The token mutes the primary color as far as the contrast
     * floor allows, so the explanation is reliably quieter than the label and reliably legible.
     *
     * The italic treatment is kept: it is what distinguishes an explanation from a value at a
     * glance, and the size is only one point below body text for the same reason.
     *
     * Uses an HTML width constraint so long text wraps instead of growing the dialog.
     */
    protected fun addHint(text: String) = addTextRow(
        text = text,
        textFont = SettingsVisuals.hintFont(font),
        color = SettingsVisuals.secondaryText(),
        measure = HINT_WIDTH,
        gapTop = HINT_GAP_TOP,
        gapBottom = HINT_GAP_BOTTOM,
        explainedSetting = true,
    )

    /**
     * One wrapped block of text, drawn by a `JLabel`.
     *
     * The breaks are computed here and given to the label as explicit `<br>`s, for two reasons.
     * Swing's HTML renderer does not break *italic* Arabic at all — measured: the same sentence
     * wraps as upright text and stays on one line in italics, whatever width the body declares —
     * so long Arabic explanations were drawn wider than their row and clipped at both ends. And
     * relying on the renderer's own wrapping tied the text to a CSS width that it scales by the
     * display scale while the measurement had used `UIScale`, so at 150% the two disagreed by a
     * few pixels and clipped the first glyph.
     *
     * With the breaks already in the markup, the label only has to draw them — and drawing is what
     * the look and feel is good at. The text is antialiased exactly like every other label, because
     * it *is* every other label, rather than by hand.
     *
     * The row is a `FlowLayout(LEADING)` strip: the block sits at the leading edge and mirrors by
     * itself in a right-to-left interface.
     */
    private fun addTextRow(
        text: String,
        textFont: Font,
        color: Color,
        measure: Int,
        gapTop: Int,
        gapBottom: Int,
        explainedSetting: Boolean,
    ): JLabel {
        val lines = wrapToMeasure(text, textFont, UIScale.scale(measure))
        val label = JLabel(textHtml(lines)).apply {
            font = textFont
            foreground = color
        }
        textLabels += TextEntry(label, lines)
        val row = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
            isOpaque = false
            add(label)
        }
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(gapTop), 0, UIScale.scale(gapBottom), 0)
            .add(row)

        // Attached to the setting above rather than indexed on its own, so searching for a word
        // that only appears in the explanation still finds the control it explains.
        if (explainedSetting) {
            entries.lastOrNull()?.let { entries[entries.lastIndex] = it.copy(hint = text) }
        }
        return label
    }

    /** A block of text and the lines it was broken into, so the markup can be rebuilt. */
    private data class TextEntry(val label: JLabel, val lines: List<String>)

    /**
     * The markup for [lines]: the breaks are literal, the base direction follows the interface.
     *
     * `dir` is set so punctuation and any embedded Latin resolve against the right base direction
     * instead of being reordered to the wrong end of the line.
     */
    private fun textHtml(lines: List<String>): String {
        val body = lines.joinToString("<br>") { escapeHtml(it) }
        val dir = if (componentOrientation.isLeftToRight) "ltr" else "rtl"
        return "<html><body dir='$dir'>$body</body></html>"
    }

    /**
     * Breaks [text] into lines that fit [measure] when measured in [measureFont].
     *
     * A single word wider than the measure is left long: breaking inside a word would be wrong in
     * every language, and one long word is better than a mangled one.
     */
    private fun wrapToMeasure(text: String, measureFont: Font, measure: Int): List<String> {
        val metrics = getFontMetrics(measureFont)
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            val words = paragraph.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                lines += ""
                continue
            }
            var current = words.first()
            for (word in words.drop(1)) {
                val candidate = "$current $word"
                if (metrics.stringWidth(candidate) <= measure) {
                    current = candidate
                } else {
                    lines += current
                    current = word
                }
            }
            lines += current
        }
        return lines
    }

    /** Escapes the three characters Swing's HTML renderer would otherwise read as markup. */
    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    /**
     * Rebuilds the markup whose base direction was baked in, when the interface mirrors.
     *
     * Panels are built before the dialog applies its orientation, so every block is first laid out
     * left-to-right. The line breaks are direction-independent and are reused as measured.
     */
    override fun applyComponentOrientation(orientation: ComponentOrientation) {
        super.applyComponentOrientation(orientation)
        textLabels.forEach { entry -> entry.label.text = textHtml(entry.lines) }
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

    // -------------------------------------------------------------------------
    // Hierarchy containers
    // -------------------------------------------------------------------------

    /**
     * A sub-section heading: bold body-size text with a short accent tick at the leading edge.
     *
     * The tick is what makes this level recognisable without making it loud. A rule like the
     * section heading's would put the two levels back on top of each other, and a plain bold label
     * was what the page already had. The tick is accent-colored, which ties the grouping to the
     * application's own gold rather than introducing a second visual language.
     *
     * Two things about the geometry are deliberate. The indent is a *child* rather than a border:
     * `FlowLayout` honours a border's leading inset in a left-to-right interface and ignores it in
     * a right-to-left one, which left the Arabic heading flush against the right edge with the tick
     * drawn on top of the last letters. A spacer is ordered by the layout itself, so it indents the
     * same way in both directions. And the tick is placed from the label's own bounds rather than
     * from the panel's edge, so it lands in the margin even if the layout moves the text.
     *
     * Both are read at paint time, so a theme or direction change needs no rebuild.
     */
    private inner class SubSectionHeader(title: String) : JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)) {
        private val titleLabel = JLabel(title).apply {
            font = SettingsVisuals.subSectionTitleFont(font)
            foreground = SettingsVisuals.primaryText()
        }

        init {
            isOpaque = false
            // The heading's own text starts where its settings start, so the group reads as one
            // block: the tick sits in the margin beside both.
            add(Box.createHorizontalStrut(UIScale.scale(NESTED_INDENT)))
            add(titleLabel)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val markWidth = UIScale.scale(MARK_WIDTH)
                val gap = UIScale.scale(MARK_GAP)
                val markX = if (componentOrientation.isLeftToRight) {
                    titleLabel.x - gap - markWidth
                } else {
                    titleLabel.x + titleLabel.width + gap
                }
                // Sized to the text it names, so it reads as a marker for the heading rather than
                // as a stray rule running the height of the row.
                val markY = titleLabel.y
                val markHeight = titleLabel.height.coerceAtLeast(UIScale.scale(MARK_MIN_HEIGHT))
                g2.color = SettingsVisuals.accentMarker()
                val arc = UIScale.scale(MARK_WIDTH)
                g2.fillRoundRect(markX, markY, markWidth, markHeight, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * The container that holds a sub-section's — or any subordinate group's — rows.
     *
     * Its whole job is to say "the rows inside belong to the thing above". It indents past the
     * heading text so the group shares one left edge, and paints a hairline rail down that edge in
     * the theme's border color. No heading is needed for the dependent-options case ([addNested]),
     * where the rail alone carries the relationship.
     */
    private inner class SubSectionBody : JPanel() {
        init {
            isOpaque = false
            border = leadingInset(NESTED_INDENT)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val railX = if (componentOrientation.isLeftToRight) {
                UIScale.scale(RAIL_X)
            } else {
                width - UIScale.scale(RAIL_X) - UIScale.scale(RAIL_WIDTH)
            }
            val g2 = g.create() as Graphics2D
            try {
                g2.color = SettingsVisuals.rail()
                g2.fillRect(railX, 0, UIScale.scale(RAIL_WIDTH), height)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * A border that insets the leading edge only, resolving which edge that is at painting time.
     *
     * `EmptyBorder` takes absolute sides and therefore cannot follow `componentOrientation`, which
     * is applied after panels are built. A fixed left inset puts the whole indentation on the
     * trailing side in Arabic, where it read as ragged spacing instead of as nesting.
     */
    private fun leadingInset(leading: Int): javax.swing.border.Border = object : AbstractBorder() {
        private fun sides(c: Component?): Insets {
            val indent = UIScale.scale(leading)
            val leftToRight = (c as? JComponent)?.componentOrientation?.isLeftToRight ?: true
            return if (leftToRight) Insets(0, indent, 0, 0) else Insets(0, 0, 0, indent)
        }

        override fun getBorderInsets(c: Component?) = sides(c)
        override fun getBorderInsets(c: Component?, insets: Insets): Insets {
            val computed = sides(c)
            insets.set(computed.top, computed.left, computed.bottom, computed.right)
            return insets
        }
    }

    /**
     * The rhythm and hierarchy constants, internal rather than protected so the tests that measure
     * the layout can assert against these numbers instead of copies of them.
     */
    internal companion object {
        /**
         * The vertical rhythm, in one place.
         *
         * Four numbers chosen separately at four call sites drift apart: a section break
         * twenty-six pixels above its title while the rows under it sit four apart is, at 150%
         * scale, a forty-pixel hole between groups and a cramped list inside them. They are one
         * scale: attached, separated, sectioned. Every value here is scaled at its point of use,
         * not stored pre-scaled.
         *
         * The section gap is wide because at eleven pixels a section break is barely wider than
         * the gap between a sub-section and its own rows, so the two levels of grouping look
         * alike even when the type differs. Space is the cheapest hierarchy there is, and this is
         * the one gap meant to be noticed.
         */
        const val ROW_GAP = 4

        /** Space above a section title. The only gap meant to be noticed. */
        const val SECTION_GAP = 18

        /** Distance between a section title and the rule that runs from it to the trailing edge. */
        const val SECTION_RULE_GAP = 10

        /** Space below the rule, before the first row of the section. */
        const val SECTION_RULE_BOTTOM = 6

        /** Space above a sub-section title: clearly a new group, clearly inside its section. */
        const val SUB_SECTION_GAP = 13

        /**
         * How far a group's rows sit inside the page.
         *
         * The sub-section heading's text and its settings share this inset, so the heading names
         * the block and the block sits under it; the marker and rail live in the margin.
         */
        const val NESTED_INDENT = 15

        /** The accent tick's width, and the corner radius that keeps it from looking like a bar. */
        const val MARK_WIDTH = 3

        /** Gap between the tick and the heading's text. */
        const val MARK_GAP = 8

        /** A tick is drawn at least this tall when the heading's own row is shorter. */
        const val MARK_MIN_HEIGHT = 12

        /** The rail connecting a group's rows, drawn in the same margin as the tick. */
        const val RAIL_X = 5

        /** Hairline width. A rail is a hint of structure, not a border. */
        const val RAIL_WIDTH = 1

        /** Space between a setting and the explanation beneath it. */
        const val HINT_GAP_TOP = 2

        /** Space below an explanation, before the next setting. */
        const val HINT_GAP_BOTTOM = 4

        /** Padding around the page itself. */
        const val EDGE = 10

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
