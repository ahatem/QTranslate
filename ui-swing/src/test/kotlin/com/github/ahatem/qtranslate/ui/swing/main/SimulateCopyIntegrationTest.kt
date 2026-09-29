package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.ui.swing.main.input.CopyInjector
import com.github.ahatem.qtranslate.ui.swing.main.input.FakeGlobalInputBackend
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalInputBackend
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalInputEvent
import com.github.ahatem.qtranslate.ui.swing.main.input.InputRuntimeState
import com.github.ahatem.qtranslate.ui.swing.main.input.KeyClass
import com.github.ahatem.qtranslate.ui.swing.main.input.NativeInjectionStatus
import com.github.ahatem.qtranslate.ui.swing.main.input.QInputCopyInjector
import com.github.ahatem.qtranslate.ui.swing.main.input.RobotCopyInjector
import com.github.ahatem.qtranslate.ui.swing.main.input.RobotKeyDriver
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.FakeChangeMonitor
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingLogger
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.SelectionCapture
import io.github.ahatem.qinput.QInputException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end regression coverage for the exactly-one-Copy-attempt contract, driven through the
 * *real* [MainGlobalKeyListener.simulateCopy] (private, exercised only via the Double Ctrl ->
 * capture -> Copy path below) and a *real* [SelectionCapture] and [QInputCopyInjector], not
 * isolated unit doubles for those two. Only [RobotCopyInjector]'s own [RobotKeyDriver] is faked,
 * because a real one would physically touch the developer's keyboard.
 *
 * This is the regression guard for the bug `simulateCopy()` used to have: it called
 * `RobotCopyInjector(logger).injectCopy()` itself whenever the configured injector returned
 * `false`, so a bare `RobotCopyInjector` failing (the default before native is available) was
 * followed by a *second*, unrelated Robot attempt. [MainGlobalKeyListener.simulateCopy] must now
 * invoke the configured [CopyInjector] exactly once and let it own its own complete attempt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulateCopyIntegrationTest {

    private fun ctrlKey(pressed: Boolean, timestampMs: Long) =
        GlobalInputEvent.Key(KeyClass.CONTROL, 162, pressed, injected = false, repeat = false, timestampMs = timestampMs)

    private fun showMainWindowBinding() =
        HotkeyBinding(HotkeyAction.SHOW_MAIN_WINDOW, scope = HotkeyScope.GLOBAL)

    /** Builds a listener wired to a real [SelectionCapture], and triggers one Copy via Double Ctrl. */
    private inner class Harness(scope: kotlinx.coroutines.CoroutineScope) {
        val backend = FakeGlobalInputBackend()
        var shows = 0

        val listener = MainGlobalKeyListener(
            scope = scope,
            logger = RecordingLogger(),
            onShowApp = { shows++ },
            onShowQuickTranslate = {},
            onListenToText = {},
            onOpenSnippingTool = {},
            onReplaceWithTranslation = {},
            onCycleTargetLanguage = {},
            backendFactory = { backend },
            selectionCaptureFactory = { _, _, simulateCopy, logger ->
                SelectionCapture(
                    RecordingClipboard("original"),
                    FakeChangeMonitor(),
                    simulateCopy,
                    logger
                )
            }
        )

        /** Emits the physical Double Ctrl tap pair that dispatches SHOW_MAIN_WINDOW. */
        fun emitDoubleCtrl() {
            listener.updateRuntimeState(InputRuntimeState(bindings = listOf(showMainWindowBinding())))
            backend.emit(ctrlKey(true, 1000L))
            backend.emit(ctrlKey(false, 1000L))
            backend.emit(ctrlKey(true, 1100L))
            backend.emit(ctrlKey(false, 1100L))
        }
    }

    /** Records [RobotKeyDriver] construction attempts and fails every one, deterministically. */
    private class FailingDriverFactory : () -> RobotKeyDriver {
        var calls = 0
        override fun invoke(): RobotKeyDriver {
            calls++
            throw RuntimeException("no display in test environment")
        }
    }

    /**
     * Counts `sendChord` attempts regardless of outcome. [FakeGlobalInputBackend]'s own `chords`
     * list only records calls that return normally; a scripted [FakeGlobalInputBackend.chordThrows]
     * throws before appending, so a wrapper is needed to count "exactly one native attempt" when
     * that attempt is scripted to fail.
     */
    private class CountingBackend(private val delegate: GlobalInputBackend) : GlobalInputBackend by delegate {
        var attempts = 0
        override fun sendChord(modifiers: List<Int>, key: Int): Boolean {
            attempts++
            return delegate.sendChord(modifiers, key)
        }
    }

    @Test
    fun `default Robot path fails with exactly one Robot attempt`() = runTest {
        val harness = Harness(this)
        val failingDriver = FailingDriverFactory()
        // Initialize normally (dispatch requires it), then install a bare RobotCopyInjector,
        // exactly the configuration in place before native becomes available.
        harness.listener.initialize()
        harness.listener.copyInjector = RobotCopyInjector(RecordingLogger(), driverFactory = failingDriver)

        harness.emitDoubleCtrl()
        advanceUntilIdle()

        assertEquals(1, failingDriver.calls, "exactly one Robot attempt, never a second retry on failure")
    }

    @Test
    fun `native retry-safe failure yields exactly one native attempt and one Robot attempt`() = runTest {
        val harness = Harness(this)
        harness.backend.supportsInjection = true
        harness.backend.chordThrows = QInputException("bad", NativeInjectionStatus.INVALID_ARGUMENT)
        val counting = CountingBackend(harness.backend)
        var robotAttempts = 0
        harness.listener.initialize()
        // Installed after initialize(), which wires the production QInputCopyInjector: replace it
        // with a real QInputCopyInjector pointed at the same fake backend, but with a counting,
        // non-Robot fallback so no test ever constructs a real java.awt.Robot.
        harness.listener.copyInjector = QInputCopyInjector(
            backend = { counting },
            logger = RecordingLogger(),
            fallback = { robotAttempts++; true },
        )

        harness.emitDoubleCtrl()
        advanceUntilIdle()

        assertEquals(1, counting.attempts, "exactly one native attempt")
        assertEquals(1, robotAttempts, "a retry-safe native failure must fall back exactly once")
    }

    @Test
    fun `native uncertain failure yields exactly one native attempt and zero Robot attempts`() = runTest {
        val harness = Harness(this)
        harness.backend.supportsInjection = true
        harness.backend.chordThrows = QInputException("partial SendInput", NativeInjectionStatus.INJECTION_UNCERTAIN)
        val counting = CountingBackend(harness.backend)
        var robotAttempts = 0
        harness.listener.initialize()
        harness.listener.copyInjector = QInputCopyInjector(
            backend = { counting },
            logger = RecordingLogger(),
            fallback = { robotAttempts++; true },
        )

        harness.emitDoubleCtrl()
        advanceUntilIdle()

        assertEquals(1, counting.attempts, "exactly one native attempt")
        assertEquals(0, robotAttempts, "an uncertain native outcome must never fall back to Robot")
    }

    @Test
    fun `native success yields one native attempt and zero Robot attempts`() = runTest {
        val harness = Harness(this)
        harness.backend.supportsInjection = true
        val counting = CountingBackend(harness.backend)
        var robotAttempts = 0
        harness.listener.initialize()
        harness.listener.copyInjector = QInputCopyInjector(
            backend = { counting },
            logger = RecordingLogger(),
            fallback = { robotAttempts++; true },
        )

        harness.emitDoubleCtrl()
        advanceUntilIdle()

        assertEquals(1, counting.attempts, "exactly one native attempt")
        assertEquals(0, robotAttempts, "a successful native chord must never fall back")
    }
}
