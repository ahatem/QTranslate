package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.ui.swing.shared.util.isPositionReachable
import java.awt.Rectangle

/**
 * Where a window being restored should be placed, decided before anything is moved.
 *
 * [At] carries a saved position that still overlaps a connected display; [Centered] is the
 * fallback for a first launch (nothing saved) and for a saved position the user can no
 * longer reach.
 */
internal sealed interface WindowRestorePlacement {
    data class At(val position: Position) : WindowRestorePlacement
    data object Centered : WindowRestorePlacement
}

/**
 * Owns the main window's saved bounds: the capture written on close/iconify, and the
 * restore decision taken at startup.
 *
 * Persistence is a scoped settings update like every other toolbar/menu quick action, so
 * the owner is the settings store; this binding only decides *what* to write. Restore
 * decides *where* the window may go: a position saved on a display that is no longer
 * attached would produce a window that is running, focusable and entirely invisible,
 * which is indistinguishable from the application having failed to start, so an
 * unreachable position centres instead. The rule is deliberately permissive — a window
 * the user deliberately pushed half off the edge stays where they put it.
 *
 * Free of Swing window state on purpose: callers pass the numbers, so both halves can be
 * exercised without a real [java.awt.Window].
 */
internal class WindowPositionBinding(
    private val dispatchSettings: (SettingsIntent) -> Unit,
    private val logger: Logger,
) {
    /** Captures the window's current bounds into the working configuration. */
    fun persistBounds(width: Int, height: Int, x: Int, y: Int) {
        dispatchSettings(
            SettingsIntent.ToggleSetting {
                it.copy(
                    mainWindowSize = Size(width, height),
                    mainWindowPosition = Position(x, y)
                )
            }
        )
    }

    /**
     * Decides where to place the window at startup.
     *
     * @param saved the position persisted at last exit, or null on a first launch.
     * @param width the window's packed width — needed because reachability is judged on the
     *   rectangle the window would actually occupy, not on the point alone.
     * @param screens the connected displays; empty means they could not be read, in which
     *   case the saved position is trusted rather than second-guessed.
     */
    fun restorePlacement(
        saved: Position?,
        width: Int,
        height: Int,
        screens: List<Rectangle>,
    ): WindowRestorePlacement {
        if (saved == null) return WindowRestorePlacement.Centered
        if (isPositionReachable(Rectangle(saved.x, saved.y, width, height), screens)) {
            return WindowRestorePlacement.At(saved)
        }
        logger.info(
            "Saved window position (${saved.x}, ${saved.y}) is off every connected display; centring instead"
        )
        return WindowRestorePlacement.Centered
    }
}
