package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.MinimizeButtonBehavior

/** What the native Minimize button should mean, resolved for one iconify event. */
internal enum class MinimizeButtonAction {
    HIDE_TO_TRAY,
    MINIMIZE_TO_TASKBAR,
    // Hidden iconify events belong to intentional hide flows such as screen capture.
    IGNORE
}

/** Pure decision behind MainAppFrame's windowIconified override, kept free of Swing. */
internal object MinimizeButtonPolicy {
    fun decide(
        behavior: MinimizeButtonBehavior,
        windowCurrentlyVisible: Boolean
    ): MinimizeButtonAction {
        if (!windowCurrentlyVisible) return MinimizeButtonAction.IGNORE
        return when (behavior) {
            MinimizeButtonBehavior.HIDE_TO_TRAY -> MinimizeButtonAction.HIDE_TO_TRAY
            MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR -> MinimizeButtonAction.MINIMIZE_TO_TASKBAR
        }
    }
}
