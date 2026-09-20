package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.ui.swing.main.input.CopyInjector
import com.github.ahatem.qtranslate.ui.swing.main.input.FakeGlobalInputBackend
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalInputEvent
import com.github.ahatem.qtranslate.ui.swing.main.input.InputRuntimeState
import com.github.ahatem.qtranslate.ui.swing.main.input.KeyClass
import com.github.ahatem.qtranslate.ui.swing.main.input.MouseButtonId
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.ClipboardChangeMonitor
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.FakeChangeMonitor
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingLogger
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.SelectionCapture
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.SystemClipboard
import io.github.ahatem.qinput.QInputKey
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * QInput-backed listener behavior against a fake backend: registration mapping, dynamic
 * updates, enable/disable, pause, hotkey dispatch, Double Ctrl adapter wiring, mouse
 * gestures, and lifecycle. No native hook, no Robot, no real clipboard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainGlobalKeyListenerTest {

    /**
     * A registration token no native registration can hold: tokens are minted from 1 upwards, so
     * this stands for "an event whose registration is not accepted".
     */
    private val UNACCEPTED_TOKEN = Long.MIN_VALUE

    private fun bindings(vararg bindings: HotkeyBinding) = bindings.toList()

    private fun global(action: HotkeyAction, keyCode: Int, modifiers: Int) =
        HotkeyBinding(action, keyCode = keyCode, modifiers = modifiers, scope = HotkeyScope.GLOBAL)

    private fun showMainWindowBinding() =
        HotkeyBinding(HotkeyAction.SHOW_MAIN_WINDOW, scope = HotkeyScope.GLOBAL)

    private fun ctrlKey(pressed: Boolean, timestampMs: Long = 0L) =
        GlobalInputEvent.Key(KeyClass.CONTROL, 162, pressed, injected = false, repeat = false, timestampMs = timestampMs)

    private inner class Harness(
        private val scope: TestScope,
        val backend: FakeGlobalInputBackend = FakeGlobalInputBackend(),
        val logger: RecordingLogger = RecordingLogger(),
        var shows: Int = 0,
        var ocrCalls: Int = 0,
        val presses: MutableList<Point> = mutableListOf(),
        var selections: Int = 0,
        val imageQueries: MutableList<String> = mutableListOf(),
        val listened: MutableList<String> = mutableListOf(),
        val dictionaryQueries: MutableList<String> = mutableListOf(),
        val replaced: MutableList<String> = mutableListOf(),
        val quickQueries: MutableList<String> = mutableListOf(),
        var cycles: Int = 0,
        var translates: Int = 0,
        captureFactory: ((
            com.github.ahatem.qtranslate.ui.swing.shared.clipboard.SystemClipboard,
            com.github.ahatem.qtranslate.ui.swing.shared.clipboard.ClipboardChangeMonitor,
            suspend () -> Boolean,
            com.github.ahatem.qtranslate.api.core.Logger,
        ) -> com.github.ahatem.qtranslate.ui.swing.shared.clipboard.SelectionCapture)? = null,
    ) {
        val listener = MainGlobalKeyListener(
            scope = scope, logger = logger,
            onShowApp = { shows++ }, onShowQuickTranslate = { quickQueries += it },
            onListenToText = { listened += it },
            onOpenSnippingTool = { ocrCalls++ }, onReplaceWithTranslation = { replaced += it },
            onCycleTargetLanguage = { cycles++ },
            onShowDictionary = { dictionaryQueries += it },
            onShowImages = { imageQueries += it },
            onSelectionDetected = { _, _ -> selections++ },
            onPointerPressed = { presses += it },
            onTranslate = { translates++ },
            backendFactory = { backend },
            selectionCaptureFactory = captureFactory
                ?: { _, _, simulateCopy, captureLogger ->
                    // Fake clipboard observation by default: listener routing is the subject,
                    // not the platform clipboard (whose sequence may read 0 in test sessions).
                    com.github.ahatem.qtranslate.ui.swing.shared.clipboard.SelectionCapture(
                        com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingClipboard("original"),
                        com.github.ahatem.qtranslate.ui.swing.shared.clipboard.FakeChangeMonitor(),
                        simulateCopy,
                        captureLogger
                    )
                }
        )

        init {
            // Never synthesize real keypresses from unit tests: the injector wiring itself
            // is covered by a dedicated test with a recording fake.
            listener.copyInjector = CopyInjector { true }
        }

        fun set(state: InputRuntimeState) {
            applied = state
            listener.updateRuntimeState(state)
        }

        /** Last state pushed through [set], for building follow-up transitions. */
        var applied: InputRuntimeState = InputRuntimeState()
            private set

        fun setPaused(paused: Boolean) = listener.setPaused(paused)

        /**
         * Fires the registration token currently accepted for [action], exactly as the native
         * backend would when the shortcut is pressed.
         *
         * When nothing is accepted for the action (disabled, paused, or never registered), a
         * token no registration can hold is emitted instead: the physical registration would not
         * exist either, so the assertion is that nothing dispatches.
         */
        fun emitHotkey(action: HotkeyAction) {
            backend.emit(GlobalInputEvent.Hotkey(listener.acceptedTokenFor(action) ?: UNACCEPTED_TOKEN))
        }
    }

    private fun TestScope.harness() = Harness(this)

    /**
     * Harness whose selection capture succeeds with "word", so a dispatched hotkey is observable.
     * The default harness times out unconfirmed (CaptureFailure.COPY_UNCONFIRMED), which strict
     * actions deliberately do not dispatch, useful for asserting "nothing happened".
     */
    private fun TestScope.imageHarness() = Harness(this, captureFactory = successCapture("word"))

    @Test
    fun `initialize registers enabled global bindings only`() = runTest {
        val harness = harness()
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.SHOW_QUICK_TRANSLATE, KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK),
                    HotkeyBinding(
                        HotkeyAction.LISTEN_TO_TEXT, keyCode = KeyEvent.VK_E,
                        modifiers = InputEvent.CTRL_DOWN_MASK, isEnabled = false, scope = HotkeyScope.GLOBAL
                    ),
                    HotkeyBinding(
                        HotkeyAction.CYCLE_TARGET_LANGUAGE, keyCode = KeyEvent.VK_L,
                        modifiers = InputEvent.CTRL_DOWN_MASK, scope = HotkeyScope.LOCAL
                    ),
                    // SHOW_MAIN_WINDOW has no key binding: double-Ctrl path, never registered.
                    showMainWindowBinding()
                )
            )
        )
        harness.listener.initialize()
        advanceUntilIdle()

        val accelerators = harness.backend.lastApplied().map { it.accelerator }
        assertEquals(listOf("control+KeyQ"), accelerators)
    }

    @Test
    fun `updateRuntimeState re-applies the native set`() = runTest {
        val harness = harness()
        harness.listener.initialize()
        harness.set(
            InputRuntimeState(
                bindings = bindings(global(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK))
            )
        )
        advanceUntilIdle()

        assertEquals(listOf("control+KeyD"), harness.backend.lastApplied().map { it.accelerator })
    }

    @Test
    fun `unsupported keys and duplicates are skipped without losing the rest`() = runTest {
        val harness = harness()
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.SHOW_QUICK_TRANSLATE, KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK),
                    // VK_CONTEXT_MENU has no accelerator mapping: must fail explicitly, not misregister.
                    global(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_CONTEXT_MENU, 0),
                    // Same accelerator as the first binding: second one loses, first survives.
                    global(HotkeyAction.LISTEN_TO_TEXT, KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        harness.listener.initialize()
        advanceUntilIdle()

        assertEquals(listOf("control+KeyQ"), harness.backend.lastApplied().map { it.accelerator })
        // The registration carries the token the ledger accepted, and nothing for the two
        // bindings that were skipped.
        val applied = harness.backend.lastApplied().single()
        assertEquals(harness.listener.acceptedTokenFor(HotkeyAction.SHOW_QUICK_TRANSLATE), applied.id)
        assertEquals(null, harness.listener.acceptedTokenFor(HotkeyAction.SHOW_DICTIONARY))
        assertEquals(null, harness.listener.acceptedTokenFor(HotkeyAction.LISTEN_TO_TEXT))
        assertEquals(2, harness.logger.warns.count { it.startsWith("Skipping") })
    }

    @Test
    fun `backend failure preserves the previous working set`() = runTest {
        val harness = harness()
        harness.set(
            InputRuntimeState(
                bindings = bindings(global(HotkeyAction.SHOW_QUICK_TRANSLATE, KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK))
            )
        )
        harness.listener.initialize()
        advanceUntilIdle()
        assertEquals(1, harness.backend.lastApplied().size)

        harness.backend.failNextApplies = 1
        harness.set(
            harness.applied.copy(
                bindings = bindings(global(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK))
            )
        )
        advanceUntilIdle()

        // The failing update threw, then the previous set was written back: two writes,
        // the last one restoring the working set instead of leaving zero hotkeys.
        assertEquals(2, harness.backend.applied.size)
        assertEquals(listOf("control+KeyQ"), harness.backend.lastApplied().map { it.accelerator })
    }

    @Test
    fun `boolean-only toggle clears and restores registrations`() = runTest {
        // Reproduces the real upstream transition: the binding list is identical, only the
        // enabled flag flips (settings checkbox save / tray toggle). The old list-watching
        // collector never fired for this; the unified state must reconcile regardless.
        val harness = harness()
        val list = bindings(
            showMainWindowBinding(),
            global(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK)
        )
        harness.set(InputRuntimeState(bindings = list))
        harness.listener.initialize()
        advanceUntilIdle()
        assertEquals(listOf("control+KeyI"), harness.backend.lastApplied().map { it.accelerator })
        assertEquals(Triple(true, true, false), harness.backend.rawMask)

        harness.set(harness.applied.copy(globalHotkeysEnabled = false))
        advanceUntilIdle()
        assertTrue(harness.backend.lastApplied().isEmpty())
        assertEquals(Triple(false, true, false), harness.backend.rawMask)

        // Double Ctrl is dead while disabled.
        harness.backend.emit(ctrlKey(true, 1000L))
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        advanceUntilIdle()
        assertEquals(0, harness.shows)

        harness.set(harness.applied.copy(globalHotkeysEnabled = true))
        advanceUntilIdle()
        assertEquals(listOf("control+KeyI"), harness.backend.lastApplied().map { it.accelerator })
        assertEquals(Triple(true, true, false), harness.backend.rawMask)

        harness.emitHotkey(HotkeyAction.OPEN_OCR)
        harness.backend.emit(ctrlKey(true, 2000L))
        harness.backend.emit(ctrlKey(false, 2000L))
        harness.backend.emit(ctrlKey(true, 2100L))
        harness.backend.emit(ctrlKey(false, 2100L))
        advanceUntilIdle()
        assertEquals(1, harness.ocrCalls)
        assertEquals(1, harness.shows)
    }

    @Test
    fun `pause suppresses registrations while raw keyboard stays subscribed`() = runTest {
        val harness = harness()
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    showMainWindowBinding(),
                    global(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        harness.listener.initialize()
        advanceUntilIdle()

        harness.setPaused(true)
        advanceUntilIdle()
        assertTrue(harness.backend.lastApplied().isEmpty())
        // Raw observation follows the configured switch, not the pause overlay.
        assertEquals(Triple(true, true, false), harness.backend.rawMask)

        // Registered shortcut is suppressed while paused.
        harness.emitHotkey(HotkeyAction.OPEN_OCR)
        advanceUntilIdle()
        assertEquals(0, harness.ocrCalls)

        harness.setPaused(false)
        advanceUntilIdle()
        assertEquals(listOf("control+KeyI"), harness.backend.lastApplied().map { it.accelerator })
        harness.emitHotkey(HotkeyAction.OPEN_OCR)
        advanceUntilIdle()
        assertEquals(1, harness.ocrCalls)
    }

    @Test
    fun `tap then pause resume tap does not trigger`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.backend.emit(ctrlKey(true, 1000L))
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.setPaused(true)
        harness.setPaused(false)
        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        advanceUntilIdle()
        assertEquals(0, harness.shows)
    }

    @Test
    fun `two taps during pause do not trigger after resume`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.setPaused(true)
        harness.backend.emit(ctrlKey(true, 1000L))
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        harness.setPaused(false)
        advanceUntilIdle()
        assertEquals(0, harness.shows)
    }

    @Test
    fun `tap during pause does not combine with tap after resume`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.setPaused(true)
        harness.backend.emit(ctrlKey(true, 1000L))
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.setPaused(false)
        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        advanceUntilIdle()
        assertEquals(0, harness.shows)
    }

    @Test
    fun `taps around pause activity do not combine after resume`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.backend.emit(ctrlKey(true, 1000L))
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.setPaused(true)
        harness.backend.emit(ctrlKey(true, 1050L))
        harness.backend.emit(ctrlKey(false, 1050L))
        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        harness.setPaused(false)
        harness.backend.emit(ctrlKey(true, 1200L))
        harness.backend.emit(ctrlKey(false, 1200L))
        advanceUntilIdle()
        assertEquals(0, harness.shows)
    }

    @Test
    fun `two fresh taps after resume trigger exactly once`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.backend.emit(ctrlKey(true, 1000L))
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.setPaused(true)
        harness.setPaused(false)
        harness.backend.emit(ctrlKey(true, 2000L))
        harness.backend.emit(ctrlKey(false, 2000L))
        harness.backend.emit(ctrlKey(true, 2100L))
        harness.backend.emit(ctrlKey(false, 2100L))
        advanceUntilIdle()
        assertEquals(1, harness.shows)
    }

    @Test
    fun `hotkey press dispatches the mapped action`() = runTest {
        val harness = harness()
        harness.set(
            InputRuntimeState(
                bindings = bindings(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK))
            )
        )
        harness.listener.initialize()
        advanceUntilIdle()

        harness.emitHotkey(HotkeyAction.OPEN_OCR)
        advanceUntilIdle()
        assertEquals(1, harness.ocrCalls)

        // Unknown ids are ignored.
        harness.backend.emit(GlobalInputEvent.Hotkey(9999L))
        advanceUntilIdle()
        assertEquals(1, harness.ocrCalls)
    }

    @Test
    fun `hotkey does not fire while hotkeys are disabled`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(globalHotkeysEnabled = false))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.emitHotkey(HotkeyAction.OPEN_OCR)
        advanceUntilIdle()
        assertEquals(0, harness.ocrCalls)
    }

    @Test
    fun `raw ctrl pair through the adapter opens the app`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.backend.emit(ctrlKey(true, 1000L))
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.backend.emit(ctrlKey(true, 1200L))
        harness.backend.emit(ctrlKey(false, 1200L))
        advanceUntilIdle()

        assertEquals(1, harness.shows)
    }

    @Test
    fun `orphan ctrl release through the adapter never fires`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        // Ditto-style synthetic key-ups with no observed press.
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.backend.emit(ctrlKey(false, 1100L))
        advanceUntilIdle()
        assertTrue(harness.shows == 0)
    }

    @Test
    fun `injected ctrl C through the adapter never fires`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        fun injected(pressed: Boolean) =
            GlobalInputEvent.Key(KeyClass.CONTROL, 162, pressed, injected = true, repeat = false, timestampMs = 1000L)
        harness.backend.emit(injected(true))
        harness.backend.emit(
            GlobalInputEvent.Key(KeyClass.OTHER, 67, true, injected = true, repeat = false, timestampMs = 1000L)
        )
        harness.backend.emit(injected(false))
        advanceUntilIdle()
        assertTrue(harness.shows == 0)
    }

    @Test
    fun `primary press always notifies and drag release without icon does nothing`() = runTest {
        val harness = harness()
        harness.listener.initialize()
        advanceUntilIdle()

        harness.backend.emit(GlobalInputEvent.MouseButton(MouseButtonId.LEFT, true, Point(0, 0)))
        harness.backend.emit(GlobalInputEvent.MouseMove(Point(30, 30)))
        harness.backend.emit(GlobalInputEvent.MouseButton(MouseButtonId.LEFT, false, Point(30, 30)))
        advanceUntilIdle()

        assertEquals(listOf(Point(0, 0)), harness.presses)
        assertEquals(0, harness.selections)
    }

    @Test
    fun `selection icon disabled keeps the motion mask off`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        assertEquals(Triple(true, true, false), harness.backend.rawMask)

        harness.set(harness.applied.copy(selectionIconEnabled = true))
        assertEquals(Triple(true, true, true), harness.backend.rawMask)
    }

    @Test
    fun `initialize once update stop double close and silence after close`() = runTest {
        var creations = 0
        val backend = FakeGlobalInputBackend()
        val listener = MainGlobalKeyListener(
            scope = this, logger = RecordingLogger(),
            onShowApp = {}, onShowQuickTranslate = {}, onListenToText = {},
            onOpenSnippingTool = {}, onReplaceWithTranslation = {}, onCycleTargetLanguage = {},
            backendFactory = { creations++; backend }
        )
        listener.initialize()
        listener.initialize()
        assertEquals(1, creations)

        var ocrCalls = 0
        val listener2 = MainGlobalKeyListener(
            scope = this, logger = RecordingLogger(),
            onShowApp = {}, onShowQuickTranslate = {}, onListenToText = {},
            onOpenSnippingTool = { ocrCalls++ }, onReplaceWithTranslation = {},
            onCycleTargetLanguage = {},
            backendFactory = { backend }
        )
        listener2.initialize()
        listener2.shutdown()
        listener2.shutdown()
        // The second shutdown is a no-op: the backend is closed exactly once.
        assertEquals(1, backend.closeCount)

        backend.emit(GlobalInputEvent.Hotkey(UNACCEPTED_TOKEN))
        advanceUntilIdle()
        assertEquals(0, ocrCalls)
    }

    @Test
    fun `partial initialization failure allows retry`() = runTest {
        var calls = 0
        val backend = FakeGlobalInputBackend()
        val listener = MainGlobalKeyListener(
            scope = this, logger = RecordingLogger(),
            onShowApp = {}, onShowQuickTranslate = {}, onListenToText = {},
            onOpenSnippingTool = {}, onReplaceWithTranslation = {}, onCycleTargetLanguage = {},
            backendFactory = {
                calls++
                if (calls == 1) throw RuntimeException("no backend yet")
                backend
            }
        )
        listener.initialize()
        assertEquals(1, calls)
        listener.initialize()
        assertEquals(2, calls)
        assertEquals(1, backend.applied.size)
    }

    @Test
    fun `tap armed before disable cannot complete after re-enable`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.backend.emit(ctrlKey(true, 1000L))
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.set(harness.applied.copy(globalHotkeysEnabled = false))
        harness.set(harness.applied.copy(globalHotkeysEnabled = true))
        // Without the reset-on-disable this second tap would pair with the armed first.
        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        advanceUntilIdle()
        assertEquals(0, harness.shows)
    }

    @Test
    fun `disable before initialize is honored at startup`() = runTest {
        val harness = harness()
        harness.set(
            InputRuntimeState(
                bindings = bindings(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK)),
                globalHotkeysEnabled = false
            )
        )
        harness.listener.initialize()
        advanceUntilIdle()

        assertTrue(harness.backend.lastApplied().isEmpty())
        assertEquals(Triple(false, true, false), harness.backend.rawMask)
    }

    @Test
    fun `binding updates work after disable and re-enable`() = runTest {
        val harness = harness()
        harness.listener.initialize()
        harness.set(harness.applied.copy(globalHotkeysEnabled = false))
        harness.set(
            harness.applied.copy(
                bindings = bindings(global(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK))
            )
        )
        advanceUntilIdle()
        assertTrue(harness.backend.lastApplied().isEmpty())

        harness.set(harness.applied.copy(globalHotkeysEnabled = true))
        advanceUntilIdle()
        assertEquals(listOf("control+KeyD"), harness.backend.lastApplied().map { it.accelerator })
    }

    @Test
    fun `double ctrl binding opt-out drops the keyboard subscription`() = runTest {
        val harness = harness()
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    HotkeyBinding(HotkeyAction.SHOW_MAIN_WINDOW, scope = HotkeyScope.GLOBAL, isDoubleCtrlEnabled = false)
                )
            )
        )
        harness.listener.initialize()
        advanceUntilIdle()

        assertEquals(Triple(false, true, false), harness.backend.rawMask)
    }

    @Test
    fun `self-injected ctrl pair can never trigger double ctrl`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        fun self(pressed: Boolean) = GlobalInputEvent.Key(
            KeyClass.CONTROL, 162, pressed, injected = true, repeat = false,
            selfInjected = true, timestampMs = 1000L
        )
        harness.backend.emit(self(true))
        harness.backend.emit(self(false))
        harness.backend.emit(self(true))
        harness.backend.emit(self(false))
        advanceUntilIdle()
        assertEquals(0, harness.shows)

        // Genuine input right after still works: self-injection left no trace.
        harness.backend.emit(ctrlKey(true, 2000L))
        harness.backend.emit(ctrlKey(false, 2000L))
        harness.backend.emit(ctrlKey(true, 2100L))
        harness.backend.emit(ctrlKey(false, 2100L))
        advanceUntilIdle()
        assertEquals(1, harness.shows)
    }

    // ---------------------------------------------------------------------------------------
    // QInput's own Copy/Paste must never feed Double Ctrl. On Windows and macOS the native layer
    // reliably tags its own injected transitions (`dwExtraInfo` / `EVENT_SOURCE_USER_DATA`) and
    // still delivers them as `selfInjected = true`; these tests express the contract the Kotlin
    // consumer must honor given that flag: a self-injected pair must never arm or complete a tap,
    // whatever else is happening around it in time.
    //
    // On X11 the native layer takes a different route to the same outcome (see qinput's
    // `crate::record_cycle` and `platform::linux::handle_inject`): it disables the XRecord
    // context, confirms via an observed `XRecordEndOfData` that recording is actually off, only
    // then injects, and re-enables afterward, so QInput's own transitions are never observed at
    // all, `selfInjected` is never set for them, and no classification is attempted for anything
    // else either (X11 cannot tell physical from third-party synthetic input, so it never claims
    // to). These tests still apply to X11 as a defensive/general contract check: a correct
    // backend must never emit a self-injected pair that arms a false Double Ctrl, but the actual
    // X11 protection against its own injections happens by those events never being delivered in
    // the first place, not by this flag.
    // ---------------------------------------------------------------------------------------

    private fun selfInjectedCtrl(pressed: Boolean, timestampMs: Long) = GlobalInputEvent.Key(
        KeyClass.CONTROL, 162, pressed, injected = true, repeat = false,
        selfInjected = true, timestampMs = timestampMs
    )

    private fun Harness.emitSelfInjectedCtrlPair(atMs: Long) {
        backend.emit(selfInjectedCtrl(true, atMs))
        backend.emit(selfInjectedCtrl(false, atMs))
    }

    @Test
    fun `one QInput Ctrl+C does not arm a false double ctrl sequence`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.emitSelfInjectedCtrlPair(1000L)
        advanceUntilIdle()
        assertEquals(0, harness.shows)

        // If the injection had armed a phantom first tap, this lone genuine tap would appear
        // to complete a pair. It must not: the injection must have armed nothing at all.
        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        advanceUntilIdle()
        assertEquals(0, harness.shows, "a single genuine tap after an injection only arms, never fires")
    }

    @Test
    fun `two QInput copy paste chords close together never open the app via double ctrl`() = runTest {
        // Models REPLACE_WITH_TRANSLATION: a Copy chord immediately followed by a Paste chord,
        // both QInput's own, well inside the double-ctrl threshold.
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.emitSelfInjectedCtrlPair(1000L)
        harness.emitSelfInjectedCtrlPair(1050L)
        advanceUntilIdle()
        assertEquals(0, harness.shows)
    }

    @Test
    fun `qinput injection immediately before a genuine double ctrl causes no phantom activation`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.emitSelfInjectedCtrlPair(1000L)
        harness.backend.emit(ctrlKey(true, 1050L))
        harness.backend.emit(ctrlKey(false, 1050L))
        harness.backend.emit(ctrlKey(true, 1150L))
        harness.backend.emit(ctrlKey(false, 1150L))
        advanceUntilIdle()

        // Exactly one activation from the genuine pair: not zero (the injection wrongly
        // blocking real input) and not more than one (a phantom third tap from the injection).
        assertEquals(1, harness.shows)
    }

    @Test
    fun `genuine double ctrl works again after injection protection ends`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.emitSelfInjectedCtrlPair(1000L)
        advanceUntilIdle()
        assertEquals(0, harness.shows)

        harness.backend.emit(ctrlKey(true, 5000L))
        harness.backend.emit(ctrlKey(false, 5000L))
        harness.backend.emit(ctrlKey(true, 5100L))
        harness.backend.emit(ctrlKey(false, 5100L))
        advanceUntilIdle()
        assertEquals(1, harness.shows, "double ctrl must work normally long after an injection")
    }

    @Test
    fun `physical ctrl held across a self-injected pair still completes one genuine tap`() = runTest {
        // Models the boundary the native layer's suppression window resolves: a physical Ctrl
        // press-and-release straddles a QInput injection in time. QInput's own transitions are
        // always reported self-injected regardless (the required invariant), so a correctly
        // behaving native layer never lets this interleaving turn its own Ctrl into a genuine
        // tap; here we assert the self-injected pair also does not corrupt the physical
        // press/release pair it has nothing to do with.
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.backend.emit(ctrlKey(true, 1000L))
        harness.emitSelfInjectedCtrlPair(1010L)
        harness.backend.emit(ctrlKey(false, 1020L))
        advanceUntilIdle()
        // One physical tap: armed, not fired (a double tap needs a second one).
        assertEquals(0, harness.shows)

        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        advanceUntilIdle()
        assertEquals(1, harness.shows, "the physical tap pair must still combine normally")
    }

    @Test
    fun `failed native injection leaves no stuck state for a later genuine double ctrl`() = runTest {
        // Simulates the native side refusing or failing an injection (for instance the X11
        // backend's XSync-confirmed drain never completing): nothing about that failure may
        // leave the detector, or any suppression state upstream of it, stuck. Double Ctrl
        // must work immediately afterward exactly as if the injection had never been attempted.
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        harness.listener.copyInjector = CopyInjector { false }
        advanceUntilIdle()

        harness.backend.emit(ctrlKey(true, 1000L))
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        advanceUntilIdle()
        assertEquals(1, harness.shows)
    }

    @Test
    fun `shutdown right after an injection stays safe and dispatches nothing later`() = runTest {
        val harness = harness()
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.emitSelfInjectedCtrlPair(1000L)
        harness.listener.shutdown()
        advanceUntilIdle()

        // A late event arriving after shutdown (as a straggling native callback might) must
        // not dispatch or throw.
        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        advanceUntilIdle()
        assertEquals(0, harness.shows)
    }

    @Test
    fun `capture prefers the injected copy path when available`() = runTest {
        // Fake capture with working monitor: the real one reports sequence 0 in this
        // environment, which correctly aborts before Copy. The injector wiring is the
        // subject here, not clipboard observation.
        val clipboard = com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingClipboard("original")
        val monitor = com.github.ahatem.qtranslate.ui.swing.shared.clipboard.FakeChangeMonitor()
        val harness = Harness(
            scope = this,
            captureFactory = { _, _, simulateCopy, logger ->
                com.github.ahatem.qtranslate.ui.swing.shared.clipboard.SelectionCapture(
                    clipboard, monitor, simulateCopy, logger
                )
            }
        )
        var injections = 0
        harness.set(InputRuntimeState(bindings = bindings(showMainWindowBinding())))
        harness.listener.initialize()
        // Installed after initialize: initialize() wires the production injector.
        harness.listener.copyInjector = object : CopyInjector {
            override fun injectCopy(): Boolean {
                injections++
                return true
            }
        }
        advanceUntilIdle()

        harness.backend.emit(ctrlKey(true, 1000L))
        harness.backend.emit(ctrlKey(false, 1000L))
        harness.backend.emit(ctrlKey(true, 1100L))
        harness.backend.emit(ctrlKey(false, 1100L))
        advanceUntilIdle()

        assertEquals(1, harness.shows)
        assertEquals(1, injections)
    }

    @Test
    fun `selection and dismissal both off request no mouse observation`() = runTest {
        val harness = harness()
        harness.set(
            InputRuntimeState(
                bindings = bindings(showMainWindowBinding()),
                selectionIconEnabled = false,
                dismissOnOutsideClickEnabled = false
            )
        )
        harness.listener.initialize()
        advanceUntilIdle()

        assertEquals(Triple(true, false, false), harness.backend.rawMask)
    }

    private fun imagesBinding() = bindings(
        showMainWindowBinding(),
        global(
            HotkeyAction.SHOW_IMAGES, KeyEvent.VK_Q,
            InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
        )
    )

    private fun recordInjector(calls: MutableList<Unit>) = object : CopyInjector {
        override fun injectCopy(): Boolean {
            calls += Unit
            return true
        }
    }

    private fun successCapture(text: String): (
        SystemClipboard, ClipboardChangeMonitor, suspend () -> Boolean, Logger,
    ) -> SelectionCapture {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        return { _, _, simulateCopy, logger ->
            SelectionCapture(clipboard, monitor, {
                simulateCopy()
                monitor.current++
                clipboard.simulateExternalCopy(text)
                true
            }, logger)
        }
    }

    private fun failedCapture(): (
        SystemClipboard, ClipboardChangeMonitor, suspend () -> Boolean, Logger,
    ) -> SelectionCapture {
        val clipboard = RecordingClipboard("original").apply {
            snapshotFailure = IllegalStateException("busy")
        }
        val monitor = FakeChangeMonitor()
        return { _, _, simulateCopy, logger ->
            SelectionCapture(clipboard, monitor, simulateCopy, logger)
        }
    }

    @Test
    fun `held trigger does not inject until release`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)
        advanceUntilIdle()

        // Ctrl+Shift+Q physically held, as on a slow first press: nothing injected.
        // Keys are portable QInput usages, which is what the neutralizer queries.
        harness.backend.keyDown[QInputKey.MOD_LEFT_CONTROL] = true
        harness.backend.keyDown[QInputKey.MOD_LEFT_SHIFT] = true
        harness.backend.keyDown[QInputKey.Q] = true
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceTimeBy(500)
        assertTrue(injections.isEmpty())
        assertTrue(harness.imageQueries.isEmpty())

        harness.backend.keyDown.clear()
        advanceUntilIdle()
        assertEquals(1, injections.size)
        assertEquals(listOf("word"), harness.imageQueries)
    }

    @Test
    fun `very fast tap performs one capture`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)
        advanceUntilIdle()

        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(1, injections.size)
        assertEquals(listOf("word"), harness.imageQueries)
    }

    @Test
    fun `trigger main key held forever means no injection and no dispatch`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)
        advanceUntilIdle()

        harness.backend.keyDown[QInputKey.Q] = true
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertTrue(injections.isEmpty())
        assertTrue(harness.imageQueries.isEmpty())
        assertTrue(harness.logger.warns.any { it.contains("never released", ignoreCase = true) })
    }

    @Test
    fun `unrelated shift held prevents contaminated injection`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)
        advanceUntilIdle()

        // Only Shift held (Ctrl and Q already released): still not neutral for Ctrl+C.
        harness.backend.keyDown[QInputKey.MOD_LEFT_SHIFT] = true
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceTimeBy(500)
        assertTrue(injections.isEmpty())

        harness.backend.keyDown.clear()
        advanceUntilIdle()
        assertEquals(1, injections.size)
        assertEquals(listOf("word"), harness.imageQueries)
    }

    @Test
    fun `ctrl-only trigger proceeds while ctrl stays held`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.LISTEN_TO_TEXT, KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)
        advanceUntilIdle()

        // E released, Ctrl physically held: neutral for E, and the injector's own
        // held-modifier preservation keeps the requested Ctrl intact for Copy.
        harness.backend.keyDown[QInputKey.MOD_LEFT_CONTROL] = true
        harness.emitHotkey(HotkeyAction.LISTEN_TO_TEXT)
        advanceUntilIdle()
        assertEquals(1, injections.size)
        assertEquals(listOf("word"), harness.listened)
    }

    @Test
    fun `failed capture dispatches nothing strict`() = runTest {
        val harness = Harness(scope = this, captureFactory = failedCapture())
        harness.set(
            InputRuntimeState(
                bindings = imagesBinding() + bindings(
                    global(HotkeyAction.LISTEN_TO_TEXT, KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK),
                    global(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK),
                    global(
                        HotkeyAction.REPLACE_WITH_TRANSLATION, KeyEvent.VK_T,
                        InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
                    )
                )
            )
        )
        harness.listener.initialize()
        advanceUntilIdle()

        for (action in listOf(
            HotkeyAction.SHOW_IMAGES, HotkeyAction.LISTEN_TO_TEXT,
            HotkeyAction.SHOW_DICTIONARY, HotkeyAction.REPLACE_WITH_TRANSLATION
        )) {
            harness.emitHotkey(action)
        }
        advanceUntilIdle()
        assertTrue(harness.imageQueries.isEmpty())
        assertTrue(harness.listened.isEmpty())
        assertTrue(harness.dictionaryQueries.isEmpty())
        assertTrue(harness.replaced.isEmpty())
    }

    @Test
    fun `no selection stays silent for strict actions but lenient dialogs open empty`() = runTest {
        // Default harness capture times out unconfirmed (injector runs, nothing arrives): not
        // proven no-selection, but the dispatch layer preserves the lenient dialogs' existing
        // empty-input UX for it anyway (see MainGlobalKeyListener.INCONCLUSIVE_AS_EMPTY).
        val harness = harness()
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    showMainWindowBinding(),
                    global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
                    global(HotkeyAction.SHOW_QUICK_TRANSLATE, KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        harness.listener.initialize()
        advanceUntilIdle()

        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        harness.emitHotkey(HotkeyAction.SHOW_QUICK_TRANSLATE)
        advanceUntilIdle()
        assertTrue(harness.imageQueries.isEmpty())
        assertEquals(listOf(""), harness.quickQueries)
    }

    @Test
    fun `a known-failed copy injection stays silent for strict actions but lenient dialogs open empty`() = runTest {
        // A definite injection failure is operationally different from an unconfirmed timeout,
        // but the product-visible mapping is the same: lenient dialogs still tolerate empty
        // input, strict actions still withhold. What must stay honest is the underlying
        // CaptureResult, which SelectionCaptureTest covers directly (COPY_INJECTION_FAILED,
        // never NoUsableText).
        val harness = harness()
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    showMainWindowBinding(),
                    global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
                    global(HotkeyAction.SHOW_QUICK_TRANSLATE, KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        harness.listener.initialize()
        harness.listener.copyInjector = CopyInjector { false }
        advanceUntilIdle()

        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        harness.emitHotkey(HotkeyAction.SHOW_QUICK_TRANSLATE)
        advanceUntilIdle()
        assertTrue(harness.imageQueries.isEmpty())
        assertEquals(listOf(""), harness.quickQueries)
    }

    @Test
    fun `successful capture dispatches strict text exactly once`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("chosen"))
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(listOf("chosen"), harness.imageQueries)
    }

    @Test
    fun `neutralization precedes any injector including fallback refusal`() = runTest {
        // A refusing injector stands in for the Robot fallback: neutralization must gate it.
        val harness = harness()
        var refusals = 0
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        harness.listener.copyInjector = object : CopyInjector {
            override fun injectCopy(): Boolean {
                refusals++
                return false
            }
        }
        advanceUntilIdle()

        harness.backend.keyDown[QInputKey.MOD_LEFT_SHIFT] = true
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(0, refusals)
        // A fresh activation after release reaches the injector exactly once.
        harness.backend.keyDown.clear()
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(1, refusals)
    }

    // ---------------------------------------------------------------------------------------
    // Stale native registrations.
    //
    // The platform can refuse to release a registration, leaving it installed after QTranslate
    // stopped wanting it. Native identity must therefore be the accepted registration token, not
    // the action: these tests are the cases an action-keyed map got wrong.
    // ---------------------------------------------------------------------------------------

    /** Disabling an action retires its token; the leftover registration is not dispatchable. */
    @Test
    fun `disable leaves a refused native registration non dispatchable`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(listOf("word"), harness.imageQueries)

        // The user disables SHOW_IMAGES; the platform refuses to release Ctrl+Shift+Q.
        harness.backend.refusedUnregister += "control+shift+KeyQ"
        harness.set(
            InputRuntimeState(
                bindings = bindings(global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK).copy(isEnabled = false))
            )
        )
        advanceUntilIdle()

        // The registration is physically still there...
        assertEquals(setOf("control+shift+KeyQ"), harness.backend.leftoverAccelerators())
        // ...but its token was retired, so it dispatches nothing.
        harness.backend.emitLeftover("control+shift+KeyQ")
        advanceUntilIdle()
        assertEquals(listOf("word"), harness.imageQueries, "the retired registration must not dispatch")
        assertTrue(
            harness.listener.observedUnacceptedTokens().isNotEmpty(),
            "an unaccepted token must be observable as evidence cleanup is incomplete"
        )
        assertTrue(harness.logger.warns.any { it.contains("not an accepted registration") })
    }

    /**
     * The changed-accelerator case: the same action on a new accelerator. The stale registration
     * must not dispatch even though it represents the action that is still wanted.
     */
    @Test
    fun `changed accelerator does not let the old registration dispatch`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()
        val oldToken = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)

        // Ctrl+Shift+Q -> Ctrl+F9, and releasing the old accelerator fails.
        harness.backend.refusedUnregister += "control+shift+KeyQ"
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        advanceUntilIdle()

        val newToken = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)
        assertNotEquals(oldToken, newToken, "the replacement must get a fresh token")
        assertEquals(setOf("control+shift+KeyQ"), harness.backend.leftoverAccelerators())

        // The new accelerator works.
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(listOf("word"), harness.imageQueries)

        // The obsolete one does nothing, even though it is still installed and the action is
        // still wanted.
        harness.backend.emitLeftover("control+shift+KeyQ")
        advanceUntilIdle()
        assertEquals(
            listOf("word"),
            harness.imageQueries,
            "the superseded registration must not dispatch the same action"
        )
    }

    /** A stale global registration must not dispatch after the binding moves to LOCAL. */
    @Test
    fun `stale global registration does not dispatch after moving to local`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.backend.refusedUnregister += "control+shift+KeyQ"
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
                        .copy(scope = HotkeyScope.LOCAL)
                )
            )
        )
        advanceUntilIdle()

        assertEquals(setOf("control+shift+KeyQ"), harness.backend.leftoverAccelerators())
        harness.backend.emitLeftover("control+shift+KeyQ")
        advanceUntilIdle()
        assertTrue(harness.imageQueries.isEmpty(), "a stale global registration must not dispatch")

        // The binding is LOCAL now, so the local path owns it and it is no longer global.
        assertEquals(
            listOf(HotkeyAction.SHOW_IMAGES),
            harness.listener.getLocalBindings().map { it.action }
        )
    }

    /** An unchanged registration keeps firing and keeps its token across reconciles. */
    @Test
    fun `unchanged registration keeps its token and keeps dispatching`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()
        val token = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)

        // A reconcile that changes nothing relevant (selection toggle only).
        harness.set(harness.applied.copy(selectionIconEnabled = true))
        advanceUntilIdle()

        assertEquals(token, harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES))
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(listOf("word"), harness.imageQueries)
    }

    /** Accepted tokens still obey the global master switch. */
    @Test
    fun `accepted registration obeys effective hotkeys enabled`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()
        val token = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)

        harness.set(harness.applied.copy(globalHotkeysEnabled = false))
        advanceUntilIdle()

        // The registration is gone from the native set, and its old token is retired.
        assertEquals(null, harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES))
        harness.backend.emit(GlobalInputEvent.Hotkey(token!!))
        advanceUntilIdle()
        assertTrue(harness.imageQueries.isEmpty(), "a retired token must not dispatch while disabled")
    }

    /** A paused runtime retires tokens too, so nothing dispatches from a stale one. */
    @Test
    fun `pause retires tokens so a stale one cannot dispatch`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()
        val token = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)!!

        harness.setPaused(true)
        advanceUntilIdle()

        harness.backend.emit(GlobalInputEvent.Hotkey(token))
        advanceUntilIdle()
        assertTrue(harness.imageQueries.isEmpty())
    }

    /** A later successful cleanup releases the stale registration and leaves the current one. */
    @Test
    fun `retry after refused unregister clears the leftover without touching the current token`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.backend.refusedUnregister += "control+shift+KeyQ"
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        advanceUntilIdle()
        val currentToken = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)
        assertEquals(setOf("control+shift+KeyQ"), harness.backend.leftoverAccelerators())

        // The platform starts cooperating; the next apply releases the leftover.
        harness.backend.refusedUnregister.clear()
        harness.set(harness.applied.copy(selectionIconEnabled = true))
        advanceUntilIdle()

        assertTrue(harness.backend.leftoverAccelerators().isEmpty(), "cleanup must complete")
        assertEquals(
            currentToken,
            harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES),
            "the current token must survive the cleanup"
        )
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(listOf("word"), harness.imageQueries)
    }

    /** An unknown token never dispatches, whatever it happens to equal. */
    @Test
    fun `unknown token never dispatches`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()

        for (token in listOf(UNACCEPTED_TOKEN, 0L, -1L, Long.MAX_VALUE, 12_345L)) {
            harness.backend.emit(GlobalInputEvent.Hotkey(token))
        }
        advanceUntilIdle()
        assertTrue(harness.imageQueries.isEmpty())
    }

    /**
     * An action ordinal is no longer a usable native identity.
     *
     * The id is resolved as an opaque token, so a small ordinal may numerically coincide with an
     * accepted token; that is meaningless, because the value identifies a registration and not an
     * action. What must hold is that an ordinal never dispatches the action it names.
     */
    @Test
    fun `an action ordinal does not dispatch the action it names`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()

        // Every action's ordinal sent as a registration id, as the old action-keyed scheme did.
        for (action in HotkeyAction.entries) {
            harness.backend.emit(GlobalInputEvent.Hotkey(action.ordinal.toLong()))
        }
        advanceUntilIdle()

        assertTrue(harness.quickQueries.isEmpty(), "SHOW_QUICK_TRANSLATE's ordinal dispatched it")
        assertTrue(harness.listened.isEmpty(), "LISTEN_TO_TEXT's ordinal dispatched it")
        assertTrue(harness.dictionaryQueries.isEmpty(), "SHOW_DICTIONARY's ordinal dispatched it")
        assertTrue(harness.replaced.isEmpty(), "REPLACE_WITH_TRANSLATION's ordinal dispatched it")
        assertEquals(0, harness.shows, "SHOW_MAIN_WINDOW's ordinal opened the app")
    }

    /** Shutdown retires every token: a late event cannot dispatch. */
    @Test
    fun `tokens are retired on shutdown`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()
        val token = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)!!

        harness.listener.shutdown()
        harness.backend.emit(GlobalInputEvent.Hotkey(token))
        advanceUntilIdle()

        assertTrue(harness.imageQueries.isEmpty())
        assertEquals(null, harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES))
    }

    /** A failed apply promotes nothing: the previous tokens stay valid and usable. */
    @Test
    fun `failed apply keeps the previous tokens accepted`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()
        val token = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)

        // The next apply fails, so nothing about the accepted set may change.
        harness.backend.failNextApplies = 1
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        advanceUntilIdle()

        assertEquals(
            token,
            harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES),
            "a failed apply must not accept the new plan"
        )
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(listOf("word"), harness.imageQueries)
    }

    // ---------------------------------------------------------------------------------------
    // Degraded applies and the accepted-token ledger.
    //
    // A degraded apply (the requested set active, but some obsolete registrations still
    // installed) is accepted exactly like a clean one, because the new registrations work either
    // way. The leftovers are recorded at apply time (never inferred from later events) and
    // retried on the next apply; their tokens stay retired throughout, so they cannot dispatch.
    // ---------------------------------------------------------------------------------------

    /** A degraded apply accepts the new plan and records the leftovers immediately. */
    @Test
    fun `degraded apply accepts the new plan and records leftovers at apply time`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()
        val oldToken = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)

        harness.backend.refusedUnregister += "control+shift+KeyQ"
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        advanceUntilIdle()

        // Accepted, despite the leftover: the new registration works.
        val newToken = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)
        assertNotEquals(oldToken, newToken)
        // Recorded at apply time, without waiting for the stale shortcut to fire.
        assertEquals(setOf("control+shift+KeyQ"), harness.listener.outstandingLeftovers())
        assertTrue(harness.logger.warns.any { it.contains("control+shift+KeyQ") })

        // The new registration dispatches; the leftover does not.
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        harness.backend.emitLeftover("control+shift+KeyQ")
        advanceUntilIdle()
        assertEquals(listOf("word"), harness.imageQueries)
    }

    /** A later clean apply clears the recorded leftovers once the release succeeds. */
    @Test
    fun `clean apply after degraded clears the leftover record`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()

        harness.backend.refusedUnregister += "control+shift+KeyQ"
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        advanceUntilIdle()
        assertEquals(setOf("control+shift+KeyQ"), harness.listener.outstandingLeftovers())

        // The platform cooperates again; any later reconcile retries the release.
        harness.backend.refusedUnregister.clear()
        harness.set(harness.applied.copy(selectionIconEnabled = true))
        advanceUntilIdle()

        assertTrue(harness.listener.outstandingLeftovers().isEmpty())
        assertTrue(harness.backend.leftoverAccelerators().isEmpty())
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(listOf("word"), harness.imageQueries)
    }

    /**
     * A speculative registration left installed by a failed apply carries a token that was never
     * accepted, so its events are ignored while the previous token keeps working.
     */
    @Test
    fun `speculative leftover after failed apply is ignored while the old token works`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()
        val oldToken = harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES)

        // The apply installs Ctrl+F9 and then reports failure: Ctrl+F9 is live in the OS, but its
        // token was never accepted and the old plan stands.
        harness.backend.throwAfterPartialApply = true
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        advanceUntilIdle()

        assertEquals(
            oldToken,
            harness.listener.acceptedTokenFor(HotkeyAction.SHOW_IMAGES),
            "a failed apply must not accept the new plan"
        )
        // The speculative registration fires, but its token is unknown: ignored.
        harness.backend.emitInstalled("control+F9")
        advanceUntilIdle()
        assertTrue(harness.imageQueries.isEmpty())
        assertTrue(harness.listener.observedUnacceptedTokens().isNotEmpty())
        // The previously accepted registration still works.
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(listOf("word"), harness.imageQueries)
    }

    /**
     * An event for a newly registered token that arrives before `accept` publishes the plan is
     * ignored rather than dispatched: fail-safe during configuration mutation, never a stale
     * dispatch.
     */
    @Test
    fun `event arriving before accept is ignored not dispatched`() = runTest {
        val harness = imageHarness()
        harness.set(InputRuntimeState(bindings = imagesBinding()))
        harness.listener.initialize()
        advanceUntilIdle()

        // Emit the new registration's token from inside the apply itself, before the listener
        // has accepted the plan, exactly as a racing OS event would.
        harness.backend.emitDuringApply = { backend ->
            val token = backend.lastApplied().single { it.accelerator == "control+KeyF9" }.id
            backend.emit(GlobalInputEvent.Hotkey(token))
        }
        harness.set(
            InputRuntimeState(
                bindings = bindings(
                    global(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK)
                )
            )
        )
        advanceUntilIdle()
        harness.backend.emitDuringApply = null

        assertTrue(
            harness.imageQueries.isEmpty(),
            "an event for a not-yet-accepted token must not dispatch"
        )
        // Once accepted, the same registration dispatches normally.
        harness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceUntilIdle()
        assertEquals(listOf("word"), harness.imageQueries)
    }

    // ---------------------------------------------------------------------------------------
    // LOCAL Swing shortcuts.
    //
    // A LOCAL selection shortcut fires from Swing's InputMap while its trigger chord can still
    // be physically held. It must therefore take the exact same deterministic neutralization
    // path as a GLOBAL selection hotkey: wait for the trigger to read neutral (through the same
    // TriggerNeutralizer, no arbitrary delay), then exactly one capture. Non-selection LOCAL
    // actions stay immediate. `dispatchLocalAction(binding)` is the entry point MainAppFrame's
    // InputMap uses.
    // ---------------------------------------------------------------------------------------

    private fun local(action: HotkeyAction, keyCode: Int, modifiers: Int) =
        HotkeyBinding(action, keyCode = keyCode, modifiers = modifiers, scope = HotkeyScope.LOCAL)

    /** The default Ctrl+Shift+Q-style LOCAL selection shortcut (SHOW_IMAGES is strict). */
    private fun localSelectionBinding() = local(
        HotkeyAction.SHOW_IMAGES, KeyEvent.VK_Q,
        InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
    )

    /** Asserts a held portable usage blocks a LOCAL selection capture until it is released. */
    private fun TestScope.assertLocalModifierBlocks(held: Int) {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)

        harness.backend.keyDown[held] = true
        harness.listener.dispatchLocalAction(localSelectionBinding())
        advanceTimeBy(500)
        assertTrue(injections.isEmpty(), "a held ${Integer.toHexString(held)} must block local capture")
        assertTrue(harness.imageQueries.isEmpty())

        harness.backend.keyDown.clear()
        advanceUntilIdle()
        assertEquals(1, injections.size)
        assertEquals(listOf("word"), harness.imageQueries)
    }

    /** 1. Held well past the old arbitrary settle delay: nothing starts until release. */
    @Test
    fun `local ctrl shift shortcut held over 500ms captures only after release`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)

        harness.backend.keyDown[QInputKey.MOD_LEFT_CONTROL] = true
        harness.backend.keyDown[QInputKey.MOD_LEFT_SHIFT] = true
        harness.backend.keyDown[QInputKey.Q] = true
        harness.listener.dispatchLocalAction(localSelectionBinding())
        advanceTimeBy(500)
        assertTrue(injections.isEmpty(), "no capture may begin while the trigger is held")
        assertTrue(harness.imageQueries.isEmpty())

        harness.backend.keyDown.clear()
        advanceUntilIdle()
        assertEquals(1, injections.size)
        assertEquals(listOf("word"), harness.imageQueries)
    }

    /** 2. A fast tap already neutral: exactly one capture, no wait. */
    @Test
    fun `local fast tap captures exactly once`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)

        harness.listener.dispatchLocalAction(localSelectionBinding())
        advanceUntilIdle()
        assertEquals(1, injections.size)
        assertEquals(listOf("word"), harness.imageQueries)
    }

    /**
     * 3 (Ctrl). The Copy modifier is exempt: a held Ctrl is harmless because injection preserves
     * it, so it must NOT block. This is the "do not over-watch" side of the contract.
     */
    @Test
    fun `local held ctrl does not block selection capture`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)

        harness.backend.keyDown[QInputKey.MOD_LEFT_CONTROL] = true
        harness.listener.dispatchLocalAction(localSelectionBinding())
        advanceUntilIdle()
        assertEquals(1, injections.size)
        assertEquals(listOf("word"), harness.imageQueries)
    }

    /** 4. Left Shift. */
    @Test
    fun `local held left shift blocks until release`() = runTest {
        assertLocalModifierBlocks(QInputKey.MOD_LEFT_SHIFT)
    }

    /** 5. Right Shift (same modifier family as the trigger's Shift: both sides are watched). */
    @Test
    fun `local held right shift blocks until release`() = runTest {
        assertLocalModifierBlocks(QInputKey.MOD_RIGHT_SHIFT)
    }

    /** 6. Left Alt. */
    @Test
    fun `local held left alt blocks until release`() = runTest {
        assertLocalModifierBlocks(QInputKey.MOD_LEFT_ALT)
    }

    /** 7. Right Alt. */
    @Test
    fun `local held right alt blocks until release`() = runTest {
        assertLocalModifierBlocks(QInputKey.MOD_RIGHT_ALT)
    }

    /** 8. Left Meta. */
    @Test
    fun `local held left meta blocks until release`() = runTest {
        assertLocalModifierBlocks(QInputKey.MOD_LEFT_META)
    }

    /** 9. Right Meta. */
    @Test
    fun `local held right meta blocks until release`() = runTest {
        assertLocalModifierBlocks(QInputKey.MOD_RIGHT_META)
    }

    /** 10. Timeout: fail closed with no Copy, no capture, no dispatch. */
    @Test
    fun `local neutralization timeout performs no capture and no dispatch`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)

        harness.backend.keyDown[QInputKey.Q] = true
        harness.listener.dispatchLocalAction(localSelectionBinding())
        advanceUntilIdle()

        assertTrue(injections.isEmpty(), "no Copy may be attempted after a neutralization timeout")
        assertTrue(harness.imageQueries.isEmpty(), "no action may dispatch after a timeout")
        assertTrue(harness.logger.warns.any { it.contains("never released", ignoreCase = true) })
    }

    /**
     * 11. No key-state capability: the conservative legacy behavior is preserved, capture
     * proceeds immediately and the neutralizer is never queried.
     */
    @Test
    fun `local capture without key-state capability stays immediate`() = runTest {
        val backend = FakeGlobalInputBackend(
            capabilities = FakeGlobalInputBackend().capabilities.copy(keyState = false)
        )
        val harness = Harness(scope = this, backend = backend, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)

        // Trigger physically held: with no synchronous key-state query there is nothing to wait on.
        harness.backend.keyDown[QInputKey.MOD_LEFT_SHIFT] = true
        harness.backend.keyDown[QInputKey.Q] = true
        harness.listener.dispatchLocalAction(localSelectionBinding())
        advanceUntilIdle()

        assertEquals(1, injections.size)
        assertEquals(listOf("word"), harness.imageQueries)
        assertEquals(0, backend.keyStateQueries)
    }

    /** 12. Non-selection LOCAL actions stay immediate and never neutralize. */
    @Test
    fun `local non-selection actions are immediate and never neutralize`() = runTest {
        val harness = harness()
        harness.listener.initialize()

        // Trigger chords physically held: a non-selection action must not wait for release.
        harness.backend.keyDown[QInputKey.MOD_LEFT_CONTROL] = true
        harness.backend.keyDown[QInputKey.I] = true
        harness.backend.keyDown[QInputKey.ENTER] = true

        harness.listener.dispatchLocalAction(local(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK))
        harness.listener.dispatchLocalAction(local(HotkeyAction.CYCLE_TARGET_LANGUAGE, KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK))
        harness.listener.dispatchLocalAction(local(HotkeyAction.TRANSLATE, KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK))

        // Immediate: every callback ran synchronously, with no coroutine and no key-state query.
        assertEquals(1, harness.ocrCalls)
        assertEquals(1, harness.cycles)
        assertEquals(1, harness.translates)
        assertEquals(0, harness.backend.keyStateQueries)

        advanceUntilIdle()
        assertEquals(1, harness.ocrCalls)
        assertEquals(1, harness.cycles)
        assertEquals(1, harness.translates)
        assertEquals(0, harness.backend.keyStateQueries)
    }

    /** 13. One LOCAL trigger, exactly one capture, never two. */
    @Test
    fun `one local selection trigger produces exactly one capture`() = runTest {
        val harness = Harness(scope = this, captureFactory = successCapture("word"))
        val injections = mutableListOf<Unit>()
        harness.listener.initialize()
        harness.listener.copyInjector = recordInjector(injections)

        harness.listener.dispatchLocalAction(localSelectionBinding())
        advanceUntilIdle()

        assertEquals(1, injections.size, "one trigger, one Copy")
        assertEquals(listOf("word"), harness.imageQueries, "one trigger, one dispatch")
    }

    /**
     * A LOCAL selection shortcut and a GLOBAL selection hotkey with the same chord share the
     * neutralization contract: both wait, both capture after release, both capture exactly once.
     */
    @Test
    fun `local and global selection shortcuts neutralize identically`() = runTest {
        val localHarness = Harness(scope = this, captureFactory = successCapture("word"))
        val globalHarness = Harness(scope = this, captureFactory = successCapture("word"))
        val localInjections = mutableListOf<Unit>()
        val globalInjections = mutableListOf<Unit>()
        localHarness.listener.initialize()
        localHarness.listener.copyInjector = recordInjector(localInjections)
        globalHarness.set(InputRuntimeState(bindings = imagesBinding()))
        globalHarness.listener.initialize()
        globalHarness.listener.copyInjector = recordInjector(globalInjections)

        val held = listOf(QInputKey.Q, QInputKey.MOD_LEFT_SHIFT)
        for (harness in listOf(localHarness, globalHarness)) {
            held.forEach { harness.backend.keyDown[it] = true }
        }
        localHarness.listener.dispatchLocalAction(localSelectionBinding())
        globalHarness.emitHotkey(HotkeyAction.SHOW_IMAGES)
        advanceTimeBy(500)
        assertTrue(localInjections.isEmpty() && globalInjections.isEmpty())

        for (harness in listOf(localHarness, globalHarness)) harness.backend.keyDown.clear()
        advanceUntilIdle()
        assertEquals(1, localInjections.size)
        assertEquals(1, globalInjections.size)
        assertEquals(listOf("word"), localHarness.imageQueries)
        assertEquals(listOf("word"), globalHarness.imageQueries)
    }
}
