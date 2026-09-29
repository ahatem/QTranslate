package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotCopyInjectorTest {

    @Test
    fun `robot creation exception returns false`() {
        val logger = RecordingLogger()
        val injector = RobotCopyInjector(logger, driverFactory = { throw RuntimeException("no display") })
        assertFalse(injector.injectCopy())
        assertTrue(logger.warns.any { it.contains("Copy simulation failed", ignoreCase = true) })
    }

    @Test
    fun `successful copy presses and releases in order`() {
        val driver = FakeRobotKeyDriver()
        val injector = RobotCopyInjector(RecordingLogger(), driverFactory = { driver })
        assertTrue(injector.injectCopy())
        assertEquals(
            listOf("press:17", "press:67", "release:67", "release:17", "waitForIdle"),
            driver.calls
        )
    }

    @Test
    fun `main key press exception returns false and releases the pressed modifier`() {
        // Fails on the C key-press, after the modifier is already down: the modifier must be
        // best-effort released, since Robot itself pressed it. The key was never pressed, so it
        // must not be "released".
        val driver = FakeRobotKeyDriver(failOn = { it == "press:67" })
        val injector = RobotCopyInjector(RecordingLogger(), driverFactory = { driver })
        assertFalse(injector.injectCopy())
        assertEquals(listOf("press:17", "press:67", "release:17"), driver.calls)
    }

    @Test
    fun `modifier press exception never attempts a release`() {
        // Fails on the very first call (the modifier press itself): nothing was pressed, so
        // cleanup must never run.
        val driver = FakeRobotKeyDriver(failOn = { it == "press:17" })
        val injector = RobotCopyInjector(RecordingLogger(), driverFactory = { driver })
        assertFalse(injector.injectCopy())
        assertFalse(driver.calls.any { it.startsWith("release") }, "nothing was pressed, so nothing may be released")
    }

    /**
     * The main key's own release fails: cleanup still attempts it again (best-effort, silently
     * swallowed) and then releases the modifier, in reverse press order. Both keys were pressed,
     * so both must be attempted, regardless of whether the retry actually succeeds.
     */
    @Test
    fun `main key release exception still attempts cleanup of both keys`() {
        val driver = FakeRobotKeyDriver(failOn = { it == "release:67" })
        val injector = RobotCopyInjector(RecordingLogger(), driverFactory = { driver })
        assertFalse(injector.injectCopy())
        assertEquals(
            listOf("press:17", "press:67", "release:67", "release:67", "release:17"),
            driver.calls
        )
    }

    /**
     * The modifier's own release fails, after the main key already released cleanly: cleanup
     * must not re-attempt the key (already released) but must still retry the modifier.
     */
    @Test
    fun `modifier release exception retries only the modifier`() {
        val driver = FakeRobotKeyDriver(failOn = { it == "release:17" })
        val injector = RobotCopyInjector(RecordingLogger(), driverFactory = { driver })
        assertFalse(injector.injectCopy())
        assertEquals(
            listOf("press:17", "press:67", "release:67", "release:17", "release:17"),
            driver.calls
        )
    }

    /**
     * `waitForIdle` fails after every key was already cleanly released: cleanup must not
     * re-release anything, since both ownership flags are already clear by that point.
     */
    @Test
    fun `waitForIdle failure after full release attempts no further cleanup`() {
        val driver = FakeRobotKeyDriver(failOn = { it == "waitForIdle" })
        val injector = RobotCopyInjector(RecordingLogger(), driverFactory = { driver })
        assertFalse(injector.injectCopy())
        assertEquals(
            listOf("press:17", "press:67", "release:67", "release:17", "waitForIdle"),
            driver.calls
        )
    }
}
