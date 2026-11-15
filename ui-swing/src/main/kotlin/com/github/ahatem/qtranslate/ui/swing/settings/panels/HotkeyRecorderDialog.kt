package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.CommandRegistry
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyConflictDetector
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.ui.swing.shared.util.formatChord
import com.github.ahatem.qtranslate.ui.swing.snippingtool.isReservedBareCaptureKey
import java.awt.*
import java.awt.event.*
import javax.swing.*

internal object HotkeyRecorderDialog {

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
        /**
         * The bindings already configured, so a combination that is taken can be named while
         * it is being recorded rather than only after the panel redraws.
         *
         * Advisory only: a conflicting combination is still saved, because a duplicate is a
         * decision the user is allowed to make.
         */
        existingBindings: List<HotkeyBinding> = emptyList(),
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
        var isReservedError   = false

        // ── dialog ──────────────────────────────────────────────────────────
        val dialog = JDialog(
            owner,
            localizer.getString("settings_hotkeys.recorder_title"),
            Dialog.ModalityType.APPLICATION_MODAL
        )
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.isResizable = false

        // The display name comes from the command registry; the fallback keeps an action that
        // somehow reaches here without a spec visible (as its enum name) instead of crashing.
        fun actionDisplayName(target: HotkeyAction): String =
            CommandRegistry.specFor(target)?.let { localizer.getString(it.nameKey) } ?: target.name
        val actionName = actionDisplayName(action)

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

        /**
         * Names what else already uses the combination that was just captured.
         *
         * Separate from [hintLabel] because it answers a different question — the hint says
         * "now confirm", this says "but this combination is already spoken for" — and it must
         * survive the hint returning to its idle text. Never blocks OK: two actions sharing a
         * combination is the user's call, and the settings table already reports it afterwards.
         */
        val warningFg = UIManager.getColor("Actions.Yellow") ?: errorFg
        val conflictRow = HotkeyConflictRow(warningFg, baseFont)

        val okButton     = JButton(localizer.getString("common.ok"))
        val cancelButton = JButton(localizer.getString("common.cancel"))
        var confirmed = false
        okButton.addActionListener     { confirmed = true;  dialog.dispose() }
        cancelButton.addActionListener { dialog.dispose() }
        dialog.rootPane.defaultButton = okButton

        // ── helpers ──────────────────────────────────────────────────────────
        /**
         * Formats the combination with the one shared formatter, so it reads exactly as the
         * settings table and the menu hints spell it.
         *
         * A negative [keyCode] is the live preview before a non-modifier key is pressed: the
         * modifiers so far, then a `?` standing in for the key still to come.
         */
        fun formatCombo(mods: Int, keyCode: Int): String {
            if (keyCode >= 0) return formatChord(keyCode, mods)
            val modifiers = formatChord(0, mods)
            return if (modifiers.isEmpty()) "?" else "$modifiers+?"
        }

        // ── updateUI ─────────────────────────────────────────────────────────
        // The input field always shows plain normal text — only hintLabel changes colour.
        /** Actions already bound to the captured combination, so the warning can name them. */
        fun conflictingNames(): List<String> =
            if (!isCaptureDone) emptyList() else conflictingActions(
                action = action,
                keyCode = capturedKeyCode,
                modifiers = capturedModifiers,
                current = current,
                bindings = existingBindings,
            ).map(::actionDisplayName)

        fun updateUI() {
            val conflictNames = conflictingNames()
            conflictRow.show(
                if (conflictNames.isEmpty()) null else {
                    "⚠  " + localizer.getString(
                        "settings_hotkeys.recorder_conflict",
                        conflictNames.joinToString(", "),
                    )
                }
            )

            when {
                isErrorState || isReservedError -> {
                    inputField.font = captureFont
                    inputField.text = KeyEvent.getKeyText(errorKeyCode)
                    hintLabel.text       = if (isReservedError) {
                        "⚠  ${localizer.getString("settings_hotkeys.recorder_reserved_capture_key")}"
                    } else {
                        "⚠  ${localizer.getString("settings_hotkeys.recorder_invalid_key")}"
                    }
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
                        if (isReservedBareCaptureKey(action, sk.keyCode, sk.mods)) {
                            errorKeyCode    = sk.keyCode
                            isErrorState    = false
                            isReservedError = true
                            isCaptureDone   = false
                            okButton.isEnabled = false
                        } else {
                            capturedKeyCode   = sk.keyCode
                            capturedModifiers = sk.mods
                            liveModifiers     = 0
                            isCaptureDone     = true
                            isErrorState      = false
                            isReservedError   = false
                        }
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
                isReservedError = false

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
                        if (isReservedBareCaptureKey(action, resolvedKeyCode, e.modifiersEx)) {
                            errorKeyCode    = resolvedKeyCode
                            isReservedError = true
                            isCaptureDone   = false
                            okButton.isEnabled = false
                        } else {
                            // Valid main key — capture the full combination
                            capturedKeyCode   = resolvedKeyCode
                            capturedModifiers = e.modifiersEx
                            liveModifiers     = 0
                            isCaptureDone     = true
                        }
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
            conflictRow.alignmentX = Component.LEFT_ALIGNMENT
            add(actionLabel)
            add(Box.createVerticalStrut(8))
            add(inputField)
            add(Box.createVerticalStrut(6))
            add(hintLabel)
            add(Box.createVerticalStrut(4))
            add(conflictRow)
        }
        // A hidden component takes no room in a BoxLayout, so the packed dialog reserved nothing
        // for the warning and the line was clipped mid-sentence the moment a combination
        // collided. The row reserves its height instead of being hidden; see [HotkeyConflictRow].
        // The dialog is then packed once and never resizes under the user.
        dialog.contentPane.add(mainPanel, BorderLayout.CENTER)
        dialog.contentPane.add(JPanel(FlowLayout(FlowLayout.TRAILING, 8, 8)).apply {
            add(cancelButton); add(okButton)
        }, BorderLayout.SOUTH)

        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) { inputField.requestFocusInWindow() }
        })

        dialog.pack()
        dialog.minimumSize = Dimension(UIScale.scale(360), dialog.preferredSize.height)
        dialog.setLocationRelativeTo(owner)

        // Disable global hotkeys while the dialog is open so a shortcut being recorded
        // (e.g. Ctrl+D for dictionary) doesn't also fire its currently assigned action.
        pauseGlobalHotkeys?.invoke()
        dialog.isVisible = true   // ← blocks on EDT until the dialog is closed
        resumeGlobalHotkeys?.invoke()

        if (!confirmed) return null

        return recordedBinding(action, current, capturedKeyCode, capturedModifiers)
    }

    /**
     * The actions that already hold the recorded combination.
     *
     * Pure so the warning can be tested without a display. The candidate is built exactly as
     * [recordedBinding] will build it — same scope, same enabled state — because a duplicate is
     * only a duplicate within one scope, and the scope is what makes the recorded binding
     * global, local or capture-local.
     *
     * [action] itself is excluded: rebinding an action to the combination it already has is not a
     * conflict with itself.
     */
    internal fun conflictingActions(
        action: HotkeyAction,
        keyCode: Int,
        modifiers: Int,
        current: HotkeyBinding?,
        bindings: List<HotkeyBinding>,
    ): Set<HotkeyAction> {
        if (keyCode == 0) return emptySet()
        val candidate = recordedBinding(action, current, keyCode, modifiers)
        val others = bindings.filterNot { it.action == action }
        return HotkeyConflictDetector.findConflicts(others + candidate)
            .flatMap { it.actions }
            .toSet() - action
    }

    /**
     * The binding a confirmed recording produces.
     *
     * The scope of the binding that was there before is kept: recording a key for an app-local
     * action must not quietly promote it to a global one, which the global input backend would
     * then register system-wide. An action that had no binding at all counts as global, the
     * same way the settings table reads it.
     */
    internal fun recordedBinding(
        action: HotkeyAction,
        current: HotkeyBinding?,
        keyCode: Int,
        modifiers: Int,
    ): HotkeyBinding = HotkeyBinding(
        action    = action,
        keyCode   = keyCode,
        modifiers = modifiers,
        isEnabled = current?.isEnabled ?: true,
        scope     = current?.scope ?: HotkeyScope.GLOBAL
    )
}
