package com.github.ahatem.qtranslate.ui.swing.main.input

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Double-Ctrl tap-pair state machine: a release counts as a tap only after an observed press,
 * so orphan or synthetic key-up traffic (for example a clipboard manager releasing modifiers
 * around a paste) can never arm or complete a pair.
 */
class DoubleCtrlDetectorTest {

    private sealed interface Step
    private data object Press : Step
    private data object OtherKey : Step
    private data object Reset : Step
    private data class Release(val at: Long, val active: Boolean = true) : Step

    /** Runs [steps] and returns the result of every [Release], in order. */
    private fun run(vararg steps: Step): List<Boolean> {
        val detector = DoubleCtrlDetector()
        return steps.mapNotNull { step ->
            when (step) {
                Press -> detector.onControlPressed().let { null }
                OtherKey -> detector.onOtherKeyPressed().let { null }
                Reset -> detector.reset().let { null }
                is Release -> detector.onControlReleased(step.at, step.active)
            }
        }
    }

    @Test
    fun `only two genuine taps within the threshold trigger, once`() {
        val cases = mapOf(
            "two taps" to (arrayOf(Press, Release(1000), Press, Release(1200)) to listOf(false, true)),
            "a single tap" to (arrayOf(Press, Release(1000)) to listOf(false)),
            "taps outside the threshold" to (arrayOf(Press, Release(1000), Press, Release(1500)) to listOf(false, false)),
            // Timestamps are monotonic microseconds since startup, so a first tap can land at zero.
            // A 0L "no previous tap" sentinel made that lone tap pair with itself.
            "a lone tap at zero" to (arrayOf(Press, Release(0)) to listOf(false)),
            "taps at the origin still pair" to (arrayOf(Press, Release(1), Press, Release(50)) to listOf(false, true)),
            "reset forgets an armed tap" to (arrayOf(Press, Release(1000), Reset, Press, Release(1100)) to listOf(false, false)),
        )
        cases.forEach { (name, case) -> assertEquals(case.second, run(*case.first), name) }
    }

    @Test
    fun `orphan releases never arm or complete a pair`() {
        val cases = mapOf(
            "two orphans, then a lone tap" to
                (arrayOf(Release(1000), Release(1100), Press, Release(1200)) to listOf(false, false, false)),
            "an orphan after a valid tap leaves it armed" to
                (arrayOf(Press, Release(1000), Release(1100), Press, Release(1200)) to listOf(false, false, true)),
        )
        cases.forEach { (name, case) -> assertEquals(case.second, run(*case.first), name) }
    }

    @Test
    fun `a combination with another key is not a tap and clears the armed tap`() {
        val cases = mapOf(
            "combination alone" to (arrayOf(Press, OtherKey, Release(1000)) to listOf(false)),
            "combination, then a tap" to
                (arrayOf(Press, OtherKey, Release(1000), Press, Release(1100)) to listOf(false, false)),
            "tap, then a combination, then a lone tap" to
                (arrayOf(Press, Release(1000), Press, OtherKey, Release(1100), Press, Release(1200)) to
                    listOf(false, false, false)),
            // Auto-repeat of the held Ctrl must not erase the combination state.
            "repeated ctrl down while held" to
                (arrayOf(Press, OtherKey, Press, Release(1000), Press, Release(1100)) to listOf(false, false)),
        )
        cases.forEach { (name, case) -> assertEquals(case.second, run(*case.first), name) }
    }

    @Test
    fun `firing is gated on active while tracking continues`() {
        assertEquals(
            listOf(false, false, false, true),
            run(
                Press, Release(1000, active = false),
                Press, Release(1100, active = false),
                Press, Release(1200),
                Press, Release(1300),
            ),
        )
    }
}
