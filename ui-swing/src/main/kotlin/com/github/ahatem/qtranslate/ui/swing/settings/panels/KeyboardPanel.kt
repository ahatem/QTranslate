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
import com.github.ahatem.qtranslate.ui.swing.shared.util.formatChord
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

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
    /** What the selected command does, and what it needs. */
    private lateinit var detailsLabel: JLabel
    /** Shown when the active filter matches no command. */
    private lateinit var emptyHint: JLabel

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
        const val ACTION_MIN_WIDTH = 170
        const val ACTION_PREFERRED_WIDTH = 310
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

    private val COL_ACTION = 0
    private val COL_HOTKEY = 1
    private val COL_SCOPE  = 2

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

    private fun buildUI() {
        addSeparator(localizationManager.getString("settings_hotkeys.global_group"))

        enableCheck = addCheckbox(
            text     = localizationManager.getString("settings_hotkeys.enable_global"),
            selected = false,
            onChange = { enabled ->
                applyDraft(store) { it.copy(isGlobalHotkeysEnabled = enabled) }
            }
        )

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
                if (!isUpdatingFromState) {
                    val preset = selectedItem as? HotkeyPresetKind ?: return@addActionListener
                    applyDraft(store) { HotkeyDraftOperations.replacePreset(it, preset) }
                }
            }
        }
        addRow(localizationManager.getString("settings_hotkeys.preset_label"), presetCombo)

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

        addRow(localizationManager.getString("settings_hotkeys.filter_label"), buildFilterRow())

        val model = object : DefaultTableModel(
            arrayOf(
                localizationManager.getString("settings_hotkeys.column_action"),
                localizationManager.getString("settings_hotkeys.column_hotkey"),
                localizationManager.getString("settings_hotkeys.column_scope")
            ), 0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
            override fun getColumnClass(col: Int) = String::class.java
        }

        table = JTable(model).apply {
            fillsViewportHeight = true
            rowHeight           = UIScale.scale(ROW_HEIGHT)
            autoResizeMode      = JTable.AUTO_RESIZE_ALL_COLUMNS
            setShowGrid(false)
            intercellSpacing    = Dimension(0, 0)
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            putClientProperty("FlatLaf.style", "showCellFocusIndicator: false")

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

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0) return
                    val action = actionAt(row) ?: return
                    when {
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
            })

            selectionModel.addListSelectionListener {
                updateButtonStates()
                updateDetails()
            }
        }

        // The viewport is sized for the whole list rather than the filtered one, so changing the
        // filter does not resize the panel underneath the pointer.
        val fullRows = actionOrder.size + categoryOrder.size
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(4), 0, 0, 0)
            .add(JScrollPane(table).apply {
                border = themeAwareBorder()
                // Every row and the header, so the table opens showing all of its commands and only
                // grows a scrollbar once the panel no longer fits beside them.
                preferredSize = Dimension(
                    UIScale.scale(TABLE_PREFERRED_WIDTH),
                    table.rowHeight * fullRows + table.tableHeader.preferredSize.height + UIScale.scale(4)
                )
            })

        emptyHint = hintLabel().apply { isVisible = false }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(2), UIScale.scale(2), 0, 0)
            .add(emptyHint)

        editButton  = JButton(localizationManager.getString("settings_hotkeys.edit_button"))
        clearButton = JButton(localizationManager.getString("settings_hotkeys.clear_button"))
        resetButton = JButton(localizationManager.getString("settings_hotkeys.reset_button"))

        editButton.isEnabled  = false
        clearButton.isEnabled = false

        editButton.addActionListener  { onEditSelected() }
        clearButton.addActionListener { onClearSelected() }
        resetButton.addActionListener { onResetAll() }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(6), 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(4), 0)).apply {
                isOpaque = false
                add(editButton)
                add(clearButton)
                add(resetButton)
            })

        conflictHint = JLabel("").apply {
            isOpaque = false
            isVisible = false
            foreground = UIManager.getColor("Label.warningForeground")
                ?: UIManager.getColor("Component.errorColor")
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(4), 0, 0, 0)
            .add(conflictHint)

        detailsLabel = JLabel("").apply {
            isOpaque = false
            verticalAlignment = SwingConstants.TOP
            text = htmlText(localizationManager.getString("settings_hotkeys.details_placeholder"))
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(4), 0, 0, 0)
            .add(detailsLabel)

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
        val confirmed = JOptionPane.showConfirmDialog(
            this,
            localizationManager.getString("settings_hotkeys.reset_confirmation"),
            localizationManager.getString("settings_hotkeys.reset_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION
        if (!confirmed) return
        applyDraft(store) { it.copy(hotkeys = HotkeyPresets.LEGACY.map { binding -> binding.copy() }) }
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
    internal fun detailsTextForTest(): String = detailsLabel.text

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

        val model = table.model as DefaultTableModel
        model.rowCount = rows.size
        rows.forEachIndexed { index, row ->
            when (row) {
                is CommandRow.Group -> {
                    model.setValueAt(categoryLabel(row.category), index, COL_ACTION)
                    model.setValueAt(null, index, COL_HOTKEY)
                    model.setValueAt("", index, COL_SCOPE)
                }
                is CommandRow.Command -> {
                    val binding = bindings.find { it.action == row.action }
                    model.setValueAt(actionDisplayName(row.action), index, COL_ACTION)
                    model.setValueAt(binding, index, COL_HOTKEY)
                    model.setValueAt(scopeLabel(binding?.scope ?: HotkeyScope.GLOBAL), index, COL_SCOPE)
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
            detailsLabel.text = htmlText(localizationManager.getString("settings_hotkeys.details_placeholder"))
            return
        }
        val parts = buildList {
            add(descriptionFor(action))
            requirementFor(action)?.let(::add)
            if (action in conflictingActions) {
                val others = conflicts.filter { action in it.actions }
                    .flatMap { it.actions }
                    .filter { it != action }
                    .distinct()
                    .joinToString(", ") { actionDisplayName(it) }
                if (others.isNotEmpty()) {
                    add(localizationManager.getString("settings_hotkeys.conflict_row_tooltip", others))
                }
            }
        }
        detailsLabel.text = htmlText(parts.joinToString("\n"))
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
     * The action column: a command's name, or a category heading.
     *
     * A conflicting row is marked with a glyph as well as a colour, because colour alone is not
     * readable for everyone, and the tooltip repeats the explanation in words.
     */
    private inner class ActionColumnRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col)
            val label = value?.toString().orEmpty()
            val commandRow = rows.getOrNull(row)

            if (commandRow is CommandRow.Group) {
                font = t.font.deriveFont(Font.BOLD)
                foreground = UIManager.getColor("Label.disabledForeground")
                text = label
                toolTipText = null
                return this
            }

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
                    JPanel().apply { background = bg }
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
         * Builds a row of key chips, vertically and horizontally centered in the table cell.
         * Uses a GridBagLayout outer panel so the inner FlowLayout strip sits in the middle
         * of the fixed-height row rather than being pinned to the top.
         */
        private fun chipRow(
            tokens: List<String>,
            bg: Color,
            muted: Boolean,
            tooltip: String? = null
        ): JPanel = JPanel(GridBagLayout()).apply {
            background  = bg
            toolTipText = tooltip
            val inner = JPanel(FlowLayout(FlowLayout.CENTER, UIScale.scale(CHIP_GAP), 0)).apply {
                isOpaque = false
                tokens.forEach { add(KeyChip(it, muted)) }
            }
            add(inner)   // default GridBagConstraints: anchor = CENTER, no fill
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
                        Color(it.red, it.green, it.blue, 35)
                    } ?: Color(0, 0, 0, 20)
                } else {
                    UIManager.getColor("Button.background") ?: Color(235, 235, 235)
                }

                val borderColor = (UIManager.getColor("Component.borderColor") ?: Color(160, 160, 160))
                    .let { Color(it.red, it.green, it.blue, borderAlpha) }

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

    private inner class ScopeColumnRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.CENTER }

        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col)
            val action = actionAt(row)

            val label = value as? String ?: ""
            text       = label
            if (action == null) {
                // Category heading: no scope to report.
                foreground = UIManager.getColor("Label.disabledForeground")
                toolTipText = null
                return this
            }
            foreground = if (sel) UIManager.getColor("Table.selectionForeground")
            else     UIManager.getColor("Component.accentColor")
                ?: UIManager.getColor("Table.foreground")
            font        = font.deriveFont(Font.PLAIN)
            val scope = bindingFor(action)?.scope
            toolTipText = if (scope == HotkeyScope.CAPTURE) {
                localizationManager.getString("settings_hotkeys.scope_fixed_hint")
            } else {
                localizationManager.getString("settings_hotkeys.scope_toggle_hint")
            }
            return this
        }
    }

}
