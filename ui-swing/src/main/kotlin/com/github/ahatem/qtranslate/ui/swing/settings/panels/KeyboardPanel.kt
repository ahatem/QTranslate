package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

class KeyboardPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    /** Called just before the hotkey recorder dialog opens to prevent accidental triggers. */
    private val pauseGlobalHotkeys:  (() -> Unit)? = null,
    /** Called after the recorder dialog closes to restore the previous enabled state. */
    private val resumeGlobalHotkeys: (() -> Unit)? = null,
) : SettingsPanel() {

    private lateinit var enableCheck: JCheckBox
    private lateinit var table:       JTable
    private lateinit var editButton:  JButton
    private lateinit var clearButton: JButton
    private lateinit var resetButton: JButton

    private val actionOrder = listOf(
        HotkeyAction.SHOW_MAIN_WINDOW,
        HotkeyAction.SHOW_QUICK_TRANSLATE,
        HotkeyAction.LISTEN_TO_TEXT,
        HotkeyAction.OPEN_OCR,
        HotkeyAction.REPLACE_WITH_TRANSLATION,
        HotkeyAction.CYCLE_TARGET_LANGUAGE,
        HotkeyAction.SHOW_DICTIONARY,
        HotkeyAction.SHOW_IMAGES,
        HotkeyAction.TRANSLATE,
        HotkeyAction.FOCUS_INPUT,
        HotkeyAction.FOCUS_OUTPUT,
        HotkeyAction.FOCUS_EXTRA_OUTPUT
    )

    // SHOW_MAIN_WINDOW can now have a custom keystroke — only its scope is locked to GLOBAL.
    private val nonEditableActions    = emptySet<HotkeyAction>()
    private val nonScopeToggleActions = setOf(HotkeyAction.SHOW_MAIN_WINDOW)

    private val COL_ACTION = 0
    private val COL_HOTKEY = 1
    private val COL_SCOPE  = 2

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

        addSeparator(localizationManager.getString("settings_hotkeys.assignments_group"))
        addHint(localizationManager.getString("settings_hotkeys.edit_hint"))

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

        actionOrder.forEach { action ->
            model.addRow(arrayOf<Any>(actionDisplayName(action), "", scopeLabel(HotkeyScope.GLOBAL)))
        }

        table = JTable(model).apply {
            fillsViewportHeight = true
            rowHeight           = 34
            setShowGrid(false)
            intercellSpacing    = Dimension(0, 0)
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            putClientProperty("FlatLaf.style", "showCellFocusIndicator: false")

            columnModel.getColumn(COL_ACTION).apply { preferredWidth = UIScale.scale(200); minWidth = UIScale.scale(150) }
            columnModel.getColumn(COL_HOTKEY).apply {
                preferredWidth = UIScale.scale(150)
                minWidth       = 110
                cellRenderer   = HotkeyColumnRenderer()
            }
            columnModel.getColumn(COL_SCOPE).apply {
                preferredWidth = UIScale.scale(130)
                minWidth       = 80
                cellRenderer   = ScopeColumnRenderer()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0) return
                    when {
                        // Double-click hotkey column — open recorder
                        col == COL_HOTKEY && e.clickCount == 2 -> {
                            setRowSelectionInterval(row, row)
                            onEditSelected()
                        }
                        // Single click scope column — toggle global/local
                        col == COL_SCOPE -> {
                            setRowSelectionInterval(row, row)
                            onToggleScope(row)
                        }
                    }
                }
            })

            selectionModel.addListSelectionListener { updateButtonStates() }
        }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0)
            .add(JScrollPane(table).apply {
                preferredSize = Dimension(UIScale.scale(580), UIScale.scale(actionOrder.size * 34 + 4))
                border = themeAwareBorder()
            })

        editButton  = JButton(localizationManager.getString("settings_hotkeys.edit_button"))
        clearButton = JButton(localizationManager.getString("settings_hotkeys.clear_button"))
        resetButton = JButton(localizationManager.getString("settings_hotkeys.reset_button"))

        editButton.isEnabled  = false
        clearButton.isEnabled = false

        editButton.addActionListener  { onEditSelected() }
        clearButton.addActionListener { onClearSelected() }
        resetButton.addActionListener { onResetAll() }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(6, 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEADING, 4, 0)).apply {
                isOpaque = false
                add(editButton)
                add(clearButton)
                add(resetButton)
            })

        finishLayout()
    }


    private fun onEditSelected() {
        val row    = table.selectedRow.takeIf { it >= 0 } ?: return
        val action = actionOrder[row]
        if (action in nonEditableActions) return

        val current = store.state.value.workingConfiguration
            .hotkeys.find { it.action == action }

        val result = HotkeyRecorderDialog.show(
            owner               = SwingUtilities.getWindowAncestor(this),
            action              = action,
            current             = current,
            localizer           = localizationManager,
            pauseGlobalHotkeys  = pauseGlobalHotkeys,
            resumeGlobalHotkeys = resumeGlobalHotkeys,
        ) ?: return

        saveBinding(result)
    }

    private fun onClearSelected() {
        val row    = table.selectedRow.takeIf { it >= 0 } ?: return
        val action = actionOrder[row]
        if (action in nonEditableActions) return
        // Keep existing scope when clearing
        val existing = store.state.value.workingConfiguration.hotkeys.find { it.action == action }
        saveBinding(HotkeyBinding(action = action, keyCode = 0, modifiers = 0, scope = existing?.scope ?: HotkeyScope.GLOBAL))
    }

    private fun onToggleScope(row: Int) {
        val action = actionOrder[row]
        if (action == HotkeyAction.SHOW_MAIN_WINDOW) {
            // For SHOW_MAIN_WINDOW the scope is locked to GLOBAL, but the cell
            // acts as a "Double Ctrl" on/off toggle instead.
            val current = store.state.value.workingConfiguration.hotkeys
                .find { it.action == action } ?: return
            saveBinding(current.copy(isDoubleCtrlEnabled = !current.isDoubleCtrlEnabled))
            return
        }
        if (action in nonScopeToggleActions) return
        val current = store.state.value.workingConfiguration.hotkeys.find { it.action == action }
            ?: return
        val newScope = if (current.scope == HotkeyScope.GLOBAL) HotkeyScope.LOCAL else HotkeyScope.GLOBAL
        saveBinding(current.copy(scope = newScope))
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
        store.dispatch(SettingsIntent.ToggleSetting { it.copy(hotkeys = HotkeyBinding.DEFAULTS) })
    }

    private fun saveBinding(binding: HotkeyBinding) {
        store.dispatch(SettingsIntent.ToggleSetting { config ->
            val updated = config.hotkeys.map { b ->
                if (b.action == binding.action) binding else b
            }
            val final = if (updated.any { it.action == binding.action }) updated
            else updated + binding
            config.copy(hotkeys = final)
        })
    }

    private fun updateButtonStates() {
        val row        = table.selectedRow
        val isEditable = row >= 0 && actionOrder.getOrNull(row) !in nonEditableActions
        val enabled    = enableCheck.isSelected
        editButton.isEnabled  = isEditable && enabled
        clearButton.isEnabled = isEditable && enabled
    }


    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            enableCheck.isSelected = c.isGlobalHotkeysEnabled

            val model = table.model as DefaultTableModel
            actionOrder.forEachIndexed { row, action ->
                val binding = c.hotkeys.find { it.action == action }
                model.setValueAt(binding, row, COL_HOTKEY)
                model.setValueAt(scopeLabel(binding?.scope ?: HotkeyScope.GLOBAL), row, COL_SCOPE)
            }

            table.isEnabled = c.isGlobalHotkeysEnabled
            updateButtonStates()
        }
    }


    private fun actionDisplayName(action: HotkeyAction): String = when (action) {
        HotkeyAction.SHOW_MAIN_WINDOW         -> localizationManager.getString("settings_hotkeys.action_show_main")
        HotkeyAction.SHOW_QUICK_TRANSLATE     -> localizationManager.getString("settings_hotkeys.action_quick_translate")
        HotkeyAction.LISTEN_TO_TEXT           -> localizationManager.getString("settings_hotkeys.action_listen")
        HotkeyAction.OPEN_OCR                 -> localizationManager.getString("settings_hotkeys.action_ocr")
        HotkeyAction.REPLACE_WITH_TRANSLATION -> localizationManager.getString("settings_hotkeys.action_replace")
        HotkeyAction.CYCLE_TARGET_LANGUAGE    -> localizationManager.getString("settings_hotkeys.action_cycle_language")
        HotkeyAction.SHOW_DICTIONARY          -> localizationManager.getString("settings_hotkeys.action_show_dictionary")
        HotkeyAction.SHOW_IMAGES              -> localizationManager.getString("settings_hotkeys.action_show_images")
        HotkeyAction.TRANSLATE                -> localizationManager.getString("settings_hotkeys.action_translate")
        HotkeyAction.FOCUS_INPUT              -> localizationManager.getString("settings_hotkeys.action_focus_input")
        HotkeyAction.FOCUS_OUTPUT             -> localizationManager.getString("settings_hotkeys.action_focus_output")
        HotkeyAction.FOCUS_EXTRA_OUTPUT       -> localizationManager.getString("settings_hotkeys.action_focus_extra_output")
        HotkeyAction.COPY_TRANSLATION         -> localizationManager.getString("settings_hotkeys.action_copy_translation")
        HotkeyAction.CLEAR_INPUT              -> localizationManager.getString("settings_hotkeys.action_clear_input")
        HotkeyAction.SWAP_LANGUAGES           -> localizationManager.getString("settings_hotkeys.action_swap_languages")
        HotkeyAction.OPEN_SETTINGS            -> localizationManager.getString("settings_hotkeys.action_open_settings")
        HotkeyAction.SHOW_HISTORY             -> localizationManager.getString("settings_hotkeys.action_show_history")
        HotkeyAction.TRANSLATE_DOCUMENT       -> localizationManager.getString("settings_hotkeys.action_translate_document")
    }

    private fun scopeLabel(scope: HotkeyScope): String = when (scope) {
        HotkeyScope.GLOBAL -> localizationManager.getString("settings_hotkeys.scope_global")
        HotkeyScope.LOCAL  -> localizationManager.getString("settings_hotkeys.scope_local")
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
            val action  = actionOrder.getOrNull(row)
            val binding = value as? HotkeyBinding
            val bg      = if (sel) t.selectionBackground else t.background

            return when {
                action == HotkeyAction.SHOW_MAIN_WINDOW && (binding == null || !binding.hasBinding) ->
                    chipRow(
                        listOf(localizationManager.getString("settings_hotkeys.double_ctrl")),
                        bg, muted = true,
                        tooltip = localizationManager.getString("settings_hotkeys.show_main_tooltip")
                    )
                binding == null || !binding.hasBinding ->
                    chipRow(
                        listOf(localizationManager.getString("settings_hotkeys.no_binding")),
                        bg, muted = true
                    )
                else ->
                    chipRow(
                        tokenizeBinding(binding), bg, muted = false,
                        tooltip = if (action == HotkeyAction.SHOW_MAIN_WINDOW)
                            localizationManager.getString("settings_hotkeys.show_main_tooltip") else null
                    )
            }
        }

        /**
         * Builds a row of key chips, vertically and horizontally centered in the table cell.
         * Uses a GridBagLayout outer panel so the inner FlowLayout strip sits in the middle
         * of the fixed-height (34 px) row rather than being pinned to the top.
         */
        private fun chipRow(
            tokens: List<String>,
            bg: Color,
            muted: Boolean,
            tooltip: String? = null
        ): JPanel = JPanel(GridBagLayout()).apply {
            background  = bg
            toolTipText = tooltip
            val inner = JPanel(FlowLayout(FlowLayout.CENTER, 4, 0)).apply {
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
     *
     * Extends [JLabel] so all text is drawn by Swing's own text pipeline (correct
     * ClearType / LCD hints, FlatLaf rendering context) instead of a raw
     * [Graphics2D.drawString] call, which skips those hints and renders poorly.
     * The rounded background is painted in [paintComponent] *before* calling super,
     * so the label's text lands on top of it.
     */
    private inner class KeyChip(label: String, private val muted: Boolean) : JLabel(label) {
        private val arc = 6

        init {
            isOpaque          = false
            horizontalAlignment = CENTER
            font      = Font(Font.MONOSPACED, if (muted) Font.ITALIC else Font.PLAIN,
                             this@KeyboardPanel.font.size - 1)
            foreground = if (muted) UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                         else       UIManager.getColor("Label.foreground")          ?: Color.BLACK
            border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
        }

        override fun paintComponent(g: Graphics) {
            if (!muted) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                val chipBg     = UIManager.getColor("Button.background")     ?: Color(235, 235, 235)
                val chipBorder = UIManager.getColor("Component.borderColor") ?: Color(180, 180, 180)
                g2.color = chipBg
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                g2.color = Color(chipBorder.red, chipBorder.green, chipBorder.blue, 180)
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
            super.paintComponent(g)   // Swing text pipeline handles the label text
        }
    }

    private inner class ScopeColumnRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.CENTER }

        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col)
            val action = actionOrder.getOrNull(row)

            if (action == HotkeyAction.SHOW_MAIN_WINDOW) {
                // Repurpose scope cell as a "Double Ctrl" on/off toggle.
                val binding = store.state.value.workingConfiguration.hotkeys
                    .find { it.action == action }
                val enabled = binding?.isDoubleCtrlEnabled ?: true
                text        = if (enabled)
                    localizationManager.getString("settings_hotkeys.double_ctrl_on")
                else
                    localizationManager.getString("settings_hotkeys.double_ctrl_off")
                foreground  = if (enabled)
                    (UIManager.getColor("Component.accentColor") ?: UIManager.getColor("Table.foreground"))
                else
                    UIManager.getColor("Label.disabledForeground")
                font        = font.deriveFont(Font.PLAIN)
                toolTipText = localizationManager.getString("settings_hotkeys.double_ctrl_toggle_hint")
                return this
            }

            val label = value as? String ?: ""
            if (action in nonScopeToggleActions) {
                text        = label
                foreground  = UIManager.getColor("Label.disabledForeground")
                font        = font.deriveFont(Font.ITALIC)
                toolTipText = null
            } else {
                text       = label
                foreground = if (sel) UIManager.getColor("Table.selectionForeground")
                else     UIManager.getColor("Component.accentColor")
                    ?: UIManager.getColor("Table.foreground")
                font       = font.deriveFont(Font.PLAIN)
                toolTipText = localizationManager.getString("settings_hotkeys.scope_toggle_hint")
            }
            return this
        }
    }

    companion object {
        fun formatBinding(binding: HotkeyBinding): String {
            if (!binding.hasBinding) return ""
            val mods = buildString {
                if (binding.modifiers and InputEvent.CTRL_DOWN_MASK  != 0) append("Ctrl + ")
                if (binding.modifiers and InputEvent.ALT_DOWN_MASK   != 0) append("Alt + ")
                if (binding.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) append("Shift + ")
                if (binding.modifiers and InputEvent.META_DOWN_MASK  != 0) append("⌘ + ")
            }
            return mods + KeyEvent.getKeyText(binding.keyCode)
        }
    }
}

object HotkeyRecorderDialog {

    /**
     * Key codes that are never valid as the main key in a shortcut.
     * Modifier keys themselves are handled separately (they update the live preview).
     * Lock keys, system keys, and VK_UNDEFINED are rejected outright.
     */
    private val UNUSABLE_MAIN_KEYS = setOf(
        KeyEvent.VK_UNDEFINED,
        KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_SCROLL_LOCK,
        KeyEvent.VK_PRINTSCREEN, KeyEvent.VK_PAUSE, KeyEvent.VK_CANCEL,
        KeyEvent.VK_WINDOWS, KeyEvent.VK_CONTEXT_MENU
    )

    /** Keys treated as pure modifiers — they update the live combo preview, not the main key. */
    private val MODIFIER_KEYS = setOf(
        KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT,
        KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META
    )

    fun show(
        owner: Window?,
        action: HotkeyAction,
        current: HotkeyBinding?,
        localizer: LocalizationManager,
        pauseGlobalHotkeys:  (() -> Unit)? = null,
        resumeGlobalHotkeys: (() -> Unit)? = null,
    ): HotkeyBinding? {

        // ── mutable recorder state ──────────────────────────────────────────
        var capturedKeyCode   = current?.keyCode   ?: 0
        var capturedModifiers = current?.modifiers  ?: 0
        /** True once the user has pressed (and released or committed) a full combo. */
        var isCaptureDone     = current?.hasBinding == true
        /** Modifier mask from live key events before the main key is pressed. */
        var liveModifiers     = 0
        var isErrorState      = false
        var errorKeyCode      = 0

        // ── dialog ──────────────────────────────────────────────────────────
        val dialog = JDialog(
            owner,
            localizer.getString("settings_hotkeys.recorder_title"),
            Dialog.ModalityType.APPLICATION_MODAL
        )
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.isResizable = false

        val actionName = when (action) {
            HotkeyAction.SHOW_MAIN_WINDOW         -> localizer.getString("settings_hotkeys.action_show_main")
            HotkeyAction.SHOW_QUICK_TRANSLATE     -> localizer.getString("settings_hotkeys.action_quick_translate")
            HotkeyAction.LISTEN_TO_TEXT           -> localizer.getString("settings_hotkeys.action_listen")
            HotkeyAction.OPEN_OCR                 -> localizer.getString("settings_hotkeys.action_ocr")
            HotkeyAction.REPLACE_WITH_TRANSLATION -> localizer.getString("settings_hotkeys.action_replace")
            HotkeyAction.CYCLE_TARGET_LANGUAGE    -> localizer.getString("settings_hotkeys.action_cycle_language")
            HotkeyAction.SHOW_DICTIONARY          -> localizer.getString("settings_hotkeys.action_show_dictionary")
            HotkeyAction.SHOW_IMAGES              -> localizer.getString("settings_hotkeys.action_show_images")
            HotkeyAction.TRANSLATE                -> localizer.getString("settings_hotkeys.action_translate")
            HotkeyAction.FOCUS_INPUT              -> localizer.getString("settings_hotkeys.action_focus_input")
            HotkeyAction.FOCUS_OUTPUT             -> localizer.getString("settings_hotkeys.action_focus_output")
            HotkeyAction.FOCUS_EXTRA_OUTPUT       -> localizer.getString("settings_hotkeys.action_focus_extra_output")
            HotkeyAction.COPY_TRANSLATION         -> localizer.getString("settings_hotkeys.action_copy_translation")
            HotkeyAction.CLEAR_INPUT              -> localizer.getString("settings_hotkeys.action_clear_input")
            HotkeyAction.SWAP_LANGUAGES           -> localizer.getString("settings_hotkeys.action_swap_languages")
            HotkeyAction.OPEN_SETTINGS            -> localizer.getString("settings_hotkeys.action_open_settings")
            HotkeyAction.SHOW_HISTORY             -> localizer.getString("settings_hotkeys.action_show_history")
            HotkeyAction.TRANSLATE_DOCUMENT       -> localizer.getString("settings_hotkeys.action_translate_document")
        }

        // ── colours ──────────────────────────────────────────────────────────
        val mutedFg   = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        val normalFg  = UIManager.getColor("Label.foreground")          ?: Color.BLACK
        val errorFg   = UIManager.getColor("Actions.Red")               ?: Color(200, 60, 60)
        val successFg = UIManager.getColor("Component.accentColor")     ?: Color(50, 150, 80)

        // ── fonts ─────────────────────────────────────────────────────────────
        val baseFont        = UIManager.getFont("TextField.font") ?: dialog.font
        val captureFont     = baseFont.deriveFont(Font.PLAIN,  baseFont.size + 2f)
        val placeholderFont = baseFont.deriveFont(Font.ITALIC, baseFont.size + 2f)

        // ── widgets ─────────────────────────────────────────────────────────
        // Muted label above the field — same pattern as IntelliJ's "Keyboard Shortcut" dialog.
        val actionLabel = JLabel(actionName).apply {
            foreground = mutedFg
        }

        /**
         * The main capture field.  isEditable=false so keystrokes aren't inserted as characters;
         * isFocusable=true so it receives key events.  Text is left-aligned and always normal
         * foreground — only the [hintLabel] below changes colour to indicate state.
         * Background is pinned to TextField.background so FlatLaf keeps the active-field look.
         */
        val inputField = JTextField().apply {
            isEditable          = false
            isFocusable         = true
            font                = captureFont
            horizontalAlignment = JTextField.LEADING   // left-aligned, like IntelliJ
            foreground          = normalFg
            UIManager.getColor("TextField.background")?.let { background = it }
        }

        /**
         * "+" button to the right of the field — opens a menu of special keys that cannot be
         * typed directly (Enter would confirm the dialog, Escape would close it, Tab moves
         * focus, etc.).  Same concept as IntelliJ's "+" next to its shortcut field.
         */
        data class SpecialKey(val name: String, val keyCode: Int, val mods: Int = 0)
        val specialKeys = listOf(
            SpecialKey("Enter",     KeyEvent.VK_ENTER),
            SpecialKey("Escape",    KeyEvent.VK_ESCAPE),
            SpecialKey("Tab",       KeyEvent.VK_TAB),
            SpecialKey("Backspace", KeyEvent.VK_BACK_SPACE),
            SpecialKey("Delete",    KeyEvent.VK_DELETE),
            SpecialKey("Space",     KeyEvent.VK_SPACE),
            SpecialKey("Insert",    KeyEvent.VK_INSERT),
            null,  // separator
            SpecialKey("Home",      KeyEvent.VK_HOME),
            SpecialKey("End",       KeyEvent.VK_END),
            SpecialKey("Page Up",   KeyEvent.VK_PAGE_UP),
            SpecialKey("Page Down", KeyEvent.VK_PAGE_DOWN),
            SpecialKey("↑",         KeyEvent.VK_UP),
            SpecialKey("↓",         KeyEvent.VK_DOWN),
            SpecialKey("←",         KeyEvent.VK_LEFT),
            SpecialKey("→",         KeyEvent.VK_RIGHT),
            null,
            SpecialKey("F1",  KeyEvent.VK_F1),
            SpecialKey("F2",  KeyEvent.VK_F2),
            SpecialKey("F3",  KeyEvent.VK_F3),
            SpecialKey("F4",  KeyEvent.VK_F4),
            SpecialKey("F5",  KeyEvent.VK_F5),
            SpecialKey("F6",  KeyEvent.VK_F6),
            SpecialKey("F7",  KeyEvent.VK_F7),
            SpecialKey("F8",  KeyEvent.VK_F8),
            SpecialKey("F9",  KeyEvent.VK_F9),
            SpecialKey("F10", KeyEvent.VK_F10),
            SpecialKey("F11", KeyEvent.VK_F11),
            SpecialKey("F12", KeyEvent.VK_F12),
        )

        // FlatLaf embeds this button inside the field's border via TEXT_FIELD_TRAILING_COMPONENT.
        val specialKeysBtn = JButton("+").apply {
            isFocusable = false
            cursor      = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Set a special key"
            putClientProperty("FlatLaf.style",
                "arc: 0; margin: 2,6,2,6; borderWidth: 0; innerFocusWidth: 0; " +
                "background: null")
        }

        val hintLabel = JLabel("").apply {
            font = font.deriveFont(font.size - 1f)
        }

        val okButton     = JButton(localizer.getString("common.ok"))
        val cancelButton = JButton(localizer.getString("common.cancel"))
        var confirmed = false
        okButton.addActionListener     { confirmed = true;  dialog.dispose() }
        cancelButton.addActionListener { dialog.dispose() }
        dialog.rootPane.defaultButton = okButton

        // ── helpers ──────────────────────────────────────────────────────────
        /** Formats a key combo as "Ctrl + Alt + T".  keyCode < 0 renders "?" for live preview. */
        fun formatCombo(mods: Int, keyCode: Int): String = buildList {
            if (mods and InputEvent.CTRL_DOWN_MASK  != 0) add("Ctrl")
            if (mods and InputEvent.ALT_DOWN_MASK   != 0) add("Alt")
            if (mods and InputEvent.SHIFT_DOWN_MASK != 0) add("Shift")
            if (mods and InputEvent.META_DOWN_MASK  != 0) add("⌘")
            add(if (keyCode < 0) "?" else KeyEvent.getKeyText(keyCode))
        }.joinToString(" + ")

        // ── updateUI ─────────────────────────────────────────────────────────
        // The input field always shows plain normal text — only hintLabel changes colour.
        fun updateUI() {
            when {
                isErrorState -> {
                    inputField.font = captureFont
                    inputField.text = KeyEvent.getKeyText(errorKeyCode)
                    hintLabel.text       = "⚠  ${localizer.getString("settings_hotkeys.recorder_invalid_key")}"
                    hintLabel.foreground = errorFg
                    okButton.isEnabled   = false
                }
                isCaptureDone && capturedKeyCode != 0 -> {
                    inputField.font = captureFont
                    inputField.text = formatCombo(capturedModifiers, capturedKeyCode)
                    hintLabel.text       = localizer.getString("settings_hotkeys.recorder_hint_confirm")
                    hintLabel.foreground = successFg
                    okButton.isEnabled   = true
                }
                liveModifiers != 0 -> {
                    inputField.font = captureFont
                    inputField.text = formatCombo(liveModifiers, -1)
                    hintLabel.text       = localizer.getString("settings_hotkeys.recorder_modifier_hint")
                    hintLabel.foreground = mutedFg
                    okButton.isEnabled   = false
                }
                else -> {
                    if (current?.hasBinding == true) {
                        inputField.font = captureFont
                        inputField.text = formatCombo(current.modifiers, current.keyCode)
                    } else {
                        inputField.font = placeholderFont
                        inputField.text = localizer.getString("settings_hotkeys.recorder_waiting")
                    }
                    hintLabel.text       = localizer.getString("settings_hotkeys.recorder_hint")
                    hintLabel.foreground = mutedFg
                    okButton.isEnabled   = current?.hasBinding == true
                }
            }
        }

        // Embed the "+" button inside the field border — FlatLaf trailing component.
        inputField.putClientProperty(
            FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, specialKeysBtn
        )

        // ── special-key popup ─────────────────────────────────────────────────
        specialKeysBtn.addActionListener {
            val menu = JPopupMenu()
            for (sk in specialKeys) {
                if (sk == null) { menu.addSeparator(); continue }
                menu.add(JMenuItem("Set ${sk.name}").apply {
                    addActionListener {
                        capturedKeyCode   = sk.keyCode
                        capturedModifiers = sk.mods
                        liveModifiers     = 0
                        isCaptureDone     = true
                        isErrorState      = false
                        updateUI()
                        inputField.requestFocusInWindow()
                    }
                })
            }
            menu.show(specialKeysBtn, 0, specialKeysBtn.height)
        }

        // ── key handling ─────────────────────────────────────────────────────
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                e.consume()
                isErrorState = false

                val resolvedKeyCode = when {
                    e.keyCode != KeyEvent.VK_UNDEFINED -> e.keyCode
                    e.extendedKeyCode != KeyEvent.VK_UNDEFINED -> e.extendedKeyCode
                    e.keyChar != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(e.keyChar) ->
                        KeyEvent.getExtendedKeyCodeForChar(e.keyChar.code)
                    else -> KeyEvent.VK_UNDEFINED
                }

                when {
                    e.keyCode == KeyEvent.VK_ESCAPE -> {
                        if (isCaptureDone) {
                            // First Esc: clear captured combo, return to waiting state
                            isCaptureDone     = false
                            capturedKeyCode   = 0
                            capturedModifiers = 0
                            liveModifiers     = 0
                            updateUI()
                        } else {
                            // Second Esc (or Esc from waiting): close dialog
                            dialog.dispose()
                        }
                        return
                    }

                    e.keyCode in MODIFIER_KEYS -> {
                        // Only modifier held — update live preview, don't capture yet
                        liveModifiers = e.modifiersEx
                        updateUI()
                        return
                    }

                    resolvedKeyCode in UNUSABLE_MAIN_KEYS -> {
                        // Rejected key — show in red, disable OK
                        errorKeyCode       = resolvedKeyCode
                        isErrorState       = true
                        okButton.isEnabled = false
                        updateUI()
                        return
                    }

                    else -> {
                        // Valid main key — capture the full combination
                        capturedKeyCode   = resolvedKeyCode
                        capturedModifiers = e.modifiersEx
                        liveModifiers     = 0
                        isCaptureDone     = true
                        updateUI()
                    }
                }
            }

            override fun keyReleased(e: KeyEvent) {
                e.consume()
                // Update live modifier display only while no main key is captured
                if (!isCaptureDone && e.keyCode in MODIFIER_KEYS) {
                    liveModifiers = e.modifiersEx
                    if (!isErrorState) updateUI()
                }
            }

            override fun keyTyped(e: KeyEvent) { e.consume() }
        })

        // ── layout ───────────────────────────────────────────────────────────
        updateUI()

        // IntelliJ layout: muted action name → field (with embedded "+") → hint, left-aligned.
        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(14, 14, 6, 14)
            isOpaque = false
            actionLabel.alignmentX = Component.LEFT_ALIGNMENT
            inputField.alignmentX  = Component.LEFT_ALIGNMENT
            hintLabel.alignmentX   = Component.LEFT_ALIGNMENT
            add(actionLabel)
            add(Box.createVerticalStrut(8))
            add(inputField)
            add(Box.createVerticalStrut(6))
            add(hintLabel)
        }

        dialog.contentPane.add(mainPanel, BorderLayout.CENTER)
        dialog.contentPane.add(JPanel(FlowLayout(FlowLayout.TRAILING, 8, 8)).apply {
            add(cancelButton); add(okButton)
        }, BorderLayout.SOUTH)

        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) { inputField.requestFocusInWindow() }
        })

        dialog.pack()
        dialog.minimumSize = Dimension(360, dialog.preferredSize.height)
        dialog.setLocationRelativeTo(owner)

        // Disable global hotkeys while the dialog is open so a shortcut being recorded
        // (e.g. Ctrl+D for dictionary) doesn't also fire its currently assigned action.
        pauseGlobalHotkeys?.invoke()
        dialog.isVisible = true   // ← blocks on EDT until the dialog is closed
        resumeGlobalHotkeys?.invoke()

        if (!confirmed) return null

        return HotkeyBinding(
            action    = action,
            keyCode   = capturedKeyCode,
            modifiers = capturedModifiers,
            isEnabled = current?.isEnabled ?: true,
            scope     = current?.scope ?: HotkeyScope.GLOBAL
        )
    }
}
