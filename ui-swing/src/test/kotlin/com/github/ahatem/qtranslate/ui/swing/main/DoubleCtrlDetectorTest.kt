package com.github.ahatem.qtranslate.ui.swing.main

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Double-Ctrl tap-pair state machine: a release counts as a tap only after an observed press,
 * so orphan or synthetic key-up traffic (for example a clipboard manager releasing modifiers
 * around a paste) can never arm or complete a pair.
 */
class DoubleCtrlDetectorTest {

    @Test
    fun `two genuine taps within threshold trigger exactly once`() {
        val detector = DoubleCtrlDetector()

        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1000L, active = true))
        detector.onControlPressed()
        assertTrue(detector.onControlReleased(1200L, active = true))
    }

    @Test
    fun `a single genuine tap does not trigger`() {
        val detector = DoubleCtrlDetector()

        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1000L, active = true))
    }

    @Test
    fun `two orphan releases within threshold do not trigger or arm`() {
        val detector = DoubleCtrlDetector()

        assertFalse(detector.onControlReleased(1000L, active = true))
        assertFalse(detector.onControlReleased(1100L, active = true))

        // The orphans must not have armed anything: a lone tap afterwards still only arms.
        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1200L, active = true))
    }

    @Test
    fun `orphan release after a valid tap does not complete double ctrl`() {
        val detector = DoubleCtrlDetector()

        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1000L, active = true))
        assertFalse(detector.onControlReleased(1100L, active = true))

        // The earlier tap is still armed and unspoiled: a genuine second tap fires.
        detector.onControlPressed()
        assertTrue(detector.onControlReleased(1200L, active = true))
    }

    @Test
    fun `ctrl C does not count as a tap`() {
        val detector = DoubleCtrlDetector()

        detector.onControlPressed()
        detector.onOtherKeyPressed()
        assertFalse(detector.onControlReleased(1000L, active = true))
    }

    @Test
    fun `ctrl V does not count as a tap`() {
        val detector = DoubleCtrlDetector()

        detector.onControlPressed()
        detector.onOtherKeyPressed()
        assertFalse(detector.onControlReleased(1000L, active = true))

        // The combination also clears any previously armed tap.
        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1100L, active = true))
    }

    @Test
    fun `combination followed by a tap does not trigger`() {
        val detector = DoubleCtrlDetector()

        detector.onControlPressed()
        detector.onOtherKeyPressed()
        assertFalse(detector.onControlReleased(1000L, active = true))
        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1100L, active = true))
    }

    @Test
    fun `tap followed by a combination does not trigger`() {
        val detector = DoubleCtrlDetector()

        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1000L, active = true))
        detector.onControlPressed()
        detector.onOtherKeyPressed()
        assertFalse(detector.onControlReleased(1100L, active = true))

        // The combination cleared the armed tap: the next lone tap only rearms.
        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1200L, active = true))
    }

    @Test
    fun `repeated ctrl down while held does not erase combination state`() {
        val detector = DoubleCtrlDetector()

        detector.onControlPressed()
        detector.onOtherKeyPressed()
        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1000L, active = true))

        // Combination path ran (armed tap cleared): a following lone tap only rearms.
        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1100L, active = true))
    }

    @Test
    fun `two taps outside the threshold do not trigger`() {
        val detector = DoubleCtrlDetector()

        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1000L, active = true))
        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1500L, active = true))
    }

    @Test
    fun `firing is gated on active while tracking continues`() {
        val detector = DoubleCtrlDetector()

        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1000L, active = false))
        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1100L, active = false))

        detector.onControlPressed()
        assertFalse(detector.onControlReleased(1200L, active = true))
        detector.onControlPressed()
        assertTrue(detector.onControlReleased(1300L, active = true))
    }
}
