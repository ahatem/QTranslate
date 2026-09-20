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
        backend: FakeGlobalInputBackend = FakeGlobalInputBackend(),
        written: MutableList<String> = mutableListOf(),
        fallbackCalls: MutableList<String> = mutableListOf(),
        isMac: Boolean = false,
    ): Pair<QInputPasteInjector, FakeGlobalInputBackend> {
        val injector = QInputPasteInjector(
            backend = { backend },
            clipboardWrite = { written += it },
            logger = RecordingLogger(),
            fallback = { text ->
                fallbackCalls += text
                true
            },
            isMac = isMac
        )
        return injector to backend
    }

    @Test
    fun `normal native paste writes once and chords once`() = runTest {
        val written = mutableListOf<String>()
        val backend = FakeGlobalInputBackend().apply { supportsInjection = true }
        val injector = QInputPasteInjector(
            backend = { backend },
            clipboardWrite = { written += it },
            logger = RecordingLogger(),
            fallback = { error("must not fall back") },
            isMac = false
        )

        assertTrue(injector.injectPaste("hello"))
        advanceUntilIdle()

        // No virtual time passes: nothing arbitrary was slept.
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(listOf("hello"), written)
        assertEquals(1, backend.chords.size)
        // Portable QInput usages, not Win32 virtual keys.
        assertEquals<List<Pair<List<Int>, Int>>>(
            listOf(listOf(QInputKey.MOD_LEFT_CONTROL) to QInputKey.V),
            backend.chords
        )
    }

    @Test
    fun `capability absent uses fallback exactly once`() = runTest {
        val written = mutableListOf<String>()
        val fallbackCalls = mutableListOf<String>()
        val backend = FakeGlobalInputBackend().apply { supportsInjection = false }
        val injector = QInputPasteInjector(
            backend = { backend },
            clipboardWrite = { written += it },
            logger = RecordingLogger(),
            fallback = { text ->
                fallbackCalls += text
                true
            },
            isMac = false
        )

        assertTrue(injector.injectPaste("hello"))

        assertTrue(backend.chords.isEmpty())
        assertTrue(written.isEmpty())
        assertEquals(listOf("hello"), fallbackCalls)
    }

    @Test
    fun `validation failure falls back, transport failure fails closed`() = runTest {
        // INVALID_ARGUMENT proves nothing was sent: fallback is safe.
        val (injector, backend) = injector()
        backend.supportsInjection = true
        backend.chordThrows = QInputException("bad", NativeInjectionStatus.INVALID_ARGUMENT)
        val fallbackCalls = mutableListOf<String>()
        val withFallback = QInputPasteInjector(
            backend = { backend },
            clipboardWrite = {},
            logger = RecordingLogger(),
            fallback = { text ->
                fallbackCalls += text
                true
            },
            isMac = false
        )
        assertTrue(withFallback.injectPaste("hello"))
        assertEquals(listOf("hello"), fallbackCalls)

        // BACKEND_ERROR leaves delivery uncertain: no fallback, no duplicate, no retry. It is
        // deliberately NOT in the retry-safe set: a physically-held main chord key fails this
        // exact way on Windows, and retrying it through Robot would recreate the same ownership
        // conflict.
        backend.chordThrows = QInputException("stuck?", NativeInjectionStatus.BACKEND_ERROR)
        val fallbackCalls2 = mutableListOf<String>()
        val closedInjector = QInputPasteInjector(
            backend = { backend },
            clipboardWrite = {},
            logger = RecordingLogger(),
            fallback = { text ->
                fallbackCalls2 += text
                true
            },
            isMac = false
        )
        assertFalse(closedInjector.injectPaste("hello"))
        assertTrue(fallbackCalls2.isEmpty())
    }

    @Test
    fun `unsupported capability failure falls back`() = runTest {
        val (injector, backend) = injector()
        backend.supportsInjection = true
        backend.chordThrows = QInputException("unsupported", NativeInjectionStatus.UNSUPPORTED)
        val fallbackCalls = mutableListOf<String>()
        val withFallback = QInputPasteInjector(
            backend = { backend },
            clipboardWrite = {},
            logger = RecordingLogger(),
            fallback = { text ->
                fallbackCalls += text
                true
            },
            isMac = false
        )
        assertTrue(withFallback.injectPaste("hello"))
        assertEquals(listOf("hello"), fallbackCalls)
    }

    @Test
    fun `explicit not-delivered status falls back`() = runTest {
        val (injector, backend) = injector()
        backend.supportsInjection = true
        backend.chordThrows = QInputException("zero delivered", NativeInjectionStatus.INJECTION_NOT_DELIVERED)
        val fallbackCalls = mutableListOf<String>()
        val withFallback = QInputPasteInjector(
            backend = { backend },
            clipboardWrite = {},
            logger = RecordingLogger(),
            fallback = { text ->
                fallbackCalls += text
                true
            },
            isMac = false
        )
        assertTrue(withFallback.injectPaste("hello"))
        assertEquals(listOf("hello"), fallbackCalls)
    }

    @Test
    fun `uncertain native delivery fails closed without fallback`() = runTest {
        val (injector, backend) = injector()
        backend.supportsInjection = true
        backend.chordThrows = QInputException("partial SendInput", NativeInjectionStatus.INJECTION_UNCERTAIN)
        val fallbackCalls = mutableListOf<String>()
        val closedInjector = QInputPasteInjector(
            backend = { backend },
            clipboardWrite = {},
            logger = RecordingLogger(),
            fallback = { text ->
                fallbackCalls += text
                true
            },
            isMac = false
        )
        assertFalse(closedInjector.injectPaste("hello"))
        assertTrue(fallbackCalls.isEmpty(), "an uncertain delivery must never retry through Robot")
    }

    @Test
    fun `shift held blocks paste until release`() = runTest {
        val (injector, backend) = injector()
        backend.supportsInjection = true
        backend.keyDown[QInputKey.MOD_LEFT_SHIFT] = true

        var done = false
        val job = launch {
            done = injector.injectPaste("hello")
        }
        advanceTimeBy(500)
        assertFalse(done)
        assertTrue(backend.chords.isEmpty())

        backend.keyDown.clear()
        advanceUntilIdle()
        job.join()
        assertTrue(done)
        assertEquals(1, backend.chords.size)
    }

    @Test
    fun `alt held blocks paste until release`() = runTest {
        val (injector, backend) = injector()
        backend.supportsInjection = true
        backend.keyDown[QInputKey.MOD_LEFT_ALT] = true

        var done: Boolean? = null
        val job = launch {
            done = injector.injectPaste("hello")
        }
        advanceTimeBy(500)
        assertNull(done)
        backend.keyDown.clear()
        advanceUntilIdle()
        job.join()
        assertEquals(true, done)
        assertEquals(1, backend.chords.size)
    }

    @Test
    fun `ctrl held does not block windows paste`() = runTest {
        val (injector, backend) = injector()
        backend.supportsInjection = true
        backend.keyDown[QInputKey.MOD_LEFT_CONTROL] = true

        assertTrue(injector.injectPaste("hello"))
        advanceUntilIdle()
        assertEquals<List<Pair<List<Int>, Int>>>(
            listOf(listOf(QInputKey.MOD_LEFT_CONTROL) to QInputKey.V),
            backend.chords
        )
    }

    @Test
    fun `neutralization timeout fails without touching clipboard or chord`() = runTest {
        val written = mutableListOf<String>()
        val backend = FakeGlobalInputBackend().apply {
            supportsInjection = true
            keyDown[QInputKey.MOD_LEFT_SHIFT] = true
        }
        val injector = QInputPasteInjector(
            backend = { backend },
            clipboardWrite = { written += it },
            logger = RecordingLogger(),
            fallback = { true },
            isMac = false
        )

        assertFalse(injector.injectPaste("hello"))
        advanceUntilIdle()
        assertTrue(written.isEmpty())
        assertTrue(backend.chords.isEmpty())
    }

    @Test
    fun `mac chord uses meta and watches ctrl`() = runTest {
        assertEquals(
            listOf(QInputKey.MOD_LEFT_META) to QInputKey.V,
            QInputPasteInjector.platformPasteChord(true)
        )
        assertEquals(
            listOf(QInputKey.MOD_LEFT_CONTROL) to QInputKey.V,
            QInputPasteInjector.platformPasteChord(false)
        )

        val watchedMac = QInputPasteInjector.pasteWatchedSet(true)
        assertTrue(QInputKey.MOD_LEFT_CONTROL in watchedMac)
        assertTrue(QInputKey.MOD_LEFT_META !in watchedMac)
        val watchedWin = QInputPasteInjector.pasteWatchedSet(false)
        assertTrue(QInputKey.MOD_LEFT_META in watchedWin)
        assertTrue(QInputKey.MOD_LEFT_CONTROL !in watchedWin)
        assertTrue(QInputKey.V in watchedWin)
    }

    /**
     * Both sides of every contaminating modifier are watched, and the chord's own modifier is
     * exempt on *both* sides (a held right Ctrl is as harmless as a held left Ctrl).
     */
    @Test
    fun `paste watches both sides and exempts the whole chord modifier family`() = runTest {
        val windows = QInputPasteInjector.pasteWatchedSet(isMac = false)
        for (usage in listOf(
            QInputKey.MOD_LEFT_SHIFT, QInputKey.MOD_RIGHT_SHIFT,
            QInputKey.MOD_LEFT_ALT, QInputKey.MOD_RIGHT_ALT,
            QInputKey.MOD_LEFT_META, QInputKey.MOD_RIGHT_META
        )) {
            assertTrue(usage in windows, "windows paste must watch ${Integer.toHexString(usage)}")
        }
        assertTrue(QInputKey.MOD_LEFT_CONTROL !in windows)
        assertTrue(QInputKey.MOD_RIGHT_CONTROL !in windows, "the right-hand Ctrl is equally harmless")
        assertTrue(QInputKey.V in windows)

        val mac = QInputPasteInjector.pasteWatchedSet(isMac = true)
        assertTrue(QInputKey.MOD_LEFT_CONTROL in mac)
        assertTrue(QInputKey.MOD_RIGHT_CONTROL in mac, "a held right Ctrl contaminates Cmd+V")
        assertTrue(QInputKey.MOD_LEFT_META !in mac)
        assertTrue(QInputKey.MOD_RIGHT_META !in mac)
    }

    /** A held right-hand modifier must block paste exactly as the left-hand one does. */
    @Test
    fun `right-side modifiers block paste until release`() = runTest {
        for (held in listOf(
            QInputKey.MOD_RIGHT_SHIFT,
            QInputKey.MOD_RIGHT_ALT,
            QInputKey.MOD_RIGHT_META
        )) {
            val backend = FakeGlobalInputBackend().apply {
                supportsInjection = true
                keyDown[held] = true
            }
            val injector = QInputPasteInjector(
                backend = { backend },
                clipboardWrite = {},
                logger = RecordingLogger(),
                fallback = { error("must not fall back") },
                isMac = false
            )

            var done: Boolean? = null
            val job = launch { done = injector.injectPaste("hello") }
            advanceTimeBy(500)
            assertNull(done, "a held $held must block paste")
            assertTrue(backend.chords.isEmpty())

            backend.keyDown.clear()
            advanceUntilIdle()
            job.join()
            assertEquals(true, done)
            assertEquals(1, backend.chords.size)
        }
    }

    /** A held right Ctrl is harmless for a Ctrl chord, exactly like a held left Ctrl. */
    @Test
    fun `right ctrl held does not block windows paste`() = runTest {
        val (injector, backend) = injector()
        backend.supportsInjection = true
        backend.keyDown[QInputKey.MOD_RIGHT_CONTROL] = true

        assertTrue(injector.injectPaste("hello"))
        advanceUntilIdle()
        assertEquals(1, backend.chords.size)
    }

    /**
     * A backend with injection but no physical key-state query cannot prove neutralization.
     * The wait is skipped rather than stalling every paste into a timeout, and injection still
     * happens; this is the conservative behavior for such a backend.
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
        val injector = QInputPasteInjector(
            backend = { backend },
            clipboardWrite = { written += it },
            logger = RecordingLogger(),
            fallback = { error("must not fall back") },
            isMac = false
        )

        assertTrue(injector.injectPaste("hello"))
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(listOf("hello"), written)
        assertEquals(1, backend.chords.size)
    }
}
