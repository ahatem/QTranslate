package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import io.github.ahatem.qinput.AwtPortableKeys
import io.github.ahatem.qinput.QInputKey
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trigger neutralization: capture waits for the physical trigger plus every contaminating
 * modifier to read up, with a bounded fail-closed timeout. No wall-clock dependence beyond
 * virtual time.
 *
 * Watched keys are portable QInput usages, so the maps below are keyed by them rather than by
 * Win32 virtual keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TriggerNeutralizerTest {

    private fun binding() = HotkeyBinding(
        HotkeyAction.SHOW_IMAGES,
        keyCode = KeyEvent.VK_Q,
        modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
        scope = HotkeyScope.GLOBAL
    )

    @Test
    fun `released trigger is immediately neutral`() = runTest {
        val neutralizer = TriggerNeutralizer(anyDown = { false })
        assertTrue(neutralizer.awaitNeutral(binding()))
    }

    @Test
    fun `held trigger waits for release then succeeds once`() = runTest {
        val down = mutableMapOf(
            QInputKey.Q to true,
            QInputKey.MOD_LEFT_SHIFT to true,
            QInputKey.MOD_LEFT_ALT to true
        )
        var queries = 0
        val neutralizer = TriggerNeutralizer(anyDown = { usages ->
            queries++
            usages.any { down[it] ?: false }
        })
        var done = false
        val job = launch {
            done = neutralizer.awaitNeutral(binding())
        }
        advanceTimeBy(500)
        assertFalse(done)
        down.clear()
        advanceUntilIdle()
        job.join()
        assertTrue(done)
        assertTrue(queries > 1)
    }

    @Test
    fun `trigger held past the timeout fails closed`() = runTest {
        val neutralizer = TriggerNeutralizer(
            anyDown = { usages -> QInputKey.Q in usages },
            timeoutMs = 100L
        )
        assertFalse(neutralizer.awaitNeutral(binding()))
    }

    @Test
    fun `unrelated held shift blocks neutrality`() = runTest {
        // Ctrl and Q released, Shift still down: Ctrl+C would read as Ctrl+Shift+C.
        val down = mutableMapOf(QInputKey.MOD_LEFT_SHIFT to true)
        val neutralizer = TriggerNeutralizer(anyDown = { usages ->
            usages.any { down[it] ?: false }
        })
        var done: Boolean? = null
        val job = launch {
            done = neutralizer.awaitNeutral(binding())
        }
        advanceTimeBy(300)
        assertNull(done)
        down.clear()
        advanceUntilIdle()
        job.join()
        assertEquals(true, done)
    }

    @Test
    fun `query failure counts as down`() = runTest {
        val neutralizer = TriggerNeutralizer(
            anyDown = { throw RuntimeException("no backend") },
            timeoutMs = 100L
        )
        assertFalse(neutralizer.awaitNeutral(binding()))
    }

    @Test
    fun `unmappable trigger fails closed`() = runTest {
        val neutralizer = TriggerNeutralizer(anyDown = { false })
        val bad = HotkeyBinding(HotkeyAction.SHOW_IMAGES, keyCode = 0, modifiers = 0)
        assertFalse(neutralizer.awaitNeutral(bad))
    }

    /**
     * The watched set is the portable one: the trigger key plus the contamination modifiers,
     * with the Copy modifier (Ctrl) exempt, and both sides of each contaminating modifier.
     */
    @Test
    fun `watched set uses portable usages and excludes the copy modifier`() = runTest {
        var watched: List<Int>? = null
        val neutralizer = TriggerNeutralizer(
            anyDown = { false },
            triggerUsages = { binding ->
                AwtPortableKeys.neutralizationSet(binding.keyCode, binding.modifiers)
                    .also { watched = it }
            }
        )
        neutralizer.awaitNeutral(binding())
        assertEquals(
            listOf(
                QInputKey.Q,
                QInputKey.MOD_LEFT_SHIFT,
                QInputKey.MOD_RIGHT_SHIFT,
                QInputKey.MOD_LEFT_ALT,
                QInputKey.MOD_RIGHT_ALT,
                QInputKey.MOD_LEFT_META,
                QInputKey.MOD_RIGHT_META,
            ),
            watched
        )
        assertTrue(QInputKey.MOD_LEFT_CONTROL !in watched!!)
        assertTrue(QInputKey.MOD_RIGHT_CONTROL !in watched!!)
    }

    /** A held right-hand modifier must block exactly as the left-hand one does. */
    @Test
    fun `right-side modifiers block neutrality`() = runTest {
        for (held in listOf(
            QInputKey.MOD_RIGHT_SHIFT,
            QInputKey.MOD_RIGHT_ALT,
            QInputKey.MOD_RIGHT_META
        )) {
            val down = mutableMapOf(held to true)
            val neutralizer = TriggerNeutralizer(
                anyDown = { usages -> usages.any { down[it] ?: false } }
            )
            var done: Boolean? = null
            val job = launch { done = neutralizer.awaitNeutral(binding()) }
            advanceTimeBy(300)
            assertNull(done, "a held $held must block neutralization")
            down.clear()
            advanceUntilIdle()
            job.join()
            assertEquals(true, done)
        }
    }

    /** A single batch query per poll, not one per watched key. */
    @Test
    fun `watched set is queried as one batch per poll`() = runTest {
        var calls = 0
        val neutralizer = TriggerNeutralizer(anyDown = {
            calls++
            false
        })
        neutralizer.awaitNeutral(binding())
        assertEquals(1, calls, "one poll must issue exactly one batch query")
    }
}
