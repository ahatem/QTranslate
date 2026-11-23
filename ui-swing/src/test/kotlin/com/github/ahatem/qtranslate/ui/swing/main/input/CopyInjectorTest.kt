package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingLogger
import io.github.ahatem.qinput.QInputException
import io.github.ahatem.qinput.QInputKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [QInputCopyInjector] owns its complete Copy attempt, fallback included. Every test supplies its
 * own `fallback` lambda rather than a real Robot, so no test touches the developer's keyboard.
 */
class CopyInjectorTest {

    /** A scripted, non-Robot fallback: records call count, returns [result]. */
    private class RecordingFallback(val result: Boolean = true) : () -> Boolean {
        var calls = 0
        override fun invoke(): Boolean {
            calls++
            return result
        }
    }

    private fun throwing(failure: Throwable): GlobalInputBackend = object : GlobalInputBackend by FakeGlobalInputBackend() {
        override val supportsInjection: Boolean = true
        override fun sendChord(modifiers: List<Int>, key: Int): Boolean = throw failure
    }

    private fun native(status: Int) = throwing(QInputException("native $status", status))

    @Test
    fun `without a usable native send the copy yields to fallback exactly once`() {
        val unsupported = FakeGlobalInputBackend().apply { supportsInjection = false }
        val refused = FakeGlobalInputBackend().apply {
            supportsInjection = true
            chordResult = false
        }
        // Refusal (false) is as strong a proof of non-delivery as a validation exception.
        val cases: Map<String, GlobalInputBackend?> = mapOf(
            "unsupported backend" to unsupported,
            "missing backend" to null,
            "native refusal" to refused,
            "invalid argument" to native(NativeInjectionStatus.INVALID_ARGUMENT),
            "unsupported status" to native(NativeInjectionStatus.UNSUPPORTED),
            "explicit not-delivered" to native(NativeInjectionStatus.INJECTION_NOT_DELIVERED),
        )
        cases.forEach { (name, backend) ->
            val fallback = RecordingFallback()
            val injector = QInputCopyInjector({ backend }, RecordingLogger(), fallback = fallback)

            assertEquals(fallback.result, injector.injectCopy(), name)
            assertEquals(1, fallback.calls, name)
        }
    }

    /**
     * Anything that does not prove zero delivery must never retry through Robot: a physically held
     * chord key fails as a backend error on Windows, and a retry would recreate the conflict. The
     * call reports "handled" so the caller does not inject a second, duplicating Copy.
     */
    @Test
    fun `an outcome that does not prove zero delivery forbids fallback`() {
        val cases = mapOf(
            "backend error" to native(NativeInjectionStatus.BACKEND_ERROR),
            "uncertain delivery" to native(NativeInjectionStatus.INJECTION_UNCERTAIN),
            "non-native exception" to throwing(RuntimeException("UIPI blocked")),
        )
        cases.forEach { (name, backend) ->
            val logger = RecordingLogger()
            val fallback = RecordingFallback()
            val injector = QInputCopyInjector({ backend }, logger, fallback = fallback)

            assertTrue(injector.injectCopy(), name)
            assertEquals(0, fallback.calls, name)
        }
    }

    @Test
    fun `the native copy chord is Ctrl+C, or Cmd+C on macOS, and never falls back`() {
        mapOf(false to QInputKey.MOD_LEFT_CONTROL, true to QInputKey.MOD_LEFT_META).forEach { (isMac, modifier) ->
            var sent: Pair<List<Int>, Int>? = null
            val fake = FakeGlobalInputBackend().apply { supportsInjection = true }
            val backend = object : GlobalInputBackend by fake {
                override fun sendChord(modifiers: List<Int>, key: Int): Boolean {
                    sent = modifiers to key
                    return true
                }
            }
            val fallback = RecordingFallback()
            val injector = QInputCopyInjector({ backend }, RecordingLogger(), fallback = fallback, isMac = isMac)

            assertTrue(injector.injectCopy())
            // Portable QInput usages, not Win32 virtual keys.
            assertEquals(listOf(modifier) to QInputKey.C, sent, "isMac = $isMac")
            assertEquals(0, fallback.calls, "a successful native chord must never invoke fallback")
        }
    }
}
