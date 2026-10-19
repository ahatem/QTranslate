package com.github.ahatem.qtranslate.ui.swing.shared.theme

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Re-applies the OS-preferred theme when the system switches between light and dark mode, but
 * only while the selected theme is [ThemeManager.OS_DEFAULT_THEME_ID].
 *
 * The selected theme arrives as a flow: leaving the OS-default theme cancels the polling loop
 * before its next tick, so a fixed theme never asks the system for its mode at all (#323), and
 * returning to it starts a fresh loop seeded with the mode in effect at that moment.
 *
 * Polling stays at [pollIntervalMs] because neither platform offers a change notification for
 * this preference without extra platform code, and on Windows a poll is now a registry read
 * instead of a child process.
 */
internal class SystemThemeWatcher(
    private val isSystemInDarkMode: () -> Boolean = { ThemeManager.isSystemInDarkMode() },
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
) {

    /**
     * Watches [themeIds] and calls [onSystemThemeChanged] whenever the system mode flips while
     * the current theme is the OS-default sentinel.
     *
     * Suspends until [themeIds] completes; the caller owns the scope, as with every other
     * application-lifetime collector.
     */
    suspend fun watch(themeIds: Flow<String>, onSystemThemeChanged: suspend () -> Unit) {
        themeIds
            .map { it == ThemeManager.OS_DEFAULT_THEME_ID }
            .distinctUntilChanged()
            .collectLatest { followsSystem ->
                if (!followsSystem) return@collectLatest
                var lastDarkMode = isSystemInDarkMode()
                while (true) {
                    delay(pollIntervalMs)
                    val isDarkMode = isSystemInDarkMode()
                    if (isDarkMode != lastDarkMode) {
                        lastDarkMode = isDarkMode
                        onSystemThemeChanged()
                    }
                }
            }
    }

    companion object {
        /** The cadence this watcher has always used. */
        const val DEFAULT_POLL_INTERVAL_MS = 10_000L
    }
}
