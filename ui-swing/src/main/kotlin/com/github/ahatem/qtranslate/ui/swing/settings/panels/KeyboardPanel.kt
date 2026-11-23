package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.CommandRegistry
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyConflict
import com.github.ahatem.qtranslate.core.settings.data.HotkeyConflictDetector
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresetKind
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresets
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.main.MainGlobalKeyListener
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.formatChord
import com.github.ahatem.qtranslate.ui.swing.shared.util.withAlpha
import java.awt.*
import java.awt.image.BufferedImage
import java.util.Locale
import javax.accessibility.Accessible
import javax.accessibility.AccessibleAction
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.accessibility.AccessibleStateSet
import java.awt.event.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel

/**
 * The words the command list uses, keyed by stable names rather than assembled from them.
 *
 * Written out because the localization guards find keys by scanning the source for literals: a
 * key built as `"settings_hotkeys.describe_" + action.name.lowercase()` is invisible to them, so a
 * missing string or a typo would only surface as a dotted key on screen. Spelling them here also
 * makes "every command has exactly one explanation" something a test can assert directly.
 *
 * The registry still decides which commands exist. This only decides what they are called.
 */
internal object CommandExplorerText {

    /** One heading per registry category, spelled as `CommandSpec.category` spells it. */
    val categoryKeys: Map<String, String> = mapOf(
        "translation" to "settings_hotkeys.category_translation",
        "writing" to "settings_hotkeys.category_writing",
        "dictionary" to "settings_hotkeys.category_dictionary",
        "ocr" to "settings_hotkeys.category_ocr",
        "capture" to "settings_hotkeys.category_capture",
        "navigation" to "settings_hotkeys.category_navigation",
        "editing" to "settings_hotkeys.category_editing",
        "document" to "settings_hotkeys.category_document",
        "window" to "settings_hotkeys.category_window",
    )

    /** One explanation per command. */
    val descriptionKeys: Map<HotkeyAction, String> = mapOf(
        HotkeyAction.SHOW_MAIN_WINDOW to "settings_hotkeys.describe_show_main_window",
        HotkeyAction.SHOW_QUICK_TRANSLATE to "settings_hotkeys.describe_show_quick_translate",
        HotkeyAction.LISTEN_TO_TEXT to "settings_hotkeys.describe_listen_to_text",
        HotkeyAction.OPEN_OCR to "settings_hotkeys.describe_open_ocr",
        HotkeyAction.REPLACE_WITH_TRANSLATION to "settings_hotkeys.describe_replace_with_translation",
        HotkeyAction.REPLACE_WITH_REWRITE to "settings_hotkeys.describe_replace_with_rewrite",
        HotkeyAction.REPLACE_WITH_SUMMARY to "settings_hotkeys.describe_replace_with_summary",
        HotkeyAction.CYCLE_TARGET_LANGUAGE to "settings_hotkeys.describe_cycle_target_language",
        HotkeyAction.SHOW_DICTIONARY to "settings_hotkeys.describe_show_dictionary",
        HotkeyAction.SHOW_IMAGES to "settings_hotkeys.describe_show_images",
        HotkeyAction.TRANSLATE to "settings_hotkeys.describe_translate",
        HotkeyAction.FOCUS_INPUT to "settings_hotkeys.describe_focus_input",
        HotkeyAction.FOCUS_OUTPUT to "settings_hotkeys.describe_focus_output",
        HotkeyAction.FOCUS_EXTRA_OUTPUT to "settings_hotkeys.describe_focus_extra_output",
        HotkeyAction.COPY_TRANSLATION to "settings_hotkeys.describe_copy_translation",
        HotkeyAction.CLEAR_INPUT to "settings_hotkeys.describe_clear_input",
        HotkeyAction.SWAP_LANGUAGES to "settings_hotkeys.describe_swap_languages",
        HotkeyAction.OPEN_SETTINGS to "settings_hotkeys.describe_open_settings",
        HotkeyAction.SHOW_HISTORY to "settings_hotkeys.describe_show_history",
        HotkeyAction.TRANSLATE_DOCUMENT to "settings_hotkeys.describe_translate_document",
        HotkeyAction.OCR_SHOW_ACTIONS to "settings_hotkeys.describe_ocr_show_actions",
        HotkeyAction.OCR_TRANSLATE to "settings_hotkeys.describe_ocr_translate",
        HotkeyAction.OCR_TRANSLATE_QUICK to "settings_hotkeys.describe_ocr_translate_quick",
        HotkeyAction.OCR_TRANSLATE_MAIN to "settings_hotkeys.describe_ocr_translate_main",
        HotkeyAction.OCR_OPEN_WORKSPACE to "settings_hotkeys.describe_ocr_open_workspace",
        HotkeyAction.OCR_COPY_TEXT to "settings_hotkeys.describe_ocr_copy_text",
        HotkeyAction.OCR_COPY_IMAGE to "settings_hotkeys.describe_ocr_copy_image",
        HotkeyAction.OCR_ACTION_TRANSLATE to "settings_hotkeys.describe_ocr_action_translate",
        HotkeyAction.OCR_ACTION_COPY_TEXT to "settings_hotkeys.describe_ocr_action_copy_text",
        HotkeyAction.OCR_ACTION_COPY_IMAGE to "settings_hotkeys.describe_ocr_action_copy_image",
        HotkeyAction.OCR_ACTION_SAVE_IMAGE to "settings_hotkeys.describe_ocr_action_save_image",
        HotkeyAction.OCR_ACTION_RECROP to "settings_hotkeys.describe_ocr_action_recrop",
        HotkeyAction.OCR_ACTION_CANCEL to "settings_hotkeys.describe_ocr_action_cancel",
    )
}

/**
 * The two things a row offers, besides its cells.
 *
 * They live in their own column rather than in a button row under the list: changing a shortcut
 * is what this page is for, and reaching the bottom of a 42-row list to do it was the slowest
 * path to the only action on screen.
 */
internal enum class CommandRowAction { EDIT, CLEAR }

/**
 * Where a row's actions sit, in one place so painting and hit-testing cannot disagree.
 *
 * The pair is anchored to the trailing edge of its column and mirrored for right-to-left, which
 * is what makes right-to-left layouts put them where a right-to-left reader expects them rather
 * than stranded on the far side of the row.
 */
internal object CommandRowActions {

    /**
     * The two action squares in a cell of [columnWidth] by [height].
     *
     * Both are square and the same size, so the pair reads as one control group; the second
     * ([CommandRowAction.CLEAR]) is the outer one, nearest the trailing edge.
     */
    fun bounds(
        columnWidth: Int,
        height: Int,
        leftToRight: Boolean,
        side: Int,
        gap: Int,
        margin: Int,
    ): List<Pair<CommandRowAction, Rectangle>> {
        val y = ((height - side) / 2).coerceAtLeast(0)
        val inner = if (leftToRight) columnWidth - margin - side else margin
        val outer = if (leftToRight) inner - gap - side else inner + side + gap
        return listOf(
            CommandRowAction.EDIT to Rectangle(outer, y, side, side),
            CommandRowAction.CLEAR to Rectangle(inner, y, side, side),
        )
    }

    /** The action whose square holds [x], or null. */
    fun hit(
        x: Int,
        columnWidth: Int,
        height: Int,
        leftToRight: Boolean,
        side: Int,
        gap: Int,
        margin: Int,
    ): CommandRowAction? = bounds(columnWidth, height, leftToRight, side, gap, margin)
        .firstOrNull { (_, rect) -> rect.contains(x, rect.y + rect.height / 2) }
        ?.first
}

/**
 * One glyph drawn in one colour, rendered once and kept.
 *
 * The row-action squares are painted for every visible row on every repaint, and between them only
 * the colour varies — disabled, idle, selected, hovered. Rasterising the glyph and recolouring it
 * for every square allocated a fresh image dozens of times per scroll of the list, which is
 * wasteful in exactly the situation the list is used in.
 *
 * Tinting is [AlphaComposite.SrcIn] rather than a pixel walk: it keeps the glyph's own antialiased
 * coverage and replaces only its colour, so a 14-pixel icon needs one fill and no per-pixel maths.
 */
internal class TintedGlyph(private val icon: Icon) {

    /** Colour (ARGB) to the glyph drawn in it. Five states per row, at most, ever seen. */
    private val cache = HashMap<Int, Icon>()

    /** Only an image observer for the icon, never shown. */
    private val host = JLabel()

    /** [icon] in [color], rendered the first time this colour is asked for. */
    fun forColor(color: Color): Icon {
        val argb = (color.alpha shl 24) or (color.rgb and 0xFFFFFF)
        return cache.getOrPut(argb) { render(color) }
    }

    /** Drops every cached copy: a new theme or display scale means new colours and sizes. */
    fun invalidate() = cache.clear()

    private fun render(color: Color): Icon {
        val width = icon.iconWidth.coerceAtLeast(1)
        val height = icon.iconHeight.coerceAtLeast(1)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            icon.paintIcon(host, g, 0, 0)
            g.composite = AlphaComposite.SrcIn
            g.color = color
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return ImageIcon(image)
    }
}

/**
 * Whether a wheel gesture can still move [bar] in the direction [rotation] asks for.
 *
 * The check that makes nested scrolling behave: a scroll bar at either end reports no room, and
 * the gesture belongs to whatever encloses the list. Asking the bar rather than the event matters
 * because Swing's scroll-pane handler consumes wheel events unconditionally — including the ones
 * it cannot act on — which is how a list with nothing left to scroll kept the page from moving.
 */
internal fun scrollBarCanMove(bar: JScrollBar, rotation: Int): Boolean {
    val travel = bar.maximum - bar.visibleAmount - bar.minimum
    if (travel <= 0) return false
    return when {
        rotation < 0 -> bar.value > bar.minimum
        rotation > 0 -> bar.value < bar.minimum + travel
        else -> false
    }
}

/**
 * The command reference and hotkey editor, in one place.
 *
 * This panel is the only list of commands in the application: one row per
 * [HotkeyAction], grouped by the registry's own `category`, with what the command does and
 * what it needs. It deliberately does not keep a second catalogue of its own — the registry
 * already holds identity, category, localized name and default binding, and duplicating that
 * is how the two drift apart.
 *
 * `SHOW_MAIN_WINDOW` is the one exception to "every command is a table row": it has its own
 * group above the table, because its shortcut is a double-Ctrl gesture with its own toggle
 * rather than a key combination. It is still represented exactly once.
 */
class KeyboardPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    /** Called just before the hotkey recorder dialog opens to prevent accidental triggers. */
    private val pauseGlobalHotkeys:  (() -> Unit)? = null,
    /** Called after the recorder dialog closes to restore the previous enabled state. */
    private val resumeGlobalHotkeys: (() -> Unit)? = null,
    /**
     * Records a new binding for an action.
     *
     * Tests substitute this so the edit path can be driven without the modal recorder dialog,
     * which needs a display. Left null, the recorder dialog does the recording.
     */
    private val recorder: ((HotkeyAction, HotkeyBinding?) -> HotkeyBinding?)? = null,
    /**
     * Asks the user to confirm replacing hand-made bindings.
     *
     * Tests substitute this so the paths that confirm can be driven without a modal dialog,
     * which needs a display and would block the event thread.
     */
    private val confirm: ((title: String, message: String) -> Boolean)? = null,
) : SettingsPanel() {

    private lateinit var enableCheck: JCheckBox
    private lateinit var showShortcut: JPanel
    private lateinit var showEditButton: JButton
    private lateinit var showClearButton: JButton
    private lateinit var doubleCtrlCheck: JCheckBox
    private lateinit var table:       JTable
    private lateinit var editButton:  JButton
    private lateinit var clearButton: JButton
    private lateinit var resetButton: JButton
    private lateinit var presetCombo: JComboBox<HotkeyPresetKind>
    private lateinit var presetStatus: JLabel
    /** Warning lines under the table; visible only while enabled bindings conflict. */
    private lateinit var conflictHint: JLabel
    /** What the selected command does, and what it needs, in the strip under the list. */
    private lateinit var detailsPanel: JPanel
    private lateinit var detailsTitle: JLabel
    private lateinit var detailsBody: JLabel
    private lateinit var detailsRequirement: JLabel
    /** The details strip as one string, which is also what its labels are built from. */
    private var detailsText: String = ""
    /** Shown when the active filter matches no command. */
    private lateinit var emptyHint: JLabel

    /** The scroll pane holding the rows, so its bounded height and wheel forwarding stay paired. */
    private lateinit var listScroll: JScrollPane

    /** The row whose action squares the pointer is over, so they can show it. */
    private var hoveredRow: Int = -1
    private var hoveredAction: CommandRowAction? = null

    /** The two row-action glyphs, each keeping the colour variants it has been asked for. */
    private lateinit var editGlyph: TintedGlyph
    private lateinit var clearGlyph: TintedGlyph

    private val filterButtons = LinkedHashMap<CommandFilter, JToggleButton>()
    private var activeFilter: CommandFilter = CommandFilter.ALL

    /** The rows currently shown, group headers included, in display order. */
    private var rows: List<CommandRow> = emptyList()

    private var conflicts: List<HotkeyConflict> = emptyList()
    private var conflictingActions: Set<HotkeyAction> = emptySet()

    /**
     * Every action listed here gets a row, and therefore can be rebound from this panel.
     * This is the registry's editable set ([CommandRegistry.editableActions]) rather than a
     * hand-maintained list, so a new [HotkeyAction] that ships without a [CommandSpec] fails
     * the core registry completeness test instead of silently having no row — the window-local
     * group added in #159 was bound, migrated and localized, but never listed, so it ran on its
     * defaults while being unconfigurable.
     *
     * SHOW_MAIN_WINDOW is deliberately excluded from the editable set: it has its own row
     * above the table with Edit/Clear buttons and the Double Ctrl option, so a second row for
     * it would edit the same binding twice.
     */
    private val actionOrder: List<HotkeyAction> = CommandRegistry.editableActions

    /**
     * The order categories are shown in.
     *
     * Task order rather than the registry's declaration order: the commands a user reaches for
     * most sit first, and window mechanics sit last. A category the registry grows later and
     * this list does not know about is appended alphabetically by [orderedCategories], so it
     * cannot silently disappear from the panel.
     */
    private val categoryOrder = listOf(
        "translation",
        "writing",
        "dictionary",
        "ocr",
        "capture",
        "navigation",
        "editing",
        "document",
        "window",
    )

    /**
     * Table metrics, authored for a 100% display and scaled where they are used: a table row and
     * its columns are plain pixel counts to Swing, and left raw they stay the same size while the
     * text and key chips inside them grow with the display.
     */
    private companion object {
        const val ROW_HEIGHT = 34
        /**
         * How many rows the list shows at once.
         *
         * The list used to be sized to its whole contents, so the panel grew to forty-odd rows
         * and the page around it scrolled instead. A viewport of about a dozen rows keeps the
         * list the tallest thing on the page while leaving the page itself scrollable.
         */
        const val LIST_VISIBLE_ROWS = 11

        /**
         * Pixels a wheel notch scrolls, before the display scale.
         *
         * Three notches move a little under 50 pixels, which is the pace the settings page itself
         * uses; a faster rate is what made the gesture feel jumpy when it crossed panels.
         */
        const val WHEEL_UNIT = 16
        /** A category heading is shorter than a command: it is a label, not a control. */
        const val GROUP_ROW_HEIGHT = 28
        /** The row-action column: two squares and their margins, and nothing to resize. */
        const val ACTION_COLUMN_WIDTH = 78
        const val ROW_ACTION_SIZE = 24
        const val ROW_ACTION_GAP = 4
        const val ROW_ACTION_MARGIN = 8
        /** The glyph inside a row-action square, at 100%. */
        const val ICON_GLYPH = 14
        /** Commands are indented under their heading, which is what makes the grouping read. */
        const val ACTION_INDENT = 12
        const val ACTION_MIN_WIDTH = 170
        const val ACTION_PREFERRED_WIDTH = 260
        const val HOTKEY_MIN_WIDTH = 125
        const val HOTKEY_PREFERRED_WIDTH = 190
        const val HOTKEY_MAX_WIDTH = 320
        const val SCOPE_MIN_WIDTH = 64
        const val SCOPE_PREFERRED_WIDTH = 80
        const val SCOPE_MAX_WIDTH = 120
        const val CHIP_ARC = 8
        const val CHIP_PADDING_Y = 4
        const val CHIP_PADDING_X = 9
        const val CHIP_GAP = 4
        const val TABLE_PREFERRED_WIDTH = 580
        /** Marks a row that takes part in a conflict without relying on colour alone. */
        const val CONFLICT_GLYPH = "⚠ "
    }

    private val COL_ACTION  = 0
    private val COL_HOTKEY  = 1
    private val COL_SCOPE   = 2
    private val COL_ACTIONS = 3

    /** What the table is filtered to. Session-only UI state; never persisted. */
    private enum class CommandFilter { ALL, UNASSIGNED, CONFLICTS }

    /** A group heading, or a command. Group heading rows carry no binding. */
    private sealed interface CommandRow {
        val action: HotkeyAction?

        data class Group(val category: String) : CommandRow {
            override val action: HotkeyAction? = null
        }

        data class Command(override val action: HotkeyAction) : CommandRow
    }

    init { buildUI() }

    /**
     * Glyphs are cached at the theme's colours and the display's scale, so a change to either
     * invalidates them. Called by Swing before [buildUI] has run, hence the initialisation check.
     */
    override fun updateUI() {
        super.updateUI()
        if (::editGlyph.isInitialized) {
            editGlyph.invalidate()
            clearGlyph.invalidate()
        }
    }

    private fun buildUI() {
        editGlyph  = TintedGlyph(IconSet.load(Icons.EDIT, ICON_GLYPH, ICON_GLYPH))
        clearGlyph = TintedGlyph(IconSet.load(Icons.DELETE, ICON_GLYPH, ICON_GLYPH))

        addSeparator(localizationManager.getString("settings_hotkeys.global_group"))

        enableCheck = addCheckbox(
            text     = localizationManager.getString("settings_hotkeys.enable_global"),
            selected = false,
            onChange = { enabled ->
                applyDraft(store) { it.copy(isGlobalHotkeysEnabled = enabled) }
            }
        )
        // "Global" reads like "all": a user who turns it off to stop shortcuts firing in other
        // applications would otherwise believe the shortcuts inside QTranslate are gone too.
        addHint(localizationManager.getString("settings_hotkeys.global_scope_hint"))

        addSeparator(localizationManager.getString("settings_hotkeys.show_main_group"))

        showShortcut = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(4), 0)).apply { isOpaque = false }
        showEditButton = JButton(localizationManager.getString("settings_hotkeys.change_button"))
        showClearButton = JButton(localizationManager.getString("settings_hotkeys.clear_button"))
        showEditButton.addActionListener { onEditShowMain() }
        showClearButton.addActionListener { onClearShowMain() }
        val showShortcutGroup = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0)).apply {
            isOpaque = false
            add(showShortcut)
            add(showEditButton)
            add(showClearButton)
        }
        addRow(localizationManager.getString("settings_hotkeys.shortcut_label"), showShortcutGroup)
        addRow(
            localizationManager.getString("settings_hotkeys.column_scope"),
            JLabel(localizationManager.getString("settings_hotkeys.scope_global"))
        )

        doubleCtrlCheck = addCheckbox(
            text = localizationManager.getString("settings_hotkeys.double_ctrl_label"),
            selected = true,
            onChange = { enabled -> onToggleDoubleCtrl(enabled) }
        )
        addHint(localizationManager.getString("settings_hotkeys.double_ctrl_description"))

        addSeparator(localizationManager.getString("settings_hotkeys.assignments_group"))
        presetCombo = JComboBox(HotkeyPresetKind.entries.filter { it != HotkeyPresetKind.CUSTOM }.toTypedArray()).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean
                ): Component = super.getListCellRendererComponent(
                    list, presetLabel(value as? HotkeyPresetKind ?: HotkeyPresetKind.LEGACY), index, selected, focused
                )
            }
            addActionListener {
                if (isUpdatingFromState) return@addActionListener
                val preset = selectedItem as? HotkeyPresetKind ?: return@addActionListener
                val current = store.state.value.workingConfiguration
                val target = HotkeyDraftOperations.replacePreset(current, preset)
                // Nothing to overwrite when the group already is that preset.
                if (target.hotkeys == current.hotkeys) return@addActionListener

                // A preset replaces whatever is there, including bindings the user made by hand.
                // Asking first is the difference between applying a preset and losing an evening.
                if (!ask(
                        titleKey = "settings_hotkeys.preset_overwrite_title",
                        messageKey = "settings_hotkeys.preset_overwrite_confirmation",
                    )
                ) {
                    // The picker must keep showing the scheme the bindings still are: a named
                    // preset, or no selection at all when they are custom.
                    withoutTrigger {
                        val currentPreset = HotkeyPresets.identify(current.hotkeys)
                        if (currentPreset == HotkeyPresetKind.CUSTOM) selectedIndex = -1
                        else selectedItem = currentPreset
                    }
                    return@addActionListener
                }
                applyDraft(store) { HotkeyDraftOperations.replacePreset(it, preset) }
            }
        }
        addRow(localizationManager.getString("settings_hotkeys.preset_label"), presetCombo)
        // The preset is a translation-scheme choice, not a whole-scheme reset. Saying which group
        // it covers is what keeps the rows beneath it from looking like they follow it.
        addHint(localizationManager.getString("settings_hotkeys.preset_scope_hint"))

        // Custom is a *description* of the current bindings, not an action. It used to be a
        // selectable entry, and picking it cleared every shortcut in the panel; now it is only
        // ever reported here, beside the two presets that can actually be applied.
        presetStatus = JLabel("").apply {
            isOpaque = false
            foreground = UIManager.getColor("Label.disabledForeground")
            font = font.deriveFont(font.size - 1f)
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(0, UIScale.scale(2), UIScale.scale(3), 0)
            .add(presetStatus)

        addHint(localizationManager.getString("settings_hotkeys.edit_hint"))
        addHint(localizationManager.getString("settings_hotkeys.preset_custom_hint"))

        val model = object : DefaultTableModel(
            arrayOf(
                localizationManager.getString("settings_hotkeys.column_action"),
                localizationManager.getString("settings_hotkeys.column_hotkey"),
                localizationManager.getString("settings_hotkeys.column_scope"),
                localizationManager.getString("settings_hotkeys.column_actions")
            ), 0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
            override fun getColumnClass(col: Int) = String::class.java
        }

        table = CommandsTable(model).apply {
            fillsViewportHeight = true
            rowHeight           = UIScale.scale(ROW_HEIGHT)
            autoResizeMode      = JTable.AUTO_RESIZE_ALL_COLUMNS
            setShowGrid(false)
            intercellSpacing    = Dimension(0, 0)
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            putClientProperty("FlatLaf.style", "showCellFocusIndicator: false")
            tableHeader.reorderingAllowed = false
            // The names are left-aligned in their column, so the heading over them is too; a
            // centred "Action" over a left-aligned column reads as a different column.
            tableHeader.defaultRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
                ): Component {
                    super.getTableCellRendererComponent(t, value, sel, focus, row, col)
                    // A renderer is not in the component hierarchy, so its own orientation is
                    // UNKNOWN and "leading" would resolve leftwards even in a right-to-left
                    // dialog. The panel's orientation is the one the row actually uses.
                    val leftToRight = componentOrientation.isLeftToRight
                    // The Hotkey heading centres because the key chips under it centre; a
                    // left-aligned heading over centred chips reads as a different column.
                    horizontalAlignment = when {
                        col == COL_HOTKEY -> SwingConstants.CENTER
                        col != COL_ACTION -> SwingConstants.CENTER
                        leftToRight -> SwingConstants.LEFT
                        else -> SwingConstants.RIGHT
                    }
                    border = leadingInset(
                        width = UIScale.scale(if (col == COL_ACTION) ACTION_INDENT else 2),
                    )
                    return this
                }
            }

            // Hotkey and Scope are capped so that extra width goes to the Action names, which are
            // the text people read, rather than stretching a column that holds a few key chips.
            columnModel.getColumn(COL_ACTION).apply {
                preferredWidth = UIScale.scale(ACTION_PREFERRED_WIDTH)
                minWidth       = UIScale.scale(ACTION_MIN_WIDTH)
                cellRenderer   = ActionColumnRenderer()
            }
            columnModel.getColumn(COL_HOTKEY).apply {
                preferredWidth = UIScale.scale(HOTKEY_PREFERRED_WIDTH)
                minWidth       = UIScale.scale(HOTKEY_MIN_WIDTH)
                maxWidth       = UIScale.scale(HOTKEY_MAX_WIDTH)
                cellRenderer   = HotkeyColumnRenderer()
            }
            columnModel.getColumn(COL_SCOPE).apply {
                preferredWidth = UIScale.scale(SCOPE_PREFERRED_WIDTH)
                minWidth       = UIScale.scale(SCOPE_MIN_WIDTH)
                maxWidth       = UIScale.scale(SCOPE_MAX_WIDTH)
                cellRenderer   = ScopeColumnRenderer()
            }
            // Fixed, so the action squares stay where the pointer learned to find them.
            columnModel.getColumn(COL_ACTIONS).apply {
                preferredWidth = UIScale.scale(ACTION_COLUMN_WIDTH)
                minWidth       = UIScale.scale(ACTION_COLUMN_WIDTH)
                maxWidth       = UIScale.scale(ACTION_COLUMN_WIDTH)
                resizable      = false
                cellRenderer   = RowActionsRenderer()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0) return
                    val action = actionAt(row)
                    if (action == null) {
                        // A heading is a signpost, not a row: clicking it selects nothing.
                        if (col == COL_ACTIONS) return
                        return
                    }
                    when {
                        col == COL_ACTIONS -> {
                            val target = rowActionAt(row, e.point) ?: return
                            setRowSelectionInterval(row, row)
                            when (target) {
                                CommandRowAction.EDIT -> onEditSelected()
                                CommandRowAction.CLEAR -> onClearSelected()
                            }
                        }
                        // Double-click hotkey column — open recorder
                        col == COL_HOTKEY && e.clickCount == 2 -> {
                            setRowSelectionInterval(row, row)
                            onEditSelected()
                        }
                        // Single click scope column — toggle global/local
                        col == COL_SCOPE -> {
                            setRowSelectionInterval(row, row)
                            if (bindingFor(action)?.scope != HotkeyScope.CAPTURE) {
                                onToggleScope(action)
                            }
                        }
                    }
                }

                override fun mouseExited(e: MouseEvent) = setHover(-1, null)
            })

            // The squares are painted rather than being components of their own, so the cell has
            // to track the pointer itself to show which square a click would hit.
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    val target = if (row >= 0 && col == COL_ACTIONS) rowActionAt(row, e.point) else null
                    setHover(if (target == null) -1 else row, target)
                    cursor = Cursor.getPredefinedCursor(
                        if (target != null) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR
                    )
                }
            })

            // Enter edits the selected command, so the row actions are not the only way in.
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "editSelectedCommand")
            actionMap.put("editSelectedCommand", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = onEditSelected()
            })

            // Named before its cells: a screen reader announces the table before reading into it,
            // and the default would be the settings page's own name.
            accessibleContext.accessibleName =
                localizationManager.getString("settings_hotkeys.command_list_accessible_name")

            selectionModel.addListSelectionListener {
                updateButtonStates()
                updateDetails()
            }
        }

        conflictHint = JLabel("").apply {
            isOpaque = false
            isVisible = false
            foreground = UIManager.getColor("Label.warningForeground")
                ?: UIManager.getColor("Component.errorColor")
        }

        editButton  = JButton(localizationManager.getString("settings_hotkeys.edit_button"))
        clearButton = JButton(localizationManager.getString("settings_hotkeys.clear_button"))
        resetButton = JButton(localizationManager.getString("settings_hotkeys.reset_button"))

        editButton.isEnabled  = false
        clearButton.isEnabled = false

        editButton.addActionListener  { onEditSelected() }
        clearButton.addActionListener { onClearSelected() }
        resetButton.addActionListener { onResetAll() }

        // The controls that act on the list sit above it, in one line with the filter: they are
        // read before the rows are, and they no longer move when the rows scroll.
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(2), 0, UIScale.scale(4), 0)
            .add(buildToolbar())

        listScroll = JScrollPane(table).apply {
            border = themeAwareBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = UIScale.scale(ROW_HEIGHT)
            viewport.background = table.background
            // A viewport of a fixed number of rows, not the height of the whole list: the list
            // scrolls its own contents, so adding a command no longer makes the page taller.
            preferredSize = Dimension(
                UIScale.scale(TABLE_PREFERRED_WIDTH),
                UIScale.scale(ROW_HEIGHT) * LIST_VISIBLE_ROWS +
                    table.tableHeader.preferredSize.height + UIScale.scale(2)
            )
        }
        installWheelForwarding(listScroll)

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(4), 0, 0, 0)
            .add(listScroll)

        emptyHint = hintLabel().apply { isVisible = false }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(2), UIScale.scale(2), 0, 0)
            .add(emptyHint)

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(4), 0, 0, 0)
            .add(conflictHint)

        detailsPanel = buildDetailsPanel()
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(8), 0, 0, 0)
            .add(detailsPanel)

        finishLayout()
    }

    /** The All / Unassigned / Conflicts switch above the table. */
    private fun buildFilterRow(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(4), 0)).apply { isOpaque = false }
        val group = ButtonGroup()
        listOf(
            CommandFilter.ALL to "settings_hotkeys.filter_all",
            CommandFilter.UNASSIGNED to "settings_hotkeys.filter_unassigned",
            CommandFilter.CONFLICTS to "settings_hotkeys.filter_conflicts",
        ).forEach { (filter, key) ->
            val button = JToggleButton(localizationManager.getString(key)).apply {
                isSelected = filter == CommandFilter.ALL
                isFocusable = false
                addActionListener {
                    if (!isUpdatingFromState && activeFilter != filter) {
                        activeFilter = filter
                        rebuildRows(store.state.value)
                        updateButtonStates()
                    }
                }
            }
            group.add(button)
            filterButtons[filter] = button
            panel.add(button)
        }
        return panel
    }

    /**
     * The line above the list: what it shows on the leading edge, what acts on the selection on
     * the trailing one.
     *
     * The filter and the three buttons used to sit on separate rows on opposite sides of a
     * forty-row list, so which commands were showing and what could be done to the selected one
     * were a scroll apart. They answer two halves of the same question and belong on one line.
     */
    private fun buildToolbar(): JPanel = JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
        isOpaque = false
        add(
            JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0)).apply {
                isOpaque = false
                add(JLabel(localizationManager.getString("settings_hotkeys.filter_label")))
                add(buildFilterRow())
            },
            BorderLayout.LINE_START
        )
        add(
            JPanel(FlowLayout(FlowLayout.TRAILING, UIScale.scale(4), 0)).apply {
                isOpaque = false
                add(editButton)
                add(clearButton)
                add(resetButton)
            },
            BorderLayout.LINE_END
        )
    }

    /**
     * The strip under the list describing the selected command.
     *
     * The name, then what the command does, then what it needs: three weights of one block, read
     * in the order the question is asked. Nothing in it is a control, so it is flat — a bordered
     * card here competed with the list directly above it, which is the part that should draw the
     * eye.
     */
    private fun buildDetailsPanel(): JPanel = JPanel(BorderLayout(0, UIScale.scale(2))).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(2), 0, 0)

        detailsTitle = JLabel("").apply {
            isOpaque = false
            font = font.deriveFont(Font.BOLD)
        }
        detailsBody = JLabel("").apply {
            isOpaque = false
            verticalAlignment = SwingConstants.TOP
        }
        detailsRequirement = JLabel("").apply {
            isOpaque = false
            verticalAlignment = SwingConstants.TOP
            foreground = UIManager.getColor("Label.disabledForeground")
            font = font.deriveFont(font.size - 1f)
            isVisible = false
        }

        add(detailsTitle, BorderLayout.NORTH)
        add(
            JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(detailsBody)
                add(detailsRequirement)
            },
            BorderLayout.CENTER
        )
    }

    /**
     * Lets a wheel gesture leave the list once the list has nowhere left to go.
     *
     * The list is a scroll pane inside the page's scroll pane, and Swing's scroll-pane handler
     * consumes every wheel event it sees — including the ones it cannot act on — so a list that had
     * reached its end swallowed the gesture and the page underneath could not scroll while the
     * pointer was over the rows.
     *
     * Listening on the view and on the scroll bar, rather than on the scroll pane, is what makes
     * this possible: those run before the scroll pane's own handler, so an event that belongs to the
     * page is forwarded and consumed here, while an event the list can still use is left alone to
     * scroll the list.
     */
    /**
     * Hands the wheel to the page when the list has nothing left to scroll.
     *
     * Swing's scroll pane consumes every wheel event it receives, including ones it cannot act
     * on, which is how a list with nothing left to scroll kept the page from moving. The old
     * remedy re-dispatched the event at the enclosing scroll pane, but a `JScrollPane` does not
     * itself handle wheels — its `BasicScrollPaneUI` listens on the viewport and the bars — so
     * the gesture only reached the page when the converted coordinates happened to fall inside
     * the page's viewport, and was dropped otherwise. That is the "cannot scroll over the list"
     * report.
     *
     * Moving the scroll bars directly is coordinate-free and cannot land on the wrong pane: the
     * list takes the gesture when it has room for it, and the page takes it, by exactly the same
     * distance, when the list is at the end it is being pushed against. The gesture is consumed
     * either way, so only one pane ever moves.
     */
    private fun installWheelForwarding(list: JScrollPane) {
        val forward = MouseWheelListener { event ->
            if (event.isConsumed) return@MouseWheelListener
            val inner = list.verticalScrollBar
            val rotation = event.wheelRotation
            if (rotation == 0) return@MouseWheelListener

            if (scrollBarCanMove(inner, rotation)) {
                // The list has room: it scrolls, and the page stays where it is.
                scrollBarBy(inner, rotation * wheelStep(event))
                event.consume()
                return@MouseWheelListener
            }

            val outer = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, list) as? JScrollPane
                ?: return@MouseWheelListener
            scrollBarBy(outer.verticalScrollBar, rotation * wheelStep(event))
            event.consume()
        }
        // Installed on the view, the viewport and the bar. A `JTable` is a `Scrollable`, so its
        // own viewport handler would consume the gesture before anything here ran; listening on
        // the view first is what lets the decision above be the one that is made.
        list.viewport.view.addMouseWheelListener(forward)
        list.viewport.addMouseWheelListener(forward)
        list.verticalScrollBar.addMouseWheelListener(forward)
    }

    /**
     * How far one wheel notch moves [bar].
     *
     * This deliberately does not use the bar's `unitIncrement`. That is set to a whole row so
     * arrow-key scrolling moves by rows, and a wheel notch carries three units, so reusing it
     * scrolled three rows per notch — noticeably faster than the page's own 16 pixels, which made
     * the gesture lurch exactly when it crossed from one pane to the other. The wheel uses the
     * toolkit's own scroll distance instead, which is what every other scroll pane here does and
     * what a trackpad's fine-grained deltas are scaled against.
     */
    private fun wheelStep(event: MouseWheelEvent): Int {
        val unit = UIScale.scale(WHEEL_UNIT)
        return unit * event.scrollAmount.coerceAtLeast(1)
    }

    /** Moves [bar] by [delta], staying inside its travel. */
    private fun scrollBarBy(bar: JScrollBar, delta: Int) {
        val travel = (bar.maximum - bar.visibleAmount - bar.minimum).coerceAtLeast(0)
        bar.value = (bar.value + delta).coerceIn(bar.minimum, bar.minimum + travel)
    }

    /** The action square under [point] in [row], or null when the point is between them. */
    private fun rowActionAt(row: Int, point: Point): CommandRowAction? {
        if (actionAt(row) == null) return null
        val column = table.columnModel.getColumn(COL_ACTIONS)
        if (column.width <= 0) return null
        val cell = table.getCellRect(row, COL_ACTIONS, false)
        return CommandRowActions.hit(
            x = point.x - cell.x,
            columnWidth = column.width,
            height = cell.height,
            leftToRight = componentOrientation.isLeftToRight,
            side = UIScale.scale(ROW_ACTION_SIZE),
            gap = UIScale.scale(ROW_ACTION_GAP),
            margin = UIScale.scale(ROW_ACTION_MARGIN),
        )
    }

    private fun setHover(row: Int, action: CommandRowAction?) {
        if (row == hoveredRow && action == hoveredAction) return
        val previous = hoveredRow
        hoveredRow = row
        hoveredAction = action
        if (previous >= 0) table.repaint(table.getCellRect(previous, COL_ACTIONS, true))
        if (row >= 0) table.repaint(table.getCellRect(row, COL_ACTIONS, true))
    }

    private fun hintLabel(): JLabel = JLabel("").apply {
        isOpaque = false
        foreground = UIManager.getColor("Label.disabledForeground")
        font = font.deriveFont(font.size - 1f)
    }

    private fun onEditSelected() {
        val action = selectedAction() ?: return
        if (!isEditable(action)) return

        val result = recordBinding(action, bindingFor(action)) ?: return

        saveBinding(result)
    }

    /**
     * Whether the binding of [action] can be changed right now.
     *
     * A LOCAL binding is installed on QTranslate's own InputMap and fires inside the window with
     * no OS-level registration at all, so it stays configurable whatever the global switch says.
     * A GLOBAL binding is one the global input backend registers system-wide, and the switch
     * turns that registration off. Recording a key for it needs no registration, but a global
     * shortcut is a statement about how it is registered, so the panel keeps editing it behind
     * the switch: changing it while it is not registered would let the shortcut and its
     * registration drift apart.
     *
     * The scope decides, never the switch alone — a user who runs without system-wide shortcuts
     * must still be able to change their local ones.
     *
     * The scope cell of the table is deliberately not gated by this. Clicking it swaps a binding
     * between GLOBAL and LOCAL in both directions, which is how a global shortcut is turned into
     * a local one, and therefore how a global shortcut becomes editable at all while the switch
     * is off.
     */
    private fun isEditable(action: HotkeyAction): Boolean {
        val configuration = store.state.value.workingConfiguration
        if (configuration.isGlobalHotkeysEnabled) return true
        val scope = configuration.effectiveHotkeys.find { it.action == action }?.scope ?: HotkeyScope.GLOBAL
        return scope == HotkeyScope.LOCAL || scope == HotkeyScope.CAPTURE
    }

    /** Records a new binding for [action], through the recorder dialog unless a test replaced it. */
    private fun recordBinding(action: HotkeyAction, current: HotkeyBinding?): HotkeyBinding? =
        if (recorder != null) {
            recorder(action, current)
        } else {
            HotkeyRecorderDialog.show(
                owner               = SwingUtilities.getWindowAncestor(this),
                action              = action,
                current             = current,
                localizer           = localizationManager,
                pauseGlobalHotkeys  = pauseGlobalHotkeys,
                resumeGlobalHotkeys = resumeGlobalHotkeys,
                existingBindings    = store.state.value.workingConfiguration.effectiveHotkeys,
            )
        }

    private fun onEditShowMain() {
        val current = bindingFor(HotkeyAction.SHOW_MAIN_WINDOW) ?: return
        val result = recordBinding(HotkeyAction.SHOW_MAIN_WINDOW, current) ?: return
        saveBinding(result.copy(scope = HotkeyScope.GLOBAL, isDoubleCtrlEnabled = current.isDoubleCtrlEnabled))
    }

    private fun onClearShowMain() {
        applyDraft(store) { HotkeyDraftOperations.clearShowMainWindow(it) }
    }

    private fun onToggleDoubleCtrl(enabled: Boolean) {
        applyDraft(store) { HotkeyDraftOperations.setDoubleCtrl(it, enabled) }
    }

    private fun onClearSelected() {
        val action = selectedAction() ?: return
        if (!isEditable(action)) return
        // Keep existing scope when clearing
        val existing = bindingFor(action)
        saveBinding(HotkeyBinding(action = action, keyCode = 0, modifiers = 0, scope = existing?.scope ?: HotkeyScope.GLOBAL))
    }

    /**
     * Swaps the scope of [action] between GLOBAL and LOCAL.
     *
     * Deliberately not gated by the global switch, in either direction: this cell is the
     * documented way to turn a global shortcut into a local one, and it is the only way to make a
     * global shortcut editable while the switch is off. A shortcut nobody can change would be a
     * dead end, so the cell stays clickable and the scope it shows keeps telling the truth.
     */
    private fun onToggleScope(action: HotkeyAction) {
        if (bindingFor(action) == null) return
        applyDraft(store) { HotkeyDraftOperations.toggleScope(it, action) }
    }

    private fun onResetAll() {
        if (!ask(
                titleKey = "settings_hotkeys.reset_title",
                messageKey = "settings_hotkeys.reset_confirmation",
            )
        ) return
        // Every group the table shows, not just the legacy rows: a button that says "all" and
        // leaves OCR and writing shortcuts untouched is the defect, not the fix.
        applyDraft(store) { HotkeyDraftOperations.resetAllBindings(it) }
    }

    /**
     * Asks one yes/no question, through the injected seam when there is one.
     */
    private fun ask(titleKey: String, messageKey: String): Boolean {
        val title = localizationManager.getString(titleKey)
        val message = localizationManager.getString(messageKey)
        confirm?.let { return it(title, message) }
        return JOptionPane.showConfirmDialog(
            this, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION
    }

    private fun saveBinding(binding: HotkeyBinding) {
        applyDraft(store) { HotkeyDraftOperations.replaceBinding(it, binding) }
    }

    /** The bindings table, for tests that check how it is sized. */
    internal fun tableForTest(): JTable = table

    /** One filter switch, so tests can drive the filters without walking the tree. */
    internal fun filterButtonForTest(label: String): JToggleButton =
        filterButtons.values.single { it.text == label }

    /** The preset selector, for tests that check Custom is not one of its choices. */
    internal fun presetComboForTest(): JComboBox<HotkeyPresetKind> = presetCombo

    /** The text under the table describing the selected command. */
    internal fun detailsTextForTest(): String = detailsText

    /** The bounded viewport the commands are listed in, for tests about scrolling and height. */
    internal fun listScrollForTest(): JScrollPane = listScroll

    /** Where the two row-action squares sit in a cell, for tests about hitting them. */
    internal fun rowActionBoundsForTest(columnWidth: Int, height: Int, leftToRight: Boolean) =
        CommandRowActions.bounds(
            columnWidth = columnWidth,
            height = height,
            leftToRight = leftToRight,
            side = UIScale.scale(ROW_ACTION_SIZE),
            gap = UIScale.scale(ROW_ACTION_GAP),
            margin = UIScale.scale(ROW_ACTION_MARGIN),
        )

    /** The conflict summary lines currently shown, if any. */
    internal fun conflictTextForTest(): String = conflictHint.text

    /** The "Current: …" line beside the preset selector. */
    internal fun presetStatusForTest(): String = presetStatus.text

    private fun bindingFor(action: HotkeyAction): HotkeyBinding? =
        store.state.value.workingConfiguration.effectiveHotkeys.find { it.action == action }

    private fun selectedAction(): HotkeyAction? = actionAt(table.selectedRow)

    private fun actionAt(row: Int): HotkeyAction? = rows.getOrNull(row)?.action

    // ---------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------

    /**
     * Rebuilds the rows for [state]: grouping, filtering and conflict marking in one pass, so the
     * three can never disagree about which commands are shown.
     */
    private fun rebuildRows(state: SettingsState) {
        val configuration = state.workingConfiguration
        val bindings = configuration.effectiveHotkeys
        conflicts = HotkeyConflictDetector.findConflicts(bindings)
        conflictingActions = conflicts.flatMap { it.actions }.toSet()

        val selectedBefore = selectedAction()
        val visible = actionOrder.filter { action -> matchesFilter(action, bindings) }
        val byCategory = visible.groupBy { CommandRegistry.specFor(it)?.category ?: "" }

        rows = buildList {
            orderedCategories(byCategory.keys).forEach { category ->
                val inCategory = byCategory[category].orEmpty()
                if (inCategory.isEmpty()) return@forEach
                if (category.isNotEmpty()) add(CommandRow.Group(category))
                // Registry order within a group, which is the order the commands were designed in.
                inCategory.sortedBy { actionOrder.indexOf(it) }.forEach { add(CommandRow.Command(it)) }
            }
        }

        // Row indexes move with the filter, so a hovered square belongs to no row afterwards.
        hoveredRow = -1
        hoveredAction = null

        val model = table.model as DefaultTableModel
        model.rowCount = rows.size
        rows.forEachIndexed { index, row ->
            when (row) {
                is CommandRow.Group -> {
                    model.setValueAt(categoryLabel(row.category), index, COL_ACTION)
                    model.setValueAt(null, index, COL_HOTKEY)
                    model.setValueAt("", index, COL_SCOPE)
                    model.setValueAt("", index, COL_ACTIONS)
                    // A heading is a label, not a control: it does not need a control's height.
                    table.setRowHeight(index, UIScale.scale(GROUP_ROW_HEIGHT))
                }
                is CommandRow.Command -> {
                    val binding = bindings.find { it.action == row.action }
                    model.setValueAt(actionDisplayName(row.action), index, COL_ACTION)
                    model.setValueAt(binding, index, COL_HOTKEY)
                    model.setValueAt(scopeLabel(binding?.scope ?: HotkeyScope.GLOBAL), index, COL_SCOPE)
                    model.setValueAt("", index, COL_ACTIONS)
                    table.setRowHeight(index, UIScale.scale(ROW_HEIGHT))
                }
            }
        }

        // Keeping the selected command selected across a filter change or an edit is what makes
        // the details line and the Edit/Clear buttons usable in a list this long.
        if (selectedBefore != null) {
            val restored = rows.indexOfFirst { it.action == selectedBefore }
            if (restored >= 0) table.setRowSelectionInterval(restored, restored) else table.clearSelection()
        } else {
            table.clearSelection()
        }

        renderConflictSummary()
        renderEmptyHint()
        updateDetails()
    }

    private fun matchesFilter(action: HotkeyAction, bindings: List<HotkeyBinding>): Boolean =
        when (activeFilter) {
            CommandFilter.ALL -> true
            CommandFilter.UNASSIGNED -> bindings.find { it.action == action }?.hasBinding != true
            CommandFilter.CONFLICTS -> action in conflictingActions
        }

    /** [categories] in [categoryOrder]; anything unknown follows them, alphabetically. */
    private fun orderedCategories(categories: Collection<String>): List<String> {
        val known = categoryOrder.filter { it in categories }
        val unknown = categories.filter { it.isNotEmpty() && it !in categoryOrder }.sorted()
        // A command whose spec is missing has no category. It still gets its row, under the
        // unlabelled heading, rather than vanishing from a list that is meant to be exhaustive.
        val unlabelled = if ("" in categories) listOf("") else emptyList()
        return known + unknown + unlabelled
    }

    private fun renderConflictSummary() {
        if (conflicts.isEmpty()) {
            conflictHint.text = ""
            conflictHint.isVisible = false
            return
        }

        // The combination is the actionable half of a conflict and the model has always carried
        // it; naming only the actions left the user to find the shared chord by hand.
        val lines = conflicts.map { conflict ->
            localizationManager.getString(
                "settings_hotkeys.conflict_entry",
                formatChord(conflict.keyCode, conflict.modifiers),
                conflict.actions.sortedBy { it.ordinal }.joinToString(", ") { actionDisplayName(it) },
            )
        }
        val header = localizationManager.getString("settings_hotkeys.conflict_warning")
        conflictHint.text = htmlText((listOf(header) + lines).joinToString("\n"))
        conflictHint.isVisible = true
    }

    private fun renderEmptyHint() {
        val hasCommands = rows.any { it is CommandRow.Command }
        if (hasCommands || activeFilter == CommandFilter.ALL) {
            emptyHint.text = ""
            emptyHint.isVisible = false
            return
        }
        emptyHint.text = htmlText(
            localizationManager.getString(
                when (activeFilter) {
                    CommandFilter.UNASSIGNED -> "settings_hotkeys.filter_empty_unassigned"
                    else -> "settings_hotkeys.filter_empty_conflicts"
                }
            )
        )
        emptyHint.isVisible = true
    }

    private fun updateDetails() {
        val action = selectedAction()
        if (action == null) {
            detailsText = localizationManager.getString("settings_hotkeys.details_placeholder")
            detailsTitle.text = ""
            detailsTitle.isVisible = false
            detailsBody.text = htmlText(detailsText)
            detailsRequirement.text = ""
            detailsRequirement.isVisible = false
            detailsPanel.revalidate()
            return
        }

        val requirement = requirementFor(action)
        val conflictNote = if (action in conflictingActions) {
            val others = conflicts.filter { action in it.actions }
                .flatMap { it.actions }
                .filter { it != action }
                .distinct()
                .joinToString(", ") { actionDisplayName(it) }
            if (others.isEmpty()) null
            else localizationManager.getString("settings_hotkeys.conflict_row_tooltip", others)
        } else null

        // One plain-text version for the whole block: the labels below are styled separately, but
        // what the block says is a single statement about one command.
        detailsText = listOfNotNull(
            actionDisplayName(action),
            descriptionFor(action),
            requirement,
            conflictNote,
        ).joinToString("\n")

        detailsTitle.text = actionDisplayName(action)
        detailsTitle.isVisible = true
        detailsBody.text = htmlText(descriptionFor(action))
        val note = listOfNotNull(requirement, conflictNote).joinToString("\n")
        detailsRequirement.text = if (note.isEmpty()) "" else htmlText(note)
        detailsRequirement.isVisible = note.isNotEmpty()
        detailsPanel.revalidate()
    }

    private fun updateButtonStates() {
        val action   = selectedAction()
        val editable = action != null && isEditable(action)
        editButton.isEnabled  = editable
        clearButton.isEnabled = editable
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            enableCheck.isSelected = c.isGlobalHotkeysEnabled

            val preset = HotkeyPresets.identify(c.hotkeys)
            if (preset == HotkeyPresetKind.CUSTOM) {
                // No selection: Custom is reported in words beside the control instead, so the
                // control never shows a choice that would clear bindings when picked.
                presetCombo.selectedIndex = -1
                presetStatus.text = localizationManager.getString(
                    "settings_hotkeys.preset_current",
                    localizationManager.getString("settings_hotkeys.preset_custom"),
                )
            } else {
                presetCombo.selectedItem = preset
                presetStatus.text = localizationManager.getString(
                    "settings_hotkeys.preset_current",
                    presetLabel(preset),
                )
            }

            val showBinding = c.hotkeys.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            refreshShowShortcut(showBinding)
            doubleCtrlCheck.isSelected = showBinding?.isDoubleCtrlEnabled ?: true
            showEditButton.isEnabled = c.isGlobalHotkeysEnabled
            showClearButton.isEnabled = c.isGlobalHotkeysEnabled && showBinding?.hasBinding == true

            // The table is not greyed out with the global switch: it lists every command, and the
            // local ones have to stay configurable with global hotkeys off. Which rows can be
            // changed is decided per row, by the scope of the binding it carries.
            doubleCtrlCheck.isEnabled = c.isGlobalHotkeysEnabled

            rebuildRows(state)
            updateButtonStates()
        }
    }

    /** Active binding conflicts for the current draft, for tests. */
    internal fun conflictsForTest(): List<HotkeyConflict> =
        HotkeyConflictDetector.findConflicts(
            store.state.value.workingConfiguration.effectiveHotkeys
        )

    // ---------------------------------------------------------------------------
    // Localized text
    // ---------------------------------------------------------------------------

    /** Display name from the registry's localization key; falls back to the enum name if unregistered. */
    private fun actionDisplayName(action: HotkeyAction): String =
        CommandRegistry.specFor(action)?.let { localizationManager.getString(it.nameKey) } ?: action.name

    private fun categoryLabel(category: String): String {
        if (category.isEmpty()) return localizationManager.getString("settings_hotkeys.assignments_group")
        val key = CommandExplorerText.categoryKeys[category] ?: return category
        val localized = localizationManager.getString(key)
        // A category the registry grows before its label ships still reads as something.
        return if (localized == key) category else localized
    }

    /**
     * What [action] does, localized.
     *
     * Each command names its own key, so a command can never show another command's description.
     * The registry's English sentence is the fallback for a label that has not been translated
     * yet — better a real explanation in the wrong language than a dotted key.
     */
    private fun descriptionFor(action: HotkeyAction): String {
        val key = CommandExplorerText.descriptionKeys[action]
        if (key != null) {
            val localized = localizationManager.getString(key)
            if (localized != key) return localized
        }
        return CommandRegistry.specFor(action)?.description.orEmpty()
    }

    /**
     * What [action] needs that its name does not say, or null.
     *
     * Only two kinds of requirement are stated, and both are facts of the implementation rather
     * than a status: the selection commands read a selection made in another application (the
     * dispatch path that injects the Copy owns that list), and the two writing commands need a
     * rewriter or summarizer service to do anything. Runtime availability is deliberately not
     * shown — nothing tracks it reliably enough to print a claim about it.
     */
    private fun requirementFor(action: HotkeyAction): String? = when {
        action in HotkeyBinding.WRITING_ACTIONS ->
            localizationManager.getString("settings_hotkeys.requirement_writing")
        action in MainGlobalKeyListener.selectionCaptureActions ->
            localizationManager.getString("settings_hotkeys.requirement_selection")
        else -> null
    }

    /** Wraps text for a [JLabel], honouring the panel's reading direction. */
    private fun htmlText(text: String): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
        val leftToRight = componentOrientation.isLeftToRight
        val dir = if (leftToRight) "ltr" else "rtl"
        val align = if (leftToRight) "left" else "right"
        val width = UIScale.scale(520)
        return "<html><body dir='$dir' style='width:${width}px; text-align:$align'>$escaped</body></html>"
    }

    private fun scopeLabel(scope: HotkeyScope): String = when (scope) {
        HotkeyScope.GLOBAL -> localizationManager.getString("settings_hotkeys.scope_global")
        HotkeyScope.LOCAL  -> localizationManager.getString("settings_hotkeys.scope_local")
        HotkeyScope.CAPTURE -> localizationManager.getString("settings_hotkeys.scope_capture")
    }

    private fun presetLabel(preset: HotkeyPresetKind): String = when (preset) {
        HotkeyPresetKind.LEGACY -> localizationManager.getString("settings_hotkeys.preset_legacy")
        HotkeyPresetKind.MODERN -> localizationManager.getString("settings_hotkeys.preset_modern")
        HotkeyPresetKind.CUSTOM -> localizationManager.getString("settings_hotkeys.preset_custom")
    }

    private fun refreshShowShortcut(binding: HotkeyBinding?) {
        showShortcut.removeAll()
        if (binding == null || !binding.hasBinding) {
            showShortcut.add(JLabel(localizationManager.getString("settings_hotkeys.no_binding")).apply {
                foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            })
        } else {
            tokenizeBinding(binding).forEach { showShortcut.add(KeyChip(it, false)) }
        }
        showShortcut.revalidate()
        showShortcut.repaint()
    }

    private fun tokenizeBinding(binding: HotkeyBinding): List<String> = buildList {
        if (binding.modifiers and InputEvent.CTRL_DOWN_MASK != 0) add("Ctrl")
        if (binding.modifiers and InputEvent.ALT_DOWN_MASK != 0) add("Alt")
        if (binding.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) add("Shift")
        if (binding.modifiers and InputEvent.META_DOWN_MASK != 0) add("⌘")
        add(KeyEvent.getKeyText(binding.keyCode))
    }

    /**
     * A heading's background: the column headers' own shade.
     *
     * A heading needs to read as a break in the list rather than as one more command. A slight
     * tint does that without a box, which at this density looked like a table inside a table.
     */
    private fun groupRowBackground(): Color =
        UIManager.getColor("TableHeader.background")
            ?: UIManager.getColor("Table.background")
            ?: Color.LIGHT_GRAY

    private fun groupRowForeground(): Color =
        UIManager.getColor("Label.disabledForeground") ?: Color.GRAY

    /**
     * Padding on the leading edge, which is the right edge in a right-to-left layout.
     *
     * Renderers build their borders themselves and are not in the hierarchy, so an asymmetric
     * `EmptyBorder` inset on the left would keep indenting from the left after the dialog is
     * flipped — the indentation that shows a command belongs to its category would end up on the
     * far side of its heading.
     */
    private fun leadingInset(width: Int, trailing: Int = 0): javax.swing.border.Border {
        val leftToRight = componentOrientation.isLeftToRight
        return if (leftToRight) BorderFactory.createEmptyBorder(0, width, 0, trailing)
        else BorderFactory.createEmptyBorder(0, trailing, 0, width)
    }

    /**
     * The action column: a command's name, or a category heading.
     *
     * Commands are indented under their heading, which is what makes the grouping visible without
     * a tree control: the heading sits at the left margin and its commands one step in from it.
     *
     * A conflicting row is marked with a glyph as well as a colour, because colour alone is not
     * readable for everyone, and the tooltip repeats the explanation in words.
     */
    private inner class ActionColumnRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col)
            // Renderers stand outside the hierarchy, so they take the dialog's direction here.
            applyComponentOrientation(this@KeyboardPanel.componentOrientation)
            val label = value?.toString().orEmpty()
            val commandRow = rows.getOrNull(row)
            isOpaque = true

            if (commandRow is CommandRow.Group) {
                background = groupRowBackground()
                font = t.font.deriveFont(Font.BOLD, t.font.size - 0.5f)
                foreground = groupRowForeground()
                text = label
                toolTipText = null
                border = leadingInset(UIScale.scale(2), UIScale.scale(6))
                return this
            }

            background = if (sel) t.selectionBackground else t.background
            font = t.font
            val action = commandRow?.action
            val conflicting = action != null && action in conflictingActions
            text = if (conflicting) CONFLICT_GLYPH + label else label
            foreground = when {
                sel -> t.selectionForeground
                conflicting -> UIManager.getColor("Label.warningForeground")
                    ?: UIManager.getColor("Component.errorColor")
                else -> t.foreground
            }
            border = leadingInset(UIScale.scale(ACTION_INDENT), UIScale.scale(6))
            toolTipText = action?.let { hint ->
                htmlText(
                    listOfNotNull(descriptionFor(hint), requirementFor(hint)).joinToString("\n")
                )
            }
            return this
        }
    }

    /**
     * Renders keyboard bindings as pill-shaped key chip badges — e.g. [Ctrl] [Alt] [T].
     * Each token is drawn as a custom component with a rounded border, matching modern
     * OS-style hotkey displays (VS Code, macOS System Settings, JetBrains IDEs).
     */
    private inner class HotkeyColumnRenderer : TableCellRenderer {

        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            val binding = value as? HotkeyBinding
            val bg      = if (sel) t.selectionBackground else t.background

            return when {
                binding == null ->
                    // A category heading holds no shortcut.
                    JPanel().apply {
                        background = groupRowBackground()
                        isOpaque = true
                    }
                !binding.hasBinding ->
                    chipRow(
                        listOf(localizationManager.getString("settings_hotkeys.no_binding")),
                        bg, muted = true
                    )
                else ->
                    chipRow(
                        tokenizeBinding(binding), bg, muted = false,
                        tooltip = null
                    )
            }
        }

        /**
         * Builds a row of key chips in the middle of the cell.
         *
         * The band is centred against the row so the shortcut reads as the cell's content rather
         * than as text resting on its top edge; the chips sit flush against each other inside it,
         * so a two-key and a four-key shortcut stay comparable down the column.
         */
        private fun chipRow(
            tokens: List<String>,
            bg: Color,
            muted: Boolean,
            tooltip: String? = null
        ): JPanel = object : JPanel(ChipBandLayout()) {
            /**
             * A table sizes a renderer component and paints it without ever validating it, so the
             * layout never runs against the width the row actually has and the chips were placed
             * for a stale one. Laying out here, against the real size, is what centres them.
             */
            override fun paintComponent(g: Graphics) {
                doLayout()
                super.paintComponent(g)
            }
        }.apply {
            background  = bg
            isOpaque    = true
            toolTipText = tooltip
            // Built by the renderer, so it has no parent to inherit a reading direction from.
            applyComponentOrientation(this@KeyboardPanel.componentOrientation)
            tokens.forEach { add(KeyChip(it, muted)) }
        }

        /**
         * Centres the chips in the exact middle of whatever width the cell is given.
         *
         * `FlowLayout` sizes a panel to its preferred width, and a table cell then renders that
         * narrow panel against the edge it happens to land on, so the chips sat off-centre no
         * matter what the column was worth. Measuring inside the panel itself and placing the
         * chips around the halfway mark centres them by construction: a two-key and a four-key
         * shortcut both read as the cell's content, at any scale and either reading direction.
         */
        private inner class ChipBandLayout : LayoutManager {
            private val gap = UIScale.scale(CHIP_GAP)

            override fun addLayoutComponent(name: String?, comp: Component) = Unit
            override fun removeLayoutComponent(comp: Component) = Unit

            private val Container.bandWidth: Int
                get() = (0 until componentCount).sumOf { index ->
                    getComponent(index).preferredSize.width + if (index > 0) gap else 0
                }

            private val Container.bandHeight: Int
                get() = (0 until componentCount).maxOfOrNull { getComponent(it).preferredSize.height } ?: 0

            override fun preferredLayoutSize(parent: Container) =
                Dimension(parent.bandWidth, parent.bandHeight)

            override fun minimumLayoutSize(parent: Container) = preferredLayoutSize(parent)

            override fun layoutContainer(parent: Container) {
                val mirrored = !parent.componentOrientation.isLeftToRight
                var x = ((parent.width - parent.bandWidth) / 2).coerceAtLeast(0)
                val y = ((parent.height - parent.bandHeight) / 2).coerceAtLeast(0)
                for (index in 0 until parent.componentCount) {
                    val child = parent.getComponent(index)
                    val size = child.preferredSize
                    child.setBounds(x, y, size.width, size.height)
                    x += if (mirrored) -(size.width + gap) else size.width + gap
                }
            }
        }

        /** Splits a binding into individual displayable key tokens. */
        private fun tokenizeBinding(binding: HotkeyBinding): List<String> = buildList {
            if (binding.modifiers and InputEvent.CTRL_DOWN_MASK  != 0) add("Ctrl")
            if (binding.modifiers and InputEvent.ALT_DOWN_MASK   != 0) add("Alt")
            if (binding.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) add("Shift")
            if (binding.modifiers and InputEvent.META_DOWN_MASK  != 0) add("⌘")
            add(KeyEvent.getKeyText(binding.keyCode))
        }
    }

    /**
     * A single keyboard key rendered as a rounded-rectangle badge.
     * Multi-platform friendly + FlatLaf compatible.
     * Clean single-border style (no nested box effect).
     */
    private inner class KeyChip(label: String, private val muted: Boolean) : JLabel(label) {

        private val arc = UIScale.scale(CHIP_ARC)
        private val borderAlpha = if (muted) 90 else 150

        init {
            isOpaque = false
            horizontalAlignment = CENTER
            verticalAlignment = CENTER

            // Multi-platform mono font selection
            val monoFont = createMonoFont(
                size = this@KeyboardPanel.font.size - 1,
                style = if (muted) Font.ITALIC else Font.PLAIN
            )
            font = keyChipFont(label, monoFont, this@KeyboardPanel.font)

            foreground = if (muted) {
                UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            } else {
                UIManager.getColor("Label.foreground") ?: Color.BLACK
            }

            // Balanced padding
            border = BorderFactory.createEmptyBorder(
                UIScale.scale(CHIP_PADDING_Y), UIScale.scale(CHIP_PADDING_X),
                UIScale.scale(CHIP_PADDING_Y), UIScale.scale(CHIP_PADDING_X)
            )
        }

        private fun createMonoFont(size: Int, style: Int): Font {
            val candidates = arrayOf(
                "JetBrains Mono",
                "Cascadia Mono",
                "Cascadia Code",
                "Menlo",
                "Monaco",
                "Consolas",
                "DejaVu Sans Mono",
                "Liberation Mono",
                "Courier New",
                "Courier",
                Font.MONOSPACED
            )

            for (name in candidates) {
                val f = Font(name, style, size)
                if (f.family != "Dialog") {
                    return f
                }
            }
            return Font(Font.MONOSPACED, style, size)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val bg = if (muted) {
                    UIManager.getColor("Panel.background")?.let {
                        it.withAlpha(35)
                    } ?: Color(0, 0, 0, 20)
                } else {
                    UIManager.getColor("Button.background") ?: Color(235, 235, 235)
                }

                val borderColor = (UIManager.getColor("Component.borderColor") ?: Color(160, 160, 160))
                    .withAlpha(borderAlpha)

                // Background
                g2.color = bg
                g2.fillRoundRect(0, 0, width, height, arc, arc)

                // Single clean border
                g2.color = borderColor
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

            } finally {
                g2.dispose()
            }

            // Let Swing + FlatLaf handle the text rendering
            super.paintComponent(g)
        }
    }

    /**
     * The scope column: a pill, not a word.
     *
     * The scope is state that can be clicked to change, and as plain text it read like a second
     * name sitting beside the command's. A pill gives it the shape of a value, and the accent
     * colour on the scopes that can be toggled says that it is one you can act on.
     */
    private inner class ScopeColumnRenderer : TableCellRenderer {
        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            val action = actionAt(row)
            val bg = when {
                action == null -> groupRowBackground()
                sel -> t.selectionBackground
                else -> t.background
            }
            if (action == null) {
                // Category heading: no scope to report.
                return JPanel().apply { background = bg; isOpaque = true }
            }

            val scope = bindingFor(action)?.scope ?: HotkeyScope.GLOBAL
            val badge = ScopeBadge(
                label = value as? String ?: "",
                accent = scope != HotkeyScope.CAPTURE,
            ).apply {
                toolTipText = localizationManager.getString(
                    if (scope == HotkeyScope.CAPTURE) "settings_hotkeys.scope_fixed_hint"
                    else "settings_hotkeys.scope_toggle_hint"
                )
            }
            return JPanel(GridBagLayout()).apply {
                background = bg
                isOpaque = true
                applyComponentOrientation(this@KeyboardPanel.componentOrientation)
                add(badge)
            }
        }
    }

    /** A small rounded label: the scope's value, sized to its text. */
    private inner class ScopeBadge(label: String, private val accent: Boolean) : JLabel(label) {
        private val arc = UIScale.scale(CHIP_ARC)

        init {
            isOpaque = false
            horizontalAlignment = CENTER
            applyComponentOrientation(this@KeyboardPanel.componentOrientation)
            font = this@KeyboardPanel.font.deriveFont(this@KeyboardPanel.font.size - 1f)
            foreground = if (accent) {
                UIManager.getColor("Component.accentColor") ?: this@KeyboardPanel.foreground
            } else {
                UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            }
            border = BorderFactory.createEmptyBorder(
                UIScale.scale(2), UIScale.scale(8), UIScale.scale(2), UIScale.scale(8)
            )
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val base = if (accent) {
                    UIManager.getColor("Component.accentColor") ?: foreground
                } else {
                    UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                }
                g2.color = base.withAlpha(26)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                g2.color = base.withAlpha(70)
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /**
     * The commands table.
     *
     * It differs from a plain table in two ways: the group separators are painted over the rows,
     * and the row actions are drawn rather than built from components. The second is why its
     * accessibility tree is not the default one — see [getAccessibleContext].
     */
    private inner class CommandsTable(model: TableModel) : JTable(model) {

        private var commandsAccessible: AccessibleContext? = null

        /**
         * Draws the hairline above each category heading.
         *
         * A heading only reads as a break if something separates it from the commands of the
         * group before it. Drawn after the cells rather than as a cell border because the row
         * renderers are opaque and would paint over a line drawn underneath them.
         */
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val lineColor = UIManager.getColor("Separator.foreground")
                ?: UIManager.getColor("Component.borderColor")
                ?: return
            val g2 = g.create() as Graphics2D
            try {
                g2.color = lineColor.withAlpha(110)
                var y = 0
                for (index in 0 until rowCount) {
                    if (rows.getOrNull(index) is CommandRow.Group && index > 0) {
                        g2.drawLine(0, y, width - 1, y)
                    }
                    y += getRowHeight(index)
                }
            } finally {
                g2.dispose()
            }
        }

        /**
         * The default table accessibility, with the painted row actions added.
         *
         * A cell is announced from its column and its value, and this column has one word of
         * heading and no value at all — the squares are paint. Left alone, a screen reader would
         * report an empty cell and never reach "change" or "clear". For each command row the
         * actions cell is replaced with an object that names those two actions and performs them.
         */
        override fun getAccessibleContext(): AccessibleContext {
            commandsAccessible?.let { return it }
            val created = object : AccessibleJTable() {
                override fun getAccessibleChild(i: Int): Accessible? {
                    val columns = columnCount
                    if (columns <= 0) return super.getAccessibleChild(i)
                    val row = i / columns
                    val column = i % columns
                    val command = actionAt(row)
                    if (column == COL_ACTIONS && command != null) return RowActionsAccessible(command)
                    return super.getAccessibleChild(i)
                }
            }
            commandsAccessible = created
            return created
        }
    }

    /**
     * One command's row actions, as the accessibility tree sees them.
     *
     * It stands in for the two painted squares: it says which command's actions these are, and
     * holds one button per action, so each is announced and can be used without a pointer.
     */
    private inner class RowActionsAccessible(
        private val action: HotkeyAction,
    ) : AccessibleContext(), Accessible {

        private val buttons: List<RowActionAccessibleButton> by lazy {
            listOf(CommandRowAction.EDIT, CommandRowAction.CLEAR).map { kind ->
                RowActionAccessibleButton(action, kind, this)
            }
        }

        override fun getAccessibleContext(): AccessibleContext = this

        override fun getAccessibleName(): String =
            localizationManager.getString("settings_hotkeys.row_actions_accessible_name")

        override fun getAccessibleDescription(): String = localizationManager.getString(
            "settings_hotkeys.row_actions_accessible_description",
            actionDisplayName(action),
        )

        override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PANEL
        override fun getAccessibleParent(): Accessible = table
        override fun getAccessibleIndexInParent(): Int = -1
        override fun getAccessibleChildrenCount(): Int = buttons.size
        override fun getAccessibleChild(i: Int): Accessible? = buttons.getOrNull(i)
        override fun getLocale(): Locale = table.locale

        override fun getAccessibleStateSet(): AccessibleStateSet = AccessibleStateSet().apply {
            if (isEditable(action)) add(AccessibleState.ENABLED)
            add(AccessibleState.SHOWING)
            add(AccessibleState.VISIBLE)
        }
    }

    /**
     * One row action, as a button for assistive technology.
     *
     * It cannot be a component either — a table cell renderer cannot hold one per row — but it
     * answers what a screen reader asks of a button: what it is called, whether it can be used
     * now, and what activating it does.
     */
    private inner class RowActionAccessibleButton(
        private val command: HotkeyAction,
        private val kind: CommandRowAction,
        private val parentCell: Accessible,
    ) : AccessibleContext(), Accessible, AccessibleAction {

        private val label: String
            get() = localizationManager.getString(
                if (kind == CommandRowAction.EDIT) "settings_hotkeys.row_edit_tooltip"
                else "settings_hotkeys.row_clear_tooltip"
            )

        override fun getAccessibleContext(): AccessibleContext = this
        override fun getAccessibleName(): String = label

        override fun getAccessibleDescription(): String = localizationManager.getString(
            "settings_hotkeys.row_actions_accessible_description",
            actionDisplayName(command),
        )

        override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON
        override fun getAccessibleParent(): Accessible = parentCell
        override fun getAccessibleIndexInParent(): Int =
            if (kind == CommandRowAction.EDIT) 0 else 1

        override fun getAccessibleChildrenCount(): Int = 0
        override fun getAccessibleChild(i: Int): Accessible? = null
        override fun getLocale(): Locale = table.locale

        override fun getAccessibleStateSet(): AccessibleStateSet = AccessibleStateSet().apply {
            // A global shortcut is not editable while the global switch is off, and the row says
            // so in its own way; the accessibility state has to agree rather than offer an action
            // that will refuse to run.
            if (isEditable(command)) add(AccessibleState.ENABLED)
            add(AccessibleState.SHOWING)
            add(AccessibleState.VISIBLE)
            add(AccessibleState.FOCUSABLE)
        }

        override fun getAccessibleAction(): AccessibleAction = this
        override fun getAccessibleActionCount(): Int = 1
        override fun getAccessibleActionDescription(i: Int): String? = if (i == 0) label else null

        override fun doAccessibleAction(i: Int): Boolean {
            if (i != 0) return false
            if (!isEditable(command)) return false
            val row = rows.indexOfFirst { it.action == command }
            if (row < 0) return false
            table.setRowSelectionInterval(row, row)
            when (kind) {
                CommandRowAction.EDIT -> onEditSelected()
                CommandRowAction.CLEAR -> onClearSelected()
            }
            return true
        }
    }

    /**
     * The two actions a row offers: change its shortcut, or clear it.
     *
     * Drawn rather than built from buttons because a table cell cannot hold live components per
     * row — a renderer is reused for every row — and a column of real buttons would introduce a
     * second focus model inside the table. Painted, they keep the table's single tab stop and
     * still give each action its own click target.
     */
    private inner class RowActionsRenderer : TableCellRenderer {
        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            val action = actionAt(row)
            return RowActionsCell(
                leftToRight = this@KeyboardPanel.componentOrientation.isLeftToRight,
                background = when {
                    action == null -> groupRowBackground()
                    sel -> t.selectionBackground
                    else -> t.background
                },
                editable = action != null && isEditable(action),
                selected = sel,
                backgroundIsGroup = action == null,
                hovered = if (row == hoveredRow) hoveredAction else null,
            )
        }
    }

    /**
     * One row's action squares.
     *
     * A heading gets an empty cell: it carries no binding, so there is nothing to change or clear,
     * and the space stays blank rather than offering two disabled squares on every heading.
     */
    private inner class RowActionsCell(
        private val leftToRight: Boolean,
        private val background: Color,
        private val editable: Boolean,
        private val selected: Boolean,
        private val backgroundIsGroup: Boolean,
        private val hovered: CommandRowAction?,
    ) : JComponent() {

        init {
            isOpaque = false
            applyComponentOrientation(this@KeyboardPanel.componentOrientation)
        }

        /** Each square answers for itself, so the tooltip names the one under the pointer. */
        override fun getToolTipText(event: MouseEvent): String? {
            if (backgroundIsGroup) return null
            val target = actionAt(event.point) ?: return null
            if (!editable) return null
            return localizationManager.getString(
                if (target == CommandRowAction.EDIT) "settings_hotkeys.row_edit_tooltip"
                else "settings_hotkeys.row_clear_tooltip"
            )
        }

        private fun squares(): List<Pair<CommandRowAction, Rectangle>> = CommandRowActions.bounds(
            columnWidth = width,
            height = height,
            leftToRight = leftToRight,
            side = UIScale.scale(ROW_ACTION_SIZE),
            gap = UIScale.scale(ROW_ACTION_GAP),
            margin = UIScale.scale(ROW_ACTION_MARGIN),
        )

        private fun actionAt(point: Point): CommandRowAction? =
            squares().firstOrNull { (_, rect) -> rect.contains(point) }?.first

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.color = background
                g2.fillRect(0, 0, width, height)
                if (backgroundIsGroup) return

                val editColor = UIManager.getColor("Label.foreground") ?: Color.BLACK
                val clearColor = UIManager.getColor("Label.warningForeground")
                    ?: UIManager.getColor("Component.errorColor")
                    ?: Color.GRAY
                val arc = UIScale.scale(CHIP_ARC)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                squares().forEach { (target, rect) ->
                    val isHovered = hovered == target && editable
                    // Clearing is the destructive half, so it is tinted with the warning colour
                    // rather than matching the edit square exactly.
                    val base = if (target == CommandRowAction.EDIT) editColor else clearColor
                    val fill = when {
                        !editable -> 0
                        isHovered -> if (target == CommandRowAction.EDIT) 34 else 42
                        // A resting hint of the hover wash, so the pair reads as buttons at a
                        // glance instead of as two faint marks discovered by accident.
                        else -> if (selected) 24 else 14
                    }
                    if (fill > 0) {
                        g2.color = base.withAlpha(fill)
                        g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc)
                    }
                    val alpha = when {
                        !editable -> 70
                        isHovered -> 255
                        selected -> 245
                        else -> 210
                    }
                    // Every visible row is repainted on a scroll, so the coloured glyph comes
                    // from the cache rather than being rendered again.
                    val glyphs = if (target == CommandRowAction.EDIT) editGlyph else clearGlyph
                    val glyph = glyphs.forColor(base.withAlpha(alpha))
                    glyph.paintIcon(
                        this,
                        g2,
                        rect.x + (rect.width - glyph.iconWidth) / 2,
                        rect.y + (rect.height - glyph.iconHeight) / 2,
                    )
                }
            } finally {
                g2.dispose()
            }
        }
    }

}
