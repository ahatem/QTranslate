package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.github.ahatem.qtranslate.ui.swing.main.widgets.Action
import com.github.ahatem.qtranslate.ui.swing.main.widgets.ActionPriority
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

/**
 * The language bar's Translate button as an action for a strip of icon buttons, for when the bar
 * is not drawn.
 *
 * It follows the bar's button exactly: Translate when idle, Cancel while a translation runs
 * (and then always pressable, so a running translation can be stopped), disabled only when there
 * is nothing to translate. The enabled rule and the words come from [LanguageSelectionBarState],
 * and it calls the same [onTranslate] and [onCancel] the bar is given.
 */
fun LanguageSelectionBarState.translateAction(onTranslate: () -> Unit, onCancel: () -> Unit): Action =
    if (isLoading) Action(
        id = CANCEL_ACTION_ID,
        iconPath = Icons.CLOSE,
        tooltip = strings.cancelButtonText,
        isEnabled = isTranslateActionable,
        isVisible = true,
        onClick = onCancel,
        keepPriority = ActionPriority.TRANSLATE,
    ) else Action(
        id = TRANSLATE_ACTION_ID,
        iconPath = Icons.TRANSLATE,
        tooltip = strings.translateButtonText,
        isEnabled = isTranslateActionable,
        isVisible = true,
        onClick = onTranslate,
        keepPriority = ActionPriority.TRANSLATE,
    )

/**
 * The language bar's Clear button as an action for a strip of icon buttons, for when the bar is not
 * drawn. Same icon, words and enabled rule as the bar's button, and the same [onClear].
 */
fun LanguageSelectionBarState.clearAction(onClear: () -> Unit): Action = Action(
    id = CLEAR_ACTION_ID,
    iconPath = Icons.DELETE,
    tooltip = strings.clearTooltip,
    isEnabled = isClearActionable,
    isVisible = true,
    onClick = onClear,
    keepPriority = ActionPriority.CLEAR,
)

const val TRANSLATE_ACTION_ID = "input_translate"
const val CANCEL_ACTION_ID = "input_cancel"
const val CLEAR_ACTION_ID = "input_clear"
