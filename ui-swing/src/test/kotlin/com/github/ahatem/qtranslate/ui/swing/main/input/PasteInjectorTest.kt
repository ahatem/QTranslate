package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingLogger
import io.github.ahatem.qinput.QInputException
import io.github.ahatem.qinput.QInputKey
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

@OptIn(ExperimentalCoroutinesApi::class)
class PasteInjectorTest {

    private fun injector(
        backend: FakeGlobalInputBackend,
        written: MutableList<String> = mutableListOf(),
        fallbackCalls: MutableList<String> = mutableListOf(),
    ) = QInputPasteInjector(
        backend = { backend },
        clipboardWrite = { written += it },
        logger = RecordingLogger(),
        fallback = { text ->
            fallbackCalls += text
            true
        },
        isMac = false
    )

    @Test
    fun `normal native paste writes once and chords once`() = runTest {
        val written = mutableListOf<String>()
        val fallbackCalls = mutableListOf<String>()
        val backend = FakeGlobalInputBackend().apply { supportsInjection = true }

        assertTrue(injector(backend, written, fallbackCalls).injectPaste("hello"))
        advanceUntilIdle()

        // No virtual time passes: nothing arbitrary was slept.
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(listOf("hello"), written)
        assertTrue(fallbackCalls.isEmpty())
        // Portable QInput usages, not Win32 virtual keys.
        assertEquals<List<Pair<List<Int>, Int>>>(
            listOf(listOf(QInputKey.MOD_LEFT_CONTROL) to QInputKey.V),
            backend.chords
        )
    }

    @Test
    fun `only outcomes that prove zero delivery fall back, uncertain ones fail closed`() = runTest {
        val safeToRetry = listOf(
            NativeInjectionStatus.INVALID_ARGUMENT,
            NativeInjectionStatus.UNSUPPORTED,
            NativeInjectionStatus.INJECTION_NOT_DELIVERED,
        )
        // BACKEND_ERROR is deliberately not retry-safe: a physically held main chord key fails this
        // way on Windows, and a Robot retry would recreate the same ownership conflict.
        val failClosed = listOf(
            NativeInjectionStatus.BACKEND_ERROR,
            NativeInjectionStatus.INJECTION_UNCERTAIN,
        )
        (safeToRetry + failClosed).forEach { status ->
            val backend = FakeGlobalInputBackend().apply {
                supportsInjection = true
                chordThrows = QInputException("status $status", status)
            }
            val fallbackCalls = mutableListOf<String>()

            assertEquals(status in safeToRetry, injector(backend, fallbackCalls = fallbackCalls).injectPaste("hello"), "status $status")
            assertEquals(if (status in safeToRetry) listOf("hello") else emptyList(), fallbackCalls, "status $status")
        }
    }

    @Test
    fun `capability absent uses fallback exactly once without touching the clipboard`() = runTest {
        val written = mutableListOf<String>()
        val fallbackCalls = mutableListOf<String>()
        val backend = FakeGlobalInputBackend().apply { supportsInjection = false }

        assertTrue(injector(backend, written, fallbackCalls).injectPaste("hello"))

        assertTrue(backend.chords.isEmpty())
        assertTrue(written.isEmpty())
        assertEquals(listOf("hello"), fallbackCalls)
    }

    @Test
    fun `a held contaminating modifier blocks paste until release, the chord's own does not`() = runTest {
        val blocking = listOf(
            QInputKey.MOD_LEFT_SHIFT, QInputKey.MOD_RIGHT_SHIFT,
            QInputKey.MOD_LEFT_ALT, QInputKey.MOD_RIGHT_ALT,
            QInputKey.MOD_LEFT_META, QInputKey.MOD_RIGHT_META,
        )
        blocking.forEach { held ->
            val backend = FakeGlobalInputBackend().apply {
                supportsInjection = true
                keyDown[held] = true
            }
            var done: Boolean? = null
            val job = launch { done = injector(backend).injectPaste("hello") }
            advanceTimeBy(500)
            assertNull(done, "a held ${Integer.toHexString(held)} must block paste")
            assertTrue(backend.chords.isEmpty())

            backend.keyDown.clear()
            advanceUntilIdle()
            job.join()
            assertEquals(true, done)
            assertEquals(1, backend.chords.size)
        }

        listOf(QInputKey.MOD_LEFT_CONTROL, QInputKey.MOD_RIGHT_CONTROL).forEach { held ->
            val backend = FakeGlobalInputBackend().apply {
                supportsInjection = true
                keyDown[held] = true
            }
            assertTrue(injector(backend).injectPaste("hello"))
            advanceUntilIdle()
            assertEquals(1, backend.chords.size, "a held ${Integer.toHexString(held)} is harmless")
        }
    }

    @Test
    fun `neutralization timeout fails without touching clipboard or chord`() = runTest {
        val written = mutableListOf<String>()
        val backend = FakeGlobalInputBackend().apply {
            supportsInjection = true
            keyDown[QInputKey.MOD_LEFT_SHIFT] = true
        }

        assertFalse(injector(backend, written).injectPaste("hello"))
        advanceUntilIdle()
        assertTrue(written.isEmpty())
        assertTrue(backend.chords.isEmpty())
    }

    /**
     * A backend with injection but no physical key-state query cannot prove neutralization. The
     * wait is skipped rather than stalling every paste into a timeout, and injection still happens.
     */
    @Test
    fun `key state absent skips neutralization without stalling`() = runTest {
        val written = mutableListOf<String>()
        val backend = FakeGlobalInputBackend(
            capabilities = FakeGlobalInputBackend().capabilities.copy(keyState = false)
        ).apply {
            supportsInjection = true
            keyDown[QInputKey.MOD_LEFT_SHIFT] = true
        }

        assertTrue(injector(backend, written).injectPaste("hello"))
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(listOf("hello"), written)
        assertEquals(1, backend.chords.size)
    }
}
