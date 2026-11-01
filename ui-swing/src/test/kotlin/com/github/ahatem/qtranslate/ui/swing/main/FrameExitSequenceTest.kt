package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The frame's exit convergence order, without a real window, hotkey backend or tray.
 *
 * The order is the contract: locally-owned resources stop synchronously so no new global
 * hotkey or tray event can start after the teardown begins; the final bounds are read from
 * the still-live frame after that; and only then does the application-level shutdown owner
 * (app's ApplicationShutdown, which persists bounds, stops the stores and exits the
 * process) take over. Each step runs exactly once per exit.
 */
class FrameExitSequenceTest {

    @Test
    fun `local resources stop in order before the exit is reported`() {
        val steps = mutableListOf<String>()
        var reportedSize: Size? = null
        var reportedPosition: Position? = null

        FrameExitSequence(
            shutdownGlobalInput = { steps += "global-input-shutdown" },
            disposeSelectionButton = { steps += "selection-button-dispose" },
            disposeTray = { steps += "tray-dispose" },
            cancelFrameScope = { steps += "frame-scope-cancel" },
            captureBounds = {
                steps += "bounds-capture"
                Size(1234, 768) to Position(-50, 120)
            },
            onApplicationExit = { size, position ->
                steps += "exit-report"
                reportedSize = size
                reportedPosition = position
            },
        ).exit()

        assertEquals(
            listOf(
                "global-input-shutdown",
                "selection-button-dispose",
                "tray-dispose",
                "frame-scope-cancel",
                "bounds-capture",
                "exit-report",
            ),
            steps,
            "the frame stops what it owns, captures the final bounds, then reports the exit",
        )
        assertEquals(Size(1234, 768), reportedSize)
        assertEquals(Position(-50, 120), reportedPosition)
    }

    @Test
    fun `a duplicate exit does not repeat the teardown`() {
        var hotkeys = 0
        var button = 0
        var tray = 0
        var scope = 0
        var bounds = 0
        var reports = 0

        val sequence = FrameExitSequence(
            shutdownGlobalInput = { hotkeys++ },
            disposeSelectionButton = { button++ },
            disposeTray = { tray++ },
            cancelFrameScope = { scope++ },
            captureBounds = { bounds++; Size(1, 1) to Position(0, 0) },
            onApplicationExit = { _, _ -> reports++ },
        )
        sequence.exit()
        sequence.exit()

        assertEquals(1, hotkeys, "the global input backend shuts down once")
        assertEquals(1, button, "the selection button is disposed once")
        assertEquals(1, tray, "the tray icon is removed once")
        assertEquals(1, scope, "the frame scope is cancelled once")
        assertEquals(1, bounds, "the final bounds are captured once")
        assertEquals(1, reports, "the exit is reported once — the store shutdown and process exit it triggers must not repeat")
    }
}
