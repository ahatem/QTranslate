package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Window position/bounds ownership, exercised without a real window.
 *
 * Restore is the risky half: a saved position outlives the desktop it was saved on, and the
 * wrong answer is a window that runs somewhere invisible. Persist is the boring half, pinned
 * anyway so a future simplification cannot split the size and position writes.
 */
class WindowPositionBindingTest {

    private val primary = Rectangle(0, 0, 1920, 1080)
    private val leftOfPrimary = Rectangle(-1920, 0, 1920, 1080)

    private val infoMessages = mutableListOf<String>()
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) { infoMessages += message }
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun binding(dispatched: MutableList<SettingsIntent> = mutableListOf()) =
        WindowPositionBinding(dispatchSettings = { dispatched += it }, logger = logger)

    @Test
    fun `a first launch with nothing saved centres the window`() {
        assertEquals(
            WindowRestorePlacement.Centered,
            binding().restorePlacement(null, 800, 600, listOf(primary)),
        )
    }

    @Test
    fun `a saved position on a connected display restores exactly as saved`() {
        val saved = Position(120, 80)
        assertEquals(
            WindowRestorePlacement.At(saved),
            binding().restorePlacement(saved, 800, 600, listOf(primary)),
        )
    }

    @Test
    fun `a negative position restores when its display is still attached`() {
        val saved = Position(-1500, 200)
        assertEquals(
            WindowRestorePlacement.At(saved),
            binding().restorePlacement(saved, 800, 600, listOf(primary, leftOfPrimary)),
        )
    }

    @Test
    fun `a position whose display is gone centres instead and says so`() {
        val placement = binding().restorePlacement(Position(-1500, 200), 800, 600, listOf(primary))

        assertEquals(WindowRestorePlacement.Centered, placement)
        assertEquals(1, infoMessages.size, "the centring fallback must be logged once")
        val message = infoMessages.single()
        assertTrue(message.contains("(-1500, 200)"), "the log should name the dead position: $message")
        assertTrue(message.contains("centring"), "the log should say what was done instead: $message")
    }

    @Test
    fun `a window deliberately pushed half off the edge is left where the user put it`() {
        // Reachability is deliberately weaker than full visibility: 400px of an 800px window.
        assertEquals(
            WindowRestorePlacement.At(Position(1520, 400)),
            binding().restorePlacement(Position(1520, 400), 800, 600, listOf(primary)),
        )
    }

    @Test
    fun `an unreadable display list trusts the saved position instead of second-guessing it`() {
        val saved = Position(-9000, -9000)
        assertEquals(
            WindowRestorePlacement.At(saved),
            binding().restorePlacement(saved, 800, 600, emptyList()),
        )
    }

    @Test
    fun `reachability is judged on the window the restore would create, not on the point alone`() {
        // On-screen point, but only a 2px sliver of the window overlaps the display: too small
        // to see and grab, so the position is not reachable.
        assertEquals(
            WindowRestorePlacement.Centered,
            binding().restorePlacement(Position(1918, 400), 800, 600, listOf(primary)),
        )
    }

    @Test
    fun `persisting bounds writes size and position in one scoped settings update`() {
        val dispatched = mutableListOf<SettingsIntent>()

        binding(dispatched).persistBounds(1234, 768, -50, 120)

        val intent = dispatched.single() as SettingsIntent.ToggleSetting
        val updated = intent.update(Configuration.DEFAULT)
        assertEquals(Size(1234, 768), updated.mainWindowSize)
        assertEquals(Position(-50, 120), updated.mainWindowPosition)
    }
}
