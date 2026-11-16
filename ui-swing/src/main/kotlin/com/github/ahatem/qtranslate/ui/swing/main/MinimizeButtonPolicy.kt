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
    /**
     * Resolves one minimize event.
     *
     * @param trayAvailable whether a tray icon is actually installed. Hiding the window is only
     *   recoverable through that icon, so with none installed "hide to tray" would make the window
     *   disappear from every surface the user can see; it minimizes to the taskbar instead.
     */
    fun decide(
        behavior: MinimizeButtonBehavior,
        windowCurrentlyVisible: Boolean,
        trayAvailable: Boolean = true,
    ): MinimizeButtonAction {
        if (!windowCurrentlyVisible) return MinimizeButtonAction.IGNORE
        return when (behavior) {
            MinimizeButtonBehavior.HIDE_TO_TRAY ->
                if (trayAvailable) MinimizeButtonAction.HIDE_TO_TRAY
                else MinimizeButtonAction.MINIMIZE_TO_TASKBAR
            MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR -> MinimizeButtonAction.MINIMIZE_TO_TASKBAR
        }
    }
}
