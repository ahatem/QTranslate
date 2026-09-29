package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingLogger
import io.github.ahatem.qinput.QInputException
import io.github.ahatem.qinput.QInputKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [QInputCopyInjector] now owns its complete Copy attempt, fallback included (see that class's
 * doc). Every test here supplies its own `fallback` lambda rather than the real default (a real
 * [RobotCopyInjector]), so no test can ever construct a real `java.awt.Robot` or touch the
 * developer's actual keyboard: the fallback lambda records how many times it was called and
 * returns a scripted result, which is exactly what "exactly one logical Copy attempt" requires
 * proving at this level.
 */
class CopyInjectorTest {

    private fun backend(
        supportsInjection: Boolean,
        chordResult: Boolean = true,
        onChord: ((List<Int>, Int) -> Boolean)? = null,
    ): GlobalInputBackend {
        val fake = FakeGlobalInputBackend()
        fake.supportsInjection = supportsInjection
        fake.chordResult = chordResult
        return if (onChord == null) fake else object : GlobalInputBackend by fake {
            override fun sendChord(modifiers: List<Int>, key: Int): Boolean = onChord(modifiers, key)
        }
    }

    /** A scripted, non-Robot fallback: records call count, returns [result]. */
    private class RecordingFallback(val result: Boolean = true) : () -> Boolean {
        var calls = 0
        override fun invoke(): Boolean {
            calls++
            return result
        }
    }

    @Test
    fun `unsupported backend yields to fallback`() {
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector(
            { backend(supportsInjection = false) },
            RecordingLogger(),
            fallback = fallback,
        )
        assertEquals(fallback.result, injector.injectCopy())
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `supported backend sends the copy chord`() {
        var sent: Pair<List<Int>, Int>? = null
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector(
            {
                backend(supportsInjection = true) { mods, key ->
                    sent = mods to key
                    true
                }
            },
            RecordingLogger(),
            fallback = fallback,
            isMac = false
        )
        assertTrue(injector.injectCopy())
        // Portable QInput usages, not Win32 virtual keys.
        assertEquals(listOf(QInputKey.MOD_LEFT_CONTROL) to QInputKey.C, sent)
        assertEquals(0, fallback.calls, "a successful native chord must never invoke fallback")
    }

    @Test
    fun `mac copy chord uses meta`() {
        var sent: Pair<List<Int>, Int>? = null
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector(
            {
                backend(supportsInjection = true) { mods, key ->
                    sent = mods to key
                    true
                }
            },
            RecordingLogger(),
            fallback = fallback,
            isMac = true
        )
        assertTrue(injector.injectCopy())
        assertEquals(listOf(QInputKey.MOD_LEFT_META) to QInputKey.C, sent)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `native refusal yields to fallback`() {
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector(
            {
                val fake = FakeGlobalInputBackend()
                fake.supportsInjection = true
                fake.chordResult = false
                fake
            },
            RecordingLogger(),
            fallback = fallback,
        )
        // sendChord returning false means refused (never thrown): the injector still falls back
        // exactly once, since a plain refusal is at least as strong a proof of non-delivery as a
        // validation exception.
        assertEquals(fallback.result, injector.injectCopy())
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `native validation failure yields to fallback`() {
        val logger = RecordingLogger()
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector(
            {
                object : GlobalInputBackend by FakeGlobalInputBackend() {
                    override val supportsInjection: Boolean = true
                    override fun sendChord(modifiers: List<Int>, key: Int): Boolean =
                        throw QInputException("bad args", NativeInjectionStatus.INVALID_ARGUMENT)
                }
            },
            logger,
            fallback = fallback,
        )
        // A validation failure proves nothing was sent: Robot fallback is safe.
        assertEquals(fallback.result, injector.injectCopy())
        assertEquals(1, fallback.calls)
        assertTrue(logger.warns.any { it.contains("falling back", ignoreCase = true) })
    }

    @Test
    fun `native unsupported failure yields to fallback`() {
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector(
            {
                object : GlobalInputBackend by FakeGlobalInputBackend() {
                    override val supportsInjection: Boolean = true
                    override fun sendChord(modifiers: List<Int>, key: Int): Boolean =
                        throw QInputException("unsupported", NativeInjectionStatus.UNSUPPORTED)
                }
            },
            RecordingLogger(),
            fallback = fallback,
        )
        assertEquals(fallback.result, injector.injectCopy())
        assertEquals(1, fallback.calls)
    }

    /**
     * A generic backend error is NOT in the retry-safe set (see [NativeInjectionStatus]): unlike
     * validation/unsupported failures, it does not prove zero delivery (a physically-held main
     * chord key, for instance, fails this exact way on Windows), so fallback must not run.
     */
    @Test
    fun `native backend error forbids fallback`() {
        val logger = RecordingLogger()
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector(
            {
                object : GlobalInputBackend by FakeGlobalInputBackend() {
                    override val supportsInjection: Boolean = true
                    override fun sendChord(modifiers: List<Int>, key: Int): Boolean =
                        throw QInputException("backend error", NativeInjectionStatus.BACKEND_ERROR)
                }
            },
            logger,
            fallback = fallback,
        )
        assertTrue(injector.injectCopy())
        assertEquals(0, fallback.calls, "a generic backend failure must not be treated as retry-safe")
    }

    @Test
    fun `native not-delivered status yields to fallback`() {
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector(
            {
                object : GlobalInputBackend by FakeGlobalInputBackend() {
                    override val supportsInjection: Boolean = true
                    override fun sendChord(modifiers: List<Int>, key: Int): Boolean =
                        throw QInputException("zero delivered", NativeInjectionStatus.INJECTION_NOT_DELIVERED)
                }
            },
            RecordingLogger(),
            fallback = fallback,
        )
        assertEquals(fallback.result, injector.injectCopy())
        assertEquals(1, fallback.calls, "the explicit retry-safe status must fall back")
    }

    @Test
    fun `native uncertain delivery forbids fallback`() {
        val logger = RecordingLogger()
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector(
            {
                object : GlobalInputBackend by FakeGlobalInputBackend() {
                    override val supportsInjection: Boolean = true
                    override fun sendChord(modifiers: List<Int>, key: Int): Boolean =
                        throw QInputException("partial SendInput", NativeInjectionStatus.INJECTION_UNCERTAIN)
                }
            },
            logger,
            fallback = fallback,
        )
        // Uncertain delivery must never fall back through Robot: this must report "handled"
        // (true) so the caller does not attempt a second, possibly duplicating, injection.
        assertTrue(injector.injectCopy())
        assertEquals(0, fallback.calls)
        assertTrue(logger.warns.any { it.contains("not retrying", ignoreCase = true) })
    }

    /**
     * A non-native failure (never thrown by the real backend, but not ruled out by the
     * interface contract either) proves nothing about delivery, so the conservative policy
     * applies: no fallback, exactly like an uncertain native outcome.
     */
    @Test
    fun `unexpected non-native exception forbids fallback`() {
        val logger = RecordingLogger()
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector(
            {
                object : GlobalInputBackend by FakeGlobalInputBackend() {
                    override val supportsInjection: Boolean = true
                    override fun sendChord(modifiers: List<Int>, key: Int): Boolean =
                        throw RuntimeException("UIPI blocked")
                }
            },
            logger,
            fallback = fallback,
        )
        assertTrue(injector.injectCopy())
        assertEquals(0, fallback.calls)
        assertTrue(logger.warns.any { it.contains("not retrying", ignoreCase = true) })
    }

    @Test
    fun `missing backend yields to fallback`() {
        val fallback = RecordingFallback()
        val injector = QInputCopyInjector({ null }, RecordingLogger(), fallback = fallback)
        assertEquals(fallback.result, injector.injectCopy())
        assertEquals(1, fallback.calls)
    }
}
