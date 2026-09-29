package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.SelectionBehavior

/** Small, deterministic decision point after a selection has been captured. */
internal enum class SelectionAction {
    NONE,
    SHOW_ICON,
    TRANSLATE,
    TRANSLATE_AND_READ
}

internal object SelectionBehaviorRouter {
    fun decide(behavior: SelectionBehavior, qTranslateWindowActive: Boolean): SelectionAction {
        if (qTranslateWindowActive) return SelectionAction.NONE
        return when (behavior) {
            SelectionBehavior.OFF -> SelectionAction.NONE
            SelectionBehavior.SHOW_ICON -> SelectionAction.SHOW_ICON
            SelectionBehavior.TRANSLATE -> SelectionAction.TRANSLATE
            SelectionBehavior.TRANSLATE_AND_READ -> SelectionAction.TRANSLATE_AND_READ
        }
    }
}
