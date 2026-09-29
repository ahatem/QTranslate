package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotPasteInjectorTest {

    @Test
    fun `robot creation exception returns false`() = runTest {
        val logger = RecordingLogger()
        val injector = RobotPasteInjector(logger, driverFactory = { throw RuntimeException("no display") }, clipboardWrite = {})
        assertFalse(injector.injectPaste("hello"))
        assertTrue(logger.warns.any { it.contains("Robot paste failed", ignoreCase = true) })
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
    fun `successful paste presses and releases in order`() = runTest {
        val driver = FakeRobotKeyDriver()
        val injector = RobotPasteInjector(RecordingLogger(), driverFactory = { driver }, clipboardWrite = {})
        assertTrue(injector.injectPaste("hello"))
        assertEquals(
            listOf("press:17", "press:86", "release:86", "release:17", "waitForIdle"),
            driver.calls
        )
    }

    @Test
    fun `main key press exception returns false and releases the pressed modifier`() = runTest {
        val driver = FakeRobotKeyDriver(failOn = { it == "press:86" })
        val injector = RobotPasteInjector(RecordingLogger(), driverFactory = { driver }, clipboardWrite = {})
        assertFalse(injector.injectPaste("hello"))
        assertEquals(listOf("press:17", "press:86", "release:17"), driver.calls)
    }

    @Test
    fun `modifier press exception never attempts a release`() = runTest {
        val driver = FakeRobotKeyDriver(failOn = { it == "press:17" })
        val injector = RobotPasteInjector(RecordingLogger(), driverFactory = { driver }, clipboardWrite = {})
        assertFalse(injector.injectPaste("hello"))
        assertFalse(driver.calls.any { it.startsWith("release") }, "nothing was pressed, so nothing may be released")
    }

    /**
     * The main key's own release fails: cleanup still attempts it again (best-effort, silently
     * swallowed) and then releases the modifier, in reverse press order.
     */
    @Test
    fun `main key release exception still attempts cleanup of both keys`() = runTest {
        val driver = FakeRobotKeyDriver(failOn = { it == "release:86" })
        val injector = RobotPasteInjector(RecordingLogger(), driverFactory = { driver }, clipboardWrite = {})
        assertFalse(injector.injectPaste("hello"))
        assertEquals(
            listOf("press:17", "press:86", "release:86", "release:86", "release:17"),
            driver.calls
        )
    }

    /**
     * The modifier's own release fails, after the main key already released cleanly: cleanup
     * must not re-attempt the key (already released) but must still retry the modifier.
     */
    @Test
    fun `modifier release exception retries only the modifier`() = runTest {
        val driver = FakeRobotKeyDriver(failOn = { it == "release:17" })
        val injector = RobotPasteInjector(RecordingLogger(), driverFactory = { driver }, clipboardWrite = {})
        assertFalse(injector.injectPaste("hello"))
        assertEquals(
            listOf("press:17", "press:86", "release:86", "release:17", "release:17"),
            driver.calls
        )
    }

    /**
     * `waitForIdle` fails after every key was already cleanly released: cleanup must not
     * re-release anything, since both ownership flags are already clear by that point.
     */
    @Test
    fun `waitForIdle failure after full release attempts no further cleanup`() = runTest {
        val driver = FakeRobotKeyDriver(failOn = { it == "waitForIdle" })
        val injector = RobotPasteInjector(RecordingLogger(), driverFactory = { driver }, clipboardWrite = {})
        assertFalse(injector.injectPaste("hello"))
        assertEquals(
            listOf("press:17", "press:86", "release:86", "release:17", "waitForIdle"),
            driver.calls
        )
    }
}
