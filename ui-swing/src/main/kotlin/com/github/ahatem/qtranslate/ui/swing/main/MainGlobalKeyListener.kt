package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.ui.swing.main.input.ApplyResult
import com.github.ahatem.qtranslate.ui.swing.main.input.CopyInjector
import com.github.ahatem.qtranslate.ui.swing.main.input.DoubleCtrlDetector
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalInputBackend
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalInputEvent
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalRegistration
import com.github.ahatem.qtranslate.ui.swing.main.input.HotkeyRegistrationLedger
import com.github.ahatem.qtranslate.ui.swing.main.input.HotkeyRegistrationToken
import com.github.ahatem.qtranslate.ui.swing.main.input.InputCapabilities
import com.github.ahatem.qtranslate.ui.swing.main.input.InputRequirements
import com.github.ahatem.qtranslate.ui.swing.main.input.InputRuntimeState
import com.github.ahatem.qtranslate.ui.swing.main.input.QInputCopyInjector
import com.github.ahatem.qtranslate.ui.swing.main.input.RobotCopyInjector
import com.github.ahatem.qtranslate.ui.swing.main.input.TriggerNeutralizer
import com.github.ahatem.qtranslate.ui.swing.main.input.KeyClass
import com.github.ahatem.qtranslate.ui.swing.main.input.MouseButtonId
import com.github.ahatem.qtranslate.ui.swing.main.input.QInputBackend
import com.github.ahatem.qtranslate.ui.swing.main.input.SelectionGestureTracker
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.AwtSystemClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.CaptureFailure
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.CaptureResult
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.ClipboardChangeMonitor
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.ClipboardChangeMonitors
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.SelectionCapture
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.SystemClipboard
import io.github.ahatem.qinput.AwtAccelerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Point
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages global and local hotkey registration.
 *
 * Global input comes solely from a [GlobalInputBackend] (QInput native runtime); there is no
 * JNativeHook/JKeyMaster usage anywhere on this path. [HotkeyScope.GLOBAL] bindings register
 * with the backend and fire even when unfocused; [HotkeyScope.LOCAL] bindings are exposed via
 * [getLocalBindings] for the caller to register on a Swing InputMap.
 * [HotkeyAction.SHOW_MAIN_WINDOW] is always GLOBAL and fires via double-Ctrl raw key tracking,
 * not a registered KeyStroke.
 */
class MainGlobalKeyListener(
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val onShowApp: (String) -> Unit,
    private val onShowQuickTranslate: (String) -> Unit,
    private val onListenToText: (String) -> Unit,
    private val onOpenSnippingTool: () -> Unit,
    private val onReplaceWithTranslation: (String) -> Unit,
    private val onCycleTargetLanguage: () -> Unit,
    private val onShowDictionary: (String) -> Unit = {},
    private val onShowImages: (String) -> Unit = {},
    private val onTranslate: () -> Unit = {},
    private val onSelectionDetected: (String, Point) -> Unit = { _, _ -> },
    private val onPointerPressed: (Point) -> Unit = {},
    private val shouldTrackSelectionAt: (Point) -> Boolean = { true },
    private val backendFactory: (() -> GlobalInputBackend)? = null,
    private val selectionCaptureFactory: ((
        clipboard: SystemClipboard,
        monitor: ClipboardChangeMonitor,
        simulateCopy: suspend () -> Boolean,
        logger: Logger,
    ) -> SelectionCapture)? = null,
) {

    private var backend: GlobalInputBackend? = null
    private val detector = DoubleCtrlDetector()
    private val gestureTracker = SelectionGestureTracker()
    internal var copyInjector: CopyInjector = RobotCopyInjector(logger)
    private val neutralizer = TriggerNeutralizer(
        anyDown = { usages ->
            runCatching { backend?.anyKeyDown(usages) ?: false }.getOrDefault(true)
        }
    )
    private val systemClipboard = AwtSystemClipboard()
    private val selectionCapture = selectionCaptureFactory?.invoke(
        systemClipboard,
        ClipboardChangeMonitors.create(systemClipboard, logger),
        { simulateCopy() },
        logger
    ) ?: SelectionCapture(
        clipboard = systemClipboard,
        changeMonitor = ClipboardChangeMonitors.create(systemClipboard, logger),
        simulateCopy = { simulateCopy() },
        logger = logger
    )
    /** Last successfully applied registration set, kept to roll back to on failure. */
    private var lastApplied: List<GlobalInputBackend.HotkeyRegistration> = emptyList()

    /**
     * Token ledger for the native registrations. Dispatch is keyed on tokens rather than
     * actions, so a registration the platform refused to release stays non-dispatchable.
     */
    private val registrations = HotkeyRegistrationLedger()

    /**
     * Accelerators of native registrations that remain installed although no longer wanted.
     *
     * Reported by the backend at apply time, not inferred from events, since waiting for a
     * stale shortcut to fire would leave cleanup incomplete indefinitely.
     */
    private var degradedLeftovers: Set<String> = emptySet()

    /** Leftover native registrations outstanding after the last apply. Diagnostics/tests only. */
    internal fun outstandingLeftovers(): Set<String> = degradedLeftovers.toSet()

    /**
     * Tokens observed firing without an accepted registration: direct evidence of a platform
     * release that failed. Recorded once per token so a held shortcut cannot flood the log.
     * Concurrent: written from the input dispatcher thread.
     */
    private val unacceptedTokens: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // Guarded by [reconcileLock]; an outside-lock check-and-set alone would race [initialize]/[shutdown].
    private val initialized = AtomicBoolean(false)
    /**
     * The complete runtime input state, recorded even before initialization so early settings
     * changes are honored by [initialize].
     *
     * Written only while holding [reconcileLock]: every writer also triggers a reconcile from
     * the same value, so the two move together as one transaction.
     */
    @Volatile private var runtimeState: InputRuntimeState = InputRuntimeState()

    /**
     * Serializes the whole reconciliation transaction against itself and against the lifecycle
     * mutations ([initialize], [shutdown]) that touch the same state.
     *
     * [updateRuntimeState] can run off the EDT (settings flow) while [setPaused] and startup run
     * on it; without this lock, an older reconciliation that reads state first but publishes
     * last could accept tokens for a native set that was never actually installed.
     *
     * Held across the native `applyHotkeys`/`setRawMask` calls (synchronous, never re-entrant
     * into this listener), so serializing exactly the native round trip is the point: only one
     * apply may be in flight at a time. A [ReentrantLock], not `synchronized`, so tests can
     * observe a blocked second reconciliation via [hasQueuedReconcile].
     */
    private val reconcileLock = ReentrantLock()

    /**
     * Creates and starts the backend, then applies the current [runtimeState] to it.
     *
     * The check-and-set and the whole creation happen under [reconcileLock] as one critical
     * section: otherwise a racing [shutdown] could observe `initialized` true before a backend
     * exists, tear down nothing, and let this call create a backend that shutdown never closes.
     * A failed creation clears `initialized` under the lock so a later retry can succeed.
     */
    fun initialize() {
        reconcileLock.withLock {
            if (!initialized.compareAndSet(false, true)) return
            try {
                val created = (backendFactory ?: { QInputBackend(logger) })()
                backend = created
                copyInjector = QInputCopyInjector({ backend }, logger)
                created.setListener { handleGlobalEvent(it) }
                logger.info("Global input backend: ${created.backendName} ${created.capabilities}")
                reportCapabilities(created.capabilities)
                reconcile()
            } catch (e: Exception) {
                runCatching { backend?.close() }
                backend = null
                initialized.set(false)   // allow retry if initialization itself failed
                logger.error("Hotkey initialization failed", e)
            }
        }
    }

    fun updateBindings(newBindings: List<HotkeyBinding>) {
        reconcileLock.withLock {
            runtimeState = runtimeState.copy(bindings = newBindings)
            if (!initialized.get()) return
            reconcile()
        }
    }

    /**
     * Applies one explicit runtime input state through the single reconciliation path, so
     * independent setters cannot leave the native side inconsistent. The Double Ctrl opt-in is
     * always derived from the bindings, never trusted from callers.
     */
    fun updateRuntimeState(state: InputRuntimeState) {
        reconcileLock.withLock {
            val normalized = state.copy(doubleCtrlEnabled = state.bindings.any {
                it.action == HotkeyAction.SHOW_MAIN_WINDOW && it.isEnabled && it.isDoubleCtrlEnabled
            })
            val previous = runtimeState
            runtimeState = normalized
            // A tap armed before the disable must not pair with a tap after re-enable.
            if (!normalized.globalHotkeysEnabled && previous.globalHotkeysEnabled) detector.reset()
            if (!initialized.get()) return
            reconcile()
        }
    }

    /**
     * Temporarily suppresses registered shortcuts (e.g. while recording a shortcut) without
     * touching the configured switch; clearing the pause restores exactly what was configured.
     * Both transitions reset Double Ctrl temporal state: no Ctrl event from before or during
     * a pause may ever combine with one after resume. Raw observation is unaffected.
     */
    fun setPaused(paused: Boolean) {
        reconcileLock.withLock {
            val current = runtimeState
            if (current.paused == paused) return
            runtimeState = current.copy(paused = paused)
            detector.reset()
            if (initialized.get()) reconcile()
        }
    }

    /**
     * Returns bindings with [HotkeyScope.LOCAL] scope that are enabled and have a key.
     * The caller (MainAppFrame) registers these via Swing InputMap.
     */
    fun getLocalBindings(): List<HotkeyBinding> =
        runtimeState.bindings.filter { it.scope == HotkeyScope.LOCAL && it.isEnabled && it.hasBinding }

    /** Whether another thread is blocked entering a reconciliation transaction (or [shutdown]). Diagnostics/tests only. */
    internal fun hasQueuedReconcile(): Boolean = reconcileLock.hasQueuedThreads()

    /**
     * Stops and discards the backend, retiring every accepted registration token.
     *
     * The initialized check sits inside [reconcileLock]: outside it, a queued [initialize]
     * could see `initialized` still true and silently no-op instead of running once this
     * teardown finishes. The lock also waits out an in-flight reconcile first, so it cannot
     * apply/accept against a backend this call already closed.
     */
    fun shutdown() {
        reconcileLock.withLock {
            if (!initialized.get()) return
            try {
                runCatching { backend?.close() }
                    .onFailure { logger.error("Hotkey manager shutdown error", it) }
                backend = null
                // Retire every token: a late event must find no accepted registration.
                registrations.clear()
                lastApplied = emptyList()
                degradedLeftovers = emptySet()
            } finally {
                initialized.set(false)
            }
        }
    }

    /** Current backend for sibling features (paste injection); null before initialization. */
    internal fun inputBackend(): GlobalInputBackend? = backend

    /** Whether a live lifecycle generation exists. Diagnostics/tests only. */
    internal fun isInitialized(): Boolean = initialized.get()

    private fun reportCapabilities(caps: InputCapabilities) {
        if (!caps.hotkeys) {
            logger.error("Global input backend reports no registered-hotkey support; global shortcuts will not fire")
        }
        if (!caps.rawKeyboard) {
            logger.warn("Global input backend has no raw keyboard events; Double Ctrl is unavailable")
        }
        if (!caps.rawMouseButtons) {
            logger.warn("Global input backend has no global mouse buttons; popup outside-click and selection gestures are unavailable")
        }
        if (!caps.rawMouseMotion) {
            logger.warn("Global input backend has no global mouse motion; selection drag detection is unavailable")
        }
        if (!caps.keyState) {
            logger.debug("Global input backend has no key-state query; hotkey captures proceed without trigger neutralization")
        }
    }

    /**
     * The single reconciliation path: derives the native registration set and raw subscriptions
     * from current QTranslate state and applies both together. Must only be called while
     * holding [reconcileLock].
     *
     * Ordering is load-bearing: tokens are planned, the native set applied, and only a
     * successful apply promotes the plan to accepted. Accepting before the apply (or keeping
     * retired tokens on failure) would let an event dispatch against a registration that isn't
     * actually installed.
     *
     * A degraded apply is accepted exactly like a clean one: retiring superseded tokens makes
     * the leftovers non-dispatchable regardless; [recordApplyOutcome] only tracks the report for
     * diagnostics.
     */
    private fun reconcile() {
        val target = backend ?: return
        val state = runtimeState
        val requirements = InputRequirements.resolve(state)
        val desired = if (requirements.registerHotkeys) desiredRegistrations(state.bindings) else emptyList()

        val plan = registrations.plan(desired)
        val native = plan.map { planned ->
            GlobalInputBackend.HotkeyRegistration(
                id = planned.token.value,
                accelerator = planned.registration.accelerator,
                description = planned.registration.action.name
            )
        }
        val outcome = try {
            target.applyHotkeys(native)
        } catch (e: Exception) {
            logger.error("Failed to apply global hotkeys, restoring previous set", e)
            runCatching { target.applyHotkeys(lastApplied) }
                .onFailure { logger.error("Failed to restore previous global hotkeys", it) }
            null
        } ?: return
        registrations.accept(plan)
        lastApplied = native
        recordApplyOutcome(outcome, native.size)
        runCatching {
            target.setRawMask(
                keyboard = requirements.keyboard,
                mouseButtons = requirements.mouseButtons,
                mouseMotion = requirements.mouseMotion
            )
        }.onFailure { logger.warn("Failed to update raw input mask: ${it.message}") }
    }

    /**
     * Records what the last successful apply left behind.
     *
     * Clean clears any previously recorded leftovers. New leftovers warn once per accelerator;
     * repeats stay quiet since every reconcile retries the release anyway.
     */
    private fun recordApplyOutcome(outcome: ApplyResult, appliedCount: Int) {
        when (outcome) {
            is ApplyResult.Clean -> {
                if (degradedLeftovers.isNotEmpty()) {
                    logger.info("Native hotkey cleanup complete; all obsolete registrations released")
                } else {
                    logger.debug("Applied $appliedCount global hotkeys")
                }
                degradedLeftovers = emptySet()
            }
            is ApplyResult.Degraded -> {
                val fresh = outcome.leftovers.toSet() - degradedLeftovers
                if (fresh.isNotEmpty()) {
                    logger.warn(
                        "Applied $appliedCount global hotkeys, but these obsolete native " +
                            "registrations are still installed and cannot dispatch: " +
                            "${fresh.sorted().joinToString()}. They will be retried on the next apply."
                    )
                }
                degradedLeftovers = outcome.leftovers.toSet()
            }
        }
    }

    /** Whether Double Ctrl can currently fire: the SHOW_MAIN_WINDOW opt-in is on. */
    private fun doubleCtrlEffective(): Boolean =
        runtimeState.bindings.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            ?.let { it.isEnabled && it.isDoubleCtrlEnabled } ?: false

    /**
     * Converts enabled GLOBAL bindings to the registrations the configuration wants installed.
     * Unsupported keys and duplicate accelerators are skipped with a warning and never take down
     * the rest: silently registering the wrong key would be worse than skipping.
     */
    private fun desiredRegistrations(bindings: List<HotkeyBinding>): List<GlobalRegistration> {
        val seen = HashSet<String>()
        val out = ArrayList<GlobalRegistration>()
        for (binding in bindings) {
            if (binding.scope != HotkeyScope.GLOBAL || !binding.isEnabled || !binding.hasBinding) continue
            val accelerator = acceleratorOrNull(binding)
            if (accelerator == null) {
                logger.warn(
                    "Skipping global hotkey ${binding.action.name}: unsupported key " +
                        "(keyCode=${binding.keyCode}, modifiers=${binding.modifiers})"
                )
                continue
            }
            if (!seen.add(accelerator.lowercase())) {
                logger.warn("Skipping duplicate global hotkey ${binding.action.name} ($accelerator)")
                continue
            }
            out += GlobalRegistration(binding.action, accelerator)
        }
        return out
    }

    /**
     * The native accelerator this binding represents, or null when the key cannot be represented.
     * Throwing-free so the dispatch path can compare without logging noise.
     */
    private fun acceleratorOrNull(binding: HotkeyBinding): String? =
        runCatching { AwtAccelerator.format(binding.keyCode, binding.modifiers) }.getOrNull()

    /**
     * Dispatches a hotkey event by its registration token, or ignores it when the token is not
     * one QTranslate currently accepts.
     */
    private fun handleHotkey(rawToken: Long) {
        val token = HotkeyRegistrationToken(rawToken)
        val registration = registrations.registrationFor(token)
        if (registration == null) {
            reportUnacceptedToken(token)
            return
        }

        // Defense in depth: the token already identifies the exact registration, but the
        // action's binding may have changed since the token was issued, so re-check it's still
        // wanted. The accelerator is deliberately not re-compared: a rolled-back apply can
        // legitimately leave the accepted registration with the previous accelerator.
        val state = runtimeState
        if (!state.effectiveHotkeysEnabled) return
        val binding = state.bindings.firstOrNull { it.action == registration.action } ?: return
        if (!binding.isEnabled || binding.scope != HotkeyScope.GLOBAL || !binding.hasBinding) return

        dispatchBinding(binding)
    }

    /**
     * Records a token that fired without an accepted registration: physically installed but no
     * longer accepted, and never dispatched. The next reconciliation retries releasing it.
     */
    private fun reportUnacceptedToken(token: HotkeyRegistrationToken) {
        if (unacceptedTokens.add(token.value)) {
            logger.warn(
                "Ignoring hotkey event for registration token ${token.value}: it is not an " +
                    "accepted registration. A superseded native registration is still installed, " +
                    "so native cleanup is incomplete."
            )
        }
    }

    /** Registration tokens that fired without being accepted, since startup. Diagnostics/tests only. */
    internal fun observedUnacceptedTokens(): Set<Long> = unacceptedTokens.toSet()

    /** The token currently accepted for [action], if any. Diagnostics/tests only. */
    internal fun acceptedTokenFor(action: HotkeyAction): Long? =
        registrations.tokenFor(action)?.value

    private fun handleGlobalEvent(event: GlobalInputEvent) {
        // Belt and braces next to the backend's own post-close silence: a late event must
        // never dispatch an action after shutdown.
        if (!initialized.get()) return
        when (event) {
            is GlobalInputEvent.Hotkey -> handleHotkey(event.id)
            is GlobalInputEvent.Key -> handleRawKey(event)
            is GlobalInputEvent.MouseButton -> handleMouseButton(event)
            is GlobalInputEvent.MouseMove ->
                gestureTracker.onMoved(event.location)
        }
    }

    /** Feeds raw keyboard events into the Double Ctrl detector; injected Copy chords are fed like any other key. */
    private fun handleRawKey(event: GlobalInputEvent.Key) {
        // QInput's own synthetic input never counts as genuine Double Ctrl input; third-party
        // injected input (remappers, clipboard tools) still passes through like physical keys.
        if (event.selfInjected) return
        if (event.keyClass == KeyClass.CONTROL) {
            if (event.pressed) {
                detector.onControlPressed()
                return
            }
            // Tracking runs regardless; only firing is gated on these flags.
            val state = runtimeState
            val binding = state.bindings.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            val active = state.effectiveHotkeysEnabled &&
                binding != null && binding.isEnabled && binding.isDoubleCtrlEnabled
            // Monotonic, matching native timestamps: a wall clock could step backwards mid-window.
            val nowMs = event.timestampMs.takeIf { it != Long.MIN_VALUE }
                ?: (System.nanoTime() / 1_000_000)
            if (!detector.onControlReleased(nowMs, active)) return
            scope.launch { handleSelectedText(onShowApp) }
        } else if (event.pressed) {
            detector.onOtherKeyPressed()
        }
    }

    private fun handleMouseButton(event: GlobalInputEvent.MouseButton) {
        if (event.pressed) {
            // Reported before the selection-icon check: floating popups rely on this to close
            // on an outside click even when the icon feature is off.
            onPointerPressed(event.location)
            if (event.button == MouseButtonId.LEFT) {
                if (shouldTrackSelectionAt(event.location)) {
                    gestureTracker.onPressed(event.location)
                } else {
                    // A disallowed press must not leave an older gesture armed. The release
                    // event may arrive outside the QTranslate window after a resize or drag.
                    gestureTracker.onReleased()
                }
            }
            return
        }
        val wasDrag = gestureTracker.onReleased()
        if (event.button != MouseButtonId.LEFT) return
        if (!wasDrag || !runtimeState.selectionCaptureEnabled) return

        val pointer = event.location
        scope.launch {
            delay(SELECTION_SETTLE_DELAY_MS)
            handleSelectedText { text ->
                // Re-check the flag: it may have been disabled while capture was in flight.
                if (text.isNotBlank() && runtimeState.selectionCaptureEnabled) {
                    onSelectionDetected(text, pointer)
                }
            }
        }
    }

    /**
     * Selection-dependent capture flow shared by GLOBAL hotkeys and LOCAL Swing shortcuts:
     * capture waits for the trigger to go physically neutral, then exactly one clean Copy runs.
     *
     * Neutralization is skipped only where the backend has no synchronous key-state query.
     */
    private suspend fun captureForHotkey(binding: HotkeyBinding) {
        val action = binding.action
        logger.debug("Hotkey activated: ${action.name}")
        if (backend?.capabilities?.keyState == true) {
            if (!neutralizer.awaitNeutral(binding)) {
                logger.warn(
                    "Trigger chord never released for ${action.name}; " +
                        "capture failed without dispatch (${CaptureFailure.NEUTRALIZATION_TIMEOUT})"
                )
                return
            }
        } else {
            // No neutralization possible without a key-state query.
            logger.debug("Key-state query unavailable; capturing without trigger neutralization")
        }
        routeResult(action, captureSelection())
    }

    private suspend fun captureSelection(): CaptureResult {
        var outcome: CaptureResult = CaptureResult.Failed(CaptureFailure.CAPTURE_ERROR)
        selectionCapture.capture { outcome = it }
        return outcome
    }

    /**
     * Routes a capture result to an action. [CaptureResult.NoUsableText] and
     * [INCONCLUSIVE_AS_EMPTY] failures only dispatch empty text for [LENIENT_ACTIONS]; every
     * other failure never dispatches.
     */
    private fun routeResult(action: HotkeyAction, result: CaptureResult) {
        when (result) {
            is CaptureResult.Success -> dispatchText(action, result.text)
            is CaptureResult.NoUsableText ->
                if (action in LENIENT_ACTIONS) dispatchText(action, "")
                else logger.info("No usable text captured for ${action.name}; not dispatching")
            is CaptureResult.Failed -> when {
                result.reason in INCONCLUSIVE_AS_EMPTY && action in LENIENT_ACTIONS ->
                    dispatchText(action, "")
                // Ordinary silence, not an operational error: logging it as a warning on
                // every hotkey press with nothing selected would just be noise.
                result.reason == CaptureFailure.COPY_UNCONFIRMED ->
                    logger.debug("Copy unconfirmed for ${action.name}; not dispatching")
                else ->
                    logger.warn("Capture failed for ${action.name} (${result.reason}); not dispatching")
            }
        }
    }

    /**
     * Dispatches one LOCAL-scope binding triggered by a Swing InputMap.
     *
     * Selection-dependent actions take the same neutralize-then-capture path as global hotkeys:
     * a locally-triggered Ctrl+Shift+&lt;key&gt; is still physically held when the Swing action
     * fires, so an unneutralized Copy would reach the target as Ctrl+Shift+C.
     */
    fun dispatchLocalAction(binding: HotkeyBinding) = dispatchBinding(binding)

    /** The single dispatch decision shared by the global hotkey path and the LOCAL InputMap path. */
    private fun dispatchBinding(binding: HotkeyBinding) {
        if (binding.action in SELECTIVE_ACTIONS) scope.launch { captureForHotkey(binding) }
        else dispatchImmediate(binding.action)
    }

    /**
     * Dispatches a non-selection action immediately. Selection-dependent actions never reach
     * here; dispatching one here would inject a Copy without waiting for the trigger to go
     * neutral.
     */
    private fun dispatchImmediate(action: HotkeyAction) {
        when (action) {
            HotkeyAction.OPEN_OCR ->
                onOpenSnippingTool()
            HotkeyAction.CYCLE_TARGET_LANGUAGE ->
                onCycleTargetLanguage()
            HotkeyAction.TRANSLATE ->
                onTranslate()
            // Selection-dependent: captured (and neutralized) before dispatch, never here.
            HotkeyAction.SHOW_QUICK_TRANSLATE,
            HotkeyAction.LISTEN_TO_TEXT,
            HotkeyAction.SHOW_MAIN_WINDOW,
            HotkeyAction.REPLACE_WITH_TRANSLATION,
            HotkeyAction.SHOW_DICTIONARY,
            HotkeyAction.SHOW_IMAGES,
            // LOCAL-only, handled by MainAppFrame/MainContentView's InputMap.
            HotkeyAction.FOCUS_INPUT,
            HotkeyAction.FOCUS_OUTPUT,
            HotkeyAction.FOCUS_EXTRA_OUTPUT,
            HotkeyAction.COPY_TRANSLATION,
            HotkeyAction.CLEAR_INPUT,
            HotkeyAction.SWAP_LANGUAGES,
            HotkeyAction.OPEN_SETTINGS,
            HotkeyAction.SHOW_HISTORY,
            HotkeyAction.TRANSLATE_DOCUMENT -> Unit
        }
    }

    private suspend fun handleSelectedText(callback: (String) -> Unit) {
        // These paths have no native trigger to wait on, so they always blank on anything but success.
        when (val result = captureSelection()) {
            is CaptureResult.Success -> callback(result.text)
            else -> callback("")
        }
    }

    /** Delivers captured text to one of the selection-dependent actions. */
    private fun dispatchText(action: HotkeyAction, text: String) {
        when (action) {
            HotkeyAction.SHOW_QUICK_TRANSLATE -> onShowQuickTranslate(text)
            HotkeyAction.LISTEN_TO_TEXT -> onListenToText(text)
            HotkeyAction.SHOW_MAIN_WINDOW -> onShowApp(text)
            HotkeyAction.REPLACE_WITH_TRANSLATION -> onReplaceWithTranslation(text)
            HotkeyAction.SHOW_DICTIONARY -> onShowDictionary(text)
            HotkeyAction.SHOW_IMAGES -> onShowImages(text)
            else -> Unit
        }
    }

    /**
     * Invokes the configured [CopyInjector] exactly once; must never retry on `false` itself,
     * since the injector already owns its complete attempt including any fallback.
     */
    private fun simulateCopy(): Boolean = copyInjector.injectCopy()

    private companion object {
        /** Actions that capture selected text when fired from a global hotkey. */
        val SELECTIVE_ACTIONS = setOf(
            HotkeyAction.SHOW_QUICK_TRANSLATE,
            HotkeyAction.LISTEN_TO_TEXT,
            HotkeyAction.SHOW_MAIN_WINDOW,
            HotkeyAction.REPLACE_WITH_TRANSLATION,
            HotkeyAction.SHOW_DICTIONARY,
            HotkeyAction.SHOW_IMAGES
        )

        /** Actions whose UX tolerates genuinely empty input (dialogs the user can type into). */
        val LENIENT_ACTIONS = setOf(
            HotkeyAction.SHOW_QUICK_TRANSLATE,
            HotkeyAction.SHOW_MAIN_WINDOW
        )

        /** [CaptureFailure] reasons that, for [LENIENT_ACTIONS] only, dispatch as empty text rather than being withheld. */
        val INCONCLUSIVE_AS_EMPTY = setOf(
            CaptureFailure.COPY_UNCONFIRMED,
            CaptureFailure.COPY_INJECTION_FAILED,
        )

        /** Grace period after mouse release so the source app can settle its selection. */
        const val SELECTION_SETTLE_DELAY_MS = 80L
    }
}
