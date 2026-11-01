package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size

/**
 * The frame's half of every exit route: stops what only the frame owns, then reports the
 * exit to the application-level shutdown owner.
 *
 * Every exit route — the main window's EXIT close behavior, tray Exit, and the close
 * dialog's Exit — disposes the frame, which fires `windowClosed` exactly once, which runs
 * this sequence. The steps run synchronously and in order: stopping the locally-owned
 * resources first means no new global hotkey or tray event can start after the teardown
 * begins, and the application-level owner can safely take over from there.
 *
 * The final bounds are read from the still-live frame *after* the local teardown, not via
 * the settings-bound bounds save, because by the time application shutdown finishes off
 * the EDT the frame may already be disposed. Application-level teardown — settings
 * persistence, main store, plugin manager, host HTTP client, app scope and process exit —
 * belongs to `app`'s ApplicationShutdown; this class only hands it the final bounds
 * through [onApplicationExit], preserving its step ordering.
 *
 * Each step is an injected callback so the order contract can be tested without
 * constructing the real window, hotkey backend or tray.
 */
internal class FrameExitSequence(
    private val shutdownGlobalInput: () -> Unit,
    private val disposeSelectionButton: () -> Unit,
    private val disposeTray: () -> Unit,
    private val cancelFrameScope: () -> Unit,
    private val captureBounds: () -> Pair<Size, Position>,
    private val onApplicationExit: (Size, Position) -> Unit,
) {
    fun exit() {
        shutdownGlobalInput()
        disposeSelectionButton()
        disposeTray()
        cancelFrameScope()
        val (size, position) = captureBounds()
        onApplicationExit(size, position)
    }
}
