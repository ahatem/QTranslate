package com.github.ahatem.qtranslate.core.settings.data

/**
 * Where a [HotkeyAction] command can be invoked from.
 *
 * GLOBAL: dispatches system-wide through the global input backend (requires the
 * global-hotkey switch, see [CommandAvailability]).
 * MAIN_WINDOW: dispatches through the window's local InputMap while QTranslate has focus.
 * CAPTURE_OVERLAY: dispatches only on the screen-capture overlay's root pane.
 */
enum class CommandSurface {
    GLOBAL,
    MAIN_WINDOW,
    CAPTURE_OVERLAY,
}

/**
 * What must be true for a command to be available.
 *
 * ALWAYS_AVAILABLE: no extra gate beyond its surface being active.
 * REQUIRES_GLOBAL_ENABLED: only registered while the global-hotkey switch is on.
 * REQUIRES_CAPTURE_OPEN: only available while the capture overlay is open.
 */
enum class CommandAvailability {
    ALWAYS_AVAILABLE,
    REQUIRES_GLOBAL_ENABLED,
    REQUIRES_CAPTURE_OPEN,
}

/**
 * A lightweight, UI-agnostic projection of a [HotkeyAction]: identity, display
 * metadata, the default binding, and which scopes/surfaces the command may use.
 *
 * This is descriptive metadata only. It does not dispatch anything, register
 * global keys, or own execution policy — those stay with the existing owners
 * (panel, global key listener, local InputMap). A spec's [defaultBinding] is
 * always equal to the matching row of [HotkeyBinding.DEFAULTS]; the registry
 * completeness test pins that.
 *
 * [nameKey] is the full localization key for the action's display name (for
 * example `"settings_hotkeys.action_quick_translate"`), resolved by the caller
 * through `LocalizationManager` — the spec never hard-codes user-visible text,
 * and [description] is a short English fallback for tests and diagnostics.
 */
data class CommandSpec(
    val action: HotkeyAction,
    val nameKey: String,
    val description: String,
    val category: String,
    val surfaces: Set<CommandSurface>,
    val defaultBinding: HotkeyBinding,
    val eligibleScopes: Set<HotkeyScope>,
    val availability: CommandAvailability,
    /** Whether the settings keyboard panel offers a row for this action. */
    val editable: Boolean,
) {
    val hasDefaultBinding: Boolean get() = defaultBinding.hasBinding
    val defaultScope: HotkeyScope get() = defaultBinding.scope
}

/**
 * The full command registry: exactly one [CommandSpec] per [HotkeyAction].
 *
 * Adding an action to [HotkeyAction] requires adding its spec here — the core
 * registry completeness test fails otherwise. Order of [SPECS] is the stable
 * panel order (SHOW_MAIN_WINDOW excepted: it is edited through a dedicated row
 * above the settings table, so it is `editable = false`).
 */
object CommandRegistry {

    private val ALL_SCOPES = setOf(HotkeyScope.GLOBAL, HotkeyScope.LOCAL)

    val SPECS: List<CommandSpec> = listOf(
        CommandSpec(
            action = HotkeyAction.SHOW_MAIN_WINDOW,
            nameKey = "settings_hotkeys.action_show_main",
            description = "Show the main window",
            category = "window",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.SHOW_MAIN_WINDOW,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.GLOBAL, isDoubleCtrlEnabled = true
            ),
            eligibleScopes = setOf(HotkeyScope.GLOBAL),
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = false,
        ),
        CommandSpec(
            action = HotkeyAction.SHOW_QUICK_TRANSLATE,
            nameKey = "settings_hotkeys.action_quick_translate",
            description = "Show the quick translate window",
            category = "translation",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.SHOW_QUICK_TRANSLATE,
                keyCode = java.awt.event.KeyEvent.VK_Q,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,
                scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.LISTEN_TO_TEXT,
            nameKey = "settings_hotkeys.action_listen",
            description = "Listen to selected text and translate it",
            category = "translation",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.LISTEN_TO_TEXT,
                keyCode = java.awt.event.KeyEvent.VK_E,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,
                scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OPEN_OCR,
            nameKey = "settings_hotkeys.action_ocr",
            description = "Open OCR on the selected screen area",
            category = "ocr",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OPEN_OCR,
                keyCode = java.awt.event.KeyEvent.VK_I,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,
                scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_SHOW_ACTIONS,
            nameKey = "settings_hotkeys.action_ocr_show_actions",
            description = "Open OCR and show its actions",
            category = "ocr",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_SHOW_ACTIONS,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_TRANSLATE,
            nameKey = "settings_hotkeys.action_ocr_translate",
            description = "Open OCR and translate the selection",
            category = "ocr",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_TRANSLATE,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_TRANSLATE_QUICK,
            nameKey = "settings_hotkeys.action_ocr_translate_quick",
            description = "Open OCR and open the quick translate workflow",
            category = "ocr",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_TRANSLATE_QUICK,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_TRANSLATE_MAIN,
            nameKey = "settings_hotkeys.action_ocr_translate_main",
            description = "Open OCR and open the main translate workflow",
            category = "ocr",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_TRANSLATE_MAIN,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_OPEN_WORKSPACE,
            nameKey = "settings_hotkeys.action_ocr_workspace",
            description = "Open OCR and open its workspace",
            category = "ocr",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_OPEN_WORKSPACE,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_COPY_TEXT,
            nameKey = "settings_hotkeys.action_ocr_copy_text",
            description = "Open OCR and copy the recognized text",
            category = "ocr",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_COPY_TEXT,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_COPY_IMAGE,
            nameKey = "settings_hotkeys.action_ocr_copy_image",
            description = "Open OCR and copy the captured image",
            category = "ocr",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_COPY_IMAGE,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.REPLACE_WITH_TRANSLATION,
            nameKey = "settings_hotkeys.action_replace",
            description = "Translate the selection and replace it with the translation",
            category = "translation",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.REPLACE_WITH_TRANSLATION,
                keyCode = java.awt.event.KeyEvent.VK_T,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.CYCLE_TARGET_LANGUAGE,
            nameKey = "settings_hotkeys.action_cycle_language",
            description = "Cycle the target language",
            category = "translation",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.CYCLE_TARGET_LANGUAGE,
                keyCode = java.awt.event.KeyEvent.VK_L,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.SHOW_DICTIONARY,
            nameKey = "settings_hotkeys.action_show_dictionary",
            description = "Show the dictionary popup for the selection",
            category = "dictionary",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.SHOW_DICTIONARY,
                keyCode = java.awt.event.KeyEvent.VK_D,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,
                scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.SHOW_IMAGES,
            nameKey = "settings_hotkeys.action_show_images",
            description = "Show the image popup for the selection",
            category = "dictionary",
            surfaces = setOf(CommandSurface.GLOBAL),
            defaultBinding = HotkeyBinding(
                HotkeyAction.SHOW_IMAGES,
                keyCode = java.awt.event.KeyEvent.VK_Q,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                scope = HotkeyScope.GLOBAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.REQUIRES_GLOBAL_ENABLED,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.TRANSLATE,
            nameKey = "settings_hotkeys.action_translate",
            description = "Trigger translation",
            category = "translation",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.TRANSLATE,
                keyCode = java.awt.event.KeyEvent.VK_ENTER,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.FOCUS_INPUT,
            nameKey = "settings_hotkeys.action_focus_input",
            description = "Move focus to the input text pane",
            category = "navigation",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.FOCUS_INPUT,
                keyCode = java.awt.event.KeyEvent.VK_1,
                modifiers = java.awt.event.InputEvent.ALT_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.FOCUS_OUTPUT,
            nameKey = "settings_hotkeys.action_focus_output",
            description = "Move focus to the output text pane",
            category = "navigation",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.FOCUS_OUTPUT,
                keyCode = java.awt.event.KeyEvent.VK_2,
                modifiers = java.awt.event.InputEvent.ALT_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.FOCUS_EXTRA_OUTPUT,
            nameKey = "settings_hotkeys.action_focus_extra_output",
            description = "Move focus to the extra-output pane",
            category = "navigation",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.FOCUS_EXTRA_OUTPUT,
                keyCode = java.awt.event.KeyEvent.VK_3,
                modifiers = java.awt.event.InputEvent.ALT_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.COPY_TRANSLATION,
            nameKey = "settings_hotkeys.action_copy_translation",
            description = "Copy the translated text",
            category = "translation",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.COPY_TRANSLATION,
                keyCode = java.awt.event.KeyEvent.VK_C,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.CLEAR_INPUT,
            nameKey = "settings_hotkeys.action_clear_input",
            description = "Clear the input pane",
            category = "editing",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.CLEAR_INPUT,
                keyCode = java.awt.event.KeyEvent.VK_X,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.SWAP_LANGUAGES,
            nameKey = "settings_hotkeys.action_swap_languages",
            description = "Swap source and target languages",
            category = "editing",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.SWAP_LANGUAGES,
                keyCode = java.awt.event.KeyEvent.VK_S,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OPEN_SETTINGS,
            nameKey = "settings_hotkeys.action_open_settings",
            description = "Open the settings dialog",
            category = "window",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OPEN_SETTINGS,
                keyCode = java.awt.event.KeyEvent.VK_COMMA,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.SHOW_HISTORY,
            nameKey = "settings_hotkeys.action_show_history",
            description = "Open the translation history dialog",
            category = "window",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.SHOW_HISTORY,
                keyCode = java.awt.event.KeyEvent.VK_H,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.TRANSLATE_DOCUMENT,
            nameKey = "settings_hotkeys.action_translate_document",
            description = "Open the document translation dialog",
            category = "document",
            surfaces = setOf(CommandSurface.MAIN_WINDOW),
            defaultBinding = HotkeyBinding(
                HotkeyAction.TRANSLATE_DOCUMENT,
                keyCode = java.awt.event.KeyEvent.VK_D,
                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                scope = HotkeyScope.LOCAL,
            ),
            eligibleScopes = ALL_SCOPES,
            availability = CommandAvailability.ALWAYS_AVAILABLE,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_ACTION_TRANSLATE,
            nameKey = "settings_hotkeys.action_capture_translate",
            description = "Translate the captured selection",
            category = "capture",
            surfaces = setOf(CommandSurface.CAPTURE_OVERLAY),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_ACTION_TRANSLATE,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.CAPTURE,
            ),
            eligibleScopes = setOf(HotkeyScope.CAPTURE),
            availability = CommandAvailability.REQUIRES_CAPTURE_OPEN,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_ACTION_COPY_TEXT,
            nameKey = "settings_hotkeys.action_capture_copy_text",
            description = "Copy the recognized text from the capture",
            category = "capture",
            surfaces = setOf(CommandSurface.CAPTURE_OVERLAY),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_ACTION_COPY_TEXT,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.CAPTURE,
            ),
            eligibleScopes = setOf(HotkeyScope.CAPTURE),
            availability = CommandAvailability.REQUIRES_CAPTURE_OPEN,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_ACTION_COPY_IMAGE,
            nameKey = "settings_hotkeys.action_capture_copy_image",
            description = "Copy the captured image",
            category = "capture",
            surfaces = setOf(CommandSurface.CAPTURE_OVERLAY),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_ACTION_COPY_IMAGE,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.CAPTURE,
            ),
            eligibleScopes = setOf(HotkeyScope.CAPTURE),
            availability = CommandAvailability.REQUIRES_CAPTURE_OPEN,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_ACTION_SAVE_IMAGE,
            nameKey = "settings_hotkeys.action_capture_save_image",
            description = "Save the captured image",
            category = "capture",
            surfaces = setOf(CommandSurface.CAPTURE_OVERLAY),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_ACTION_SAVE_IMAGE,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.CAPTURE,
            ),
            eligibleScopes = setOf(HotkeyScope.CAPTURE),
            availability = CommandAvailability.REQUIRES_CAPTURE_OPEN,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_ACTION_RECROP,
            nameKey = "settings_hotkeys.action_capture_recrop",
            description = "Re-crop the captured area",
            category = "capture",
            surfaces = setOf(CommandSurface.CAPTURE_OVERLAY),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_ACTION_RECROP,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.CAPTURE,
            ),
            eligibleScopes = setOf(HotkeyScope.CAPTURE),
            availability = CommandAvailability.REQUIRES_CAPTURE_OPEN,
            editable = true,
        ),
        CommandSpec(
            action = HotkeyAction.OCR_ACTION_CANCEL,
            nameKey = "settings_hotkeys.action_capture_cancel",
            description = "Cancel the capture",
            category = "capture",
            surfaces = setOf(CommandSurface.CAPTURE_OVERLAY),
            defaultBinding = HotkeyBinding(
                HotkeyAction.OCR_ACTION_CANCEL,
                keyCode = 0, modifiers = 0, scope = HotkeyScope.CAPTURE,
            ),
            eligibleScopes = setOf(HotkeyScope.CAPTURE),
            availability = CommandAvailability.REQUIRES_CAPTURE_OPEN,
            editable = true,
        ),
    )

    private val byAction: Map<HotkeyAction, CommandSpec> = SPECS.associateBy { it.action }

    fun specFor(action: HotkeyAction): CommandSpec? = byAction[action]

    /** Specs in registry (panel) order, optionally restricted to one surface. */
    fun specsForSurface(surface: CommandSurface): List<CommandSpec> =
        SPECS.filter { surface in it.surfaces }

    /** Actions with a row in the settings keyboard panel, in row order. */
    val editableActions: List<HotkeyAction> get() = SPECS.filter { it.editable }.map { it.action }
}

/**
 * A minimal description of two or more enabled bindings that contend for the
 * same key combination.
 *
 * DUPLICATE_GLOBAL / DUPLICATE_LOCAL / DUPLICATE_CAPTURE: two or more enabled
 * bindings in the same scope share (keyCode, modifiers).
 * LOCAL_GLOBAL_COLLISION: the same combination is bound both globally and
 * locally — the global registration wins while the app has focus, so the
 * local one is ambiguous at best.
 */
enum class HotkeyConflictKind {
    DUPLICATE_GLOBAL,
    DUPLICATE_LOCAL,
    DUPLICATE_CAPTURE,
    LOCAL_GLOBAL_COLLISION,
}

data class HotkeyConflict(
    val kind: HotkeyConflictKind,
    val keyCode: Int,
    val modifiers: Int,
    val actions: Set<HotkeyAction>,
)

/**
 * Minimal duplicate/collision detection over enabled, bound bindings.
 * Disabled and unbound (keyCode = 0) rows never conflict. CAPTURE bindings
 * only collide among themselves — capture keys are neither global nor
 * main-window local.
 */
object HotkeyConflictDetector {

    fun findConflicts(bindings: List<HotkeyBinding>): List<HotkeyConflict> {
        val active = bindings.filter { it.isEnabled && it.hasBinding }
        val conflicts = mutableListOf<HotkeyConflict>()

        // Same-scope duplicates.
        for (scope in HotkeyScope.entries) {
            active.filter { it.scope == scope }
                .groupBy { it.keyCode to it.modifiers }
                .entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .forEach { (combo, group) ->
                    if (group.size > 1) {
                        conflicts += HotkeyConflict(
                            kind = when (scope) {
                                HotkeyScope.GLOBAL -> HotkeyConflictKind.DUPLICATE_GLOBAL
                                HotkeyScope.LOCAL -> HotkeyConflictKind.DUPLICATE_LOCAL
                                HotkeyScope.CAPTURE -> HotkeyConflictKind.DUPLICATE_CAPTURE
                            },
                            keyCode = combo.first,
                            modifiers = combo.second,
                            actions = group.map { it.action }.toSet(),
                        )
                    }
                }
        }

        // Local/global collisions.
        val globalCombos = active.filter { it.scope == HotkeyScope.GLOBAL }
        val localCombos = active.filter { it.scope == HotkeyScope.LOCAL }
        (globalCombos + localCombos)
            .groupBy { it.keyCode to it.modifiers }
            .entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .forEach { (combo, group) ->
                val scopes = group.map { it.scope }.toSet()
                if (HotkeyScope.GLOBAL in scopes && HotkeyScope.LOCAL in scopes) {
                    conflicts += HotkeyConflict(
                        kind = HotkeyConflictKind.LOCAL_GLOBAL_COLLISION,
                        keyCode = combo.first,
                        modifiers = combo.second,
                        actions = group.map { it.action }.toSet(),
                    )
                }
            }

        return conflicts
    }

    /** Conflicts involving [action] among [bindings]. */
    fun conflictsFor(action: HotkeyAction, bindings: List<HotkeyBinding>): List<HotkeyConflict> =
        findConflicts(bindings).filter { action in it.actions }
}
