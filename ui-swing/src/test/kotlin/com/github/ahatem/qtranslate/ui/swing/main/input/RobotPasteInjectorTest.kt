package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Robot copy and paste injectors share one key sequence (Ctrl plus a letter) and one cleanup
 * contract, so every case runs against both. Key release cleanup on failure is what keeps a
 * modifier from staying stuck down on the user's keyboard.
 */
class RobotPasteInjectorTest {

    private class Chord(val name: String, val key: Int, val inject: suspend (RobotKeyDriver) -> Boolean)

    private val chords = listOf(
        Chord("copy", 67) { driver ->
            RobotCopyInjector(RecordingLogger(), driverFactory = { driver }).injectCopy()
        },
        Chord("paste", 86) { driver ->
            RobotPasteInjector(RecordingLogger(), driverFactory = { driver }, clipboardWrite = {}).injectPaste("hello")
        },
    )

    @Test
    fun `robot creation exception returns false`() = runTest {
        val copyLogger = RecordingLogger()
        assertFalse(RobotCopyInjector(copyLogger, driverFactory = { throw RuntimeException("no display") }).injectCopy())
        assertTrue(copyLogger.warns.any { it.contains("Copy simulation failed", ignoreCase = true) })

        val pasteLogger = RecordingLogger()
        val paste = RobotPasteInjector(pasteLogger, driverFactory = { throw RuntimeException("no display") }, clipboardWrite = {})
        assertFalse(paste.injectPaste("hello"))
        assertTrue(pasteLogger.warns.any { it.contains("Robot paste failed", ignoreCase = true) })
    }

    @Test
    fun `clipboard is written even when robot creation fails`() = runTest {
        var written: String? = null
        val injector = RobotPasteInjector(
            RecordingLogger(),
            driverFactory = { throw RuntimeException("no display") },
            clipboardWrite = { written = it },
        )
        injector.injectPaste("secret-text")
        assertEquals("secret-text", written)
    }

    @Test
    fun `successful injection presses and releases in order`() = runTest {
        chords.forEach { chord ->
            val driver = FakeRobotKeyDriver()
            assertTrue(chord.inject(driver), chord.name)
            assertEquals(
                listOf("press:17", "press:${chord.key}", "release:${chord.key}", "release:17", "waitForIdle"),
                driver.calls,
                chord.name,
            )
        }
    }

    @Test
    fun `main key press exception returns false and releases the pressed modifier`() = runTest {
        // The modifier is already down, so it is released; the key was never pressed, so it is not.
        chords.forEach { chord ->
            val driver = FakeRobotKeyDriver(failOn = { it == "press:${chord.key}" })
            assertFalse(chord.inject(driver), chord.name)
            assertEquals(listOf("press:17", "press:${chord.key}", "release:17"), driver.calls, chord.name)
        }
    }

    @Test
    fun `modifier press exception never attempts a release`() = runTest {
        chords.forEach { chord ->
            val driver = FakeRobotKeyDriver(failOn = { it == "press:17" })
            assertFalse(chord.inject(driver), chord.name)
            assertFalse(driver.calls.any { it.startsWith("release") }, "${chord.name}: nothing was pressed, so nothing may be released")
        }
    }

    @Test
    fun `main key release exception still attempts cleanup of both keys`() = runTest {
        // Cleanup retries the key (best-effort) and then releases the modifier, in reverse press order.
        chords.forEach { chord ->
            val driver = FakeRobotKeyDriver(failOn = { it == "release:${chord.key}" })
            assertFalse(chord.inject(driver), chord.name)
            assertEquals(
                listOf("press:17", "press:${chord.key}", "release:${chord.key}", "release:${chord.key}", "release:17"),
                driver.calls,
                chord.name,
            )
        }
    }

    @Test
    fun `modifier release exception retries only the modifier`() = runTest {
        chords.forEach { chord ->
            val driver = FakeRobotKeyDriver(failOn = { it == "release:17" })
            assertFalse(chord.inject(driver), chord.name)
            assertEquals(
                listOf("press:17", "press:${chord.key}", "release:${chord.key}", "release:17", "release:17"),
                driver.calls,
                chord.name,
            )
        }
    }

    @Test
    fun `waitForIdle failure after full release attempts no further cleanup`() = runTest {
        chords.forEach { chord ->
            val driver = FakeRobotKeyDriver(failOn = { it == "waitForIdle" })
            assertFalse(chord.inject(driver), chord.name)
            assertEquals(
                listOf("press:17", "press:${chord.key}", "release:${chord.key}", "release:17", "waitForIdle"),
                driver.calls,
                chord.name,
            )
        }
    }
}
