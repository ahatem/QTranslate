package com.github.ahatem.qtranslate.ui.swing.shared.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Follow-the-OS behavior of the theme watcher: who polls, at what cadence, and when a system flip
 * re-applies the theme. A fixed theme must not poll at all (#323); OS-default must still follow the
 * system while the app runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemThemeWatcherTest {

    private val osDefault = ThemeManager.OS_DEFAULT_THEME_ID
    private val fixedTheme = "custom:qtranslate_dark"

    /**
     * Runs the watcher in the test's background scope and answers each detection request from
     * [modes] in order, repeating the last answer once the list runs out.
     */
    private class Harness(
        private val scope: CoroutineScope,
        private val modes: List<Boolean>
    ) {
        var detections = 0
            private set

        var applied = 0
            private set

        private fun detect(): Boolean {
            val mode = modes[detections.coerceAtMost(modes.lastIndex)]
            detections++
            return mode
        }

        fun start(themeIds: MutableStateFlow<String>) {
            val watcher = SystemThemeWatcher(
                isSystemInDarkMode = ::detect,
                pollIntervalMs = SystemThemeWatcher.DEFAULT_POLL_INTERVAL_MS
            )
            scope.launch { watcher.watch(themeIds) { applied++ } }
        }
    }

    @Test
    fun `a fixed theme never asks the system for its mode`() = runTest {
        val harness = Harness(backgroundScope, modes = listOf(false))
        harness.start(MutableStateFlow(fixedTheme))
        runCurrent()

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(0, harness.detections)
        assertEquals(0, harness.applied)
    }

    @Test
    fun `an os default theme polls once on entry and then every interval`() = runTest {
        val harness = Harness(backgroundScope, modes = listOf(true))
        harness.start(MutableStateFlow(osDefault))
        runCurrent()
        assertEquals(1, harness.detections)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(4, harness.detections)
        assertEquals(0, harness.applied)
    }

    @Test
    fun `a system flip re-applies the theme once per change`() = runTest {
        val harness = Harness(backgroundScope, modes = listOf(false, false, true, true, false))
        harness.start(MutableStateFlow(osDefault))
        runCurrent()

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, harness.applied)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, harness.applied)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, harness.applied, "an unchanged mode must not re-apply anything")

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, harness.applied)
    }

    @Test
    fun `leaving the os default theme stops polling`() = runTest {
        val theme = MutableStateFlow(osDefault)
        val harness = Harness(backgroundScope, modes = listOf(true))
        harness.start(theme)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, harness.detections)

        theme.value = fixedTheme
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(2, harness.detections)
    }

    @Test
    fun `switching to the os default theme polls from the mode in effect`() = runTest {
        val theme = MutableStateFlow(fixedTheme)
        val harness = Harness(backgroundScope, modes = listOf(true, true))
        harness.start(theme)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, harness.detections)

        theme.value = osDefault
        runCurrent()
        assertEquals(1, harness.detections, "the first read seeds the loop on entry")
        assertEquals(0, harness.applied, "entering the theme is the settings observer's job")

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, harness.detections)
        assertEquals(0, harness.applied)
    }
}
