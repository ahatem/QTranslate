package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.ui.swing.main.input.ApplyResult
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalInputBackend
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalInputEvent
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalRegistration
import com.github.ahatem.qtranslate.ui.swing.main.input.HotkeyRegistrationLedger
import com.github.ahatem.qtranslate.ui.swing.main.input.HotkeyRegistrationToken
import com.github.ahatem.qtranslate.ui.swing.main.input.InputCapabilities
import com.github.ahatem.qtranslate.ui.swing.main.input.InputRequirements
import com.github.ahatem.qtranslate.ui.swing.main.input.InputRuntimeState
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
 * Global input comes solely from a [GlobalInputBackend] (QInput native runtime on
 * spike/qinput-native-v2): registered shortcuts arrive as hotkey events, Double Ctrl and
 * selection gestures from raw keyboard/mouse streams. There is no JNativeHook/JKeyMaster
 * usage anywhere on this path.
 *
 * ### Scopes
 * - [HotkeyScope.GLOBAL] — registered with the backend, fires system-wide
 *   even when QTranslate is not focused.
 * - [HotkeyScope.LOCAL] — the caller (MainAppFrame) registers these via
 *   Swing InputMap/ActionMap; this listener only provides the binding list
 *   via [getLocalBindings]. Local bindings never intercept keys from other apps.
 *
 * ### Per-action scope
 * Users can choose per action whether it should be global or local. This prevents
 * shortcuts like Ctrl+L from being stolen from browsers when set to LOCAL.
 *
 * ### Actions
 * - [HotkeyAction.SHOW_MAIN_WINDOW] — special: uses double-Ctrl via raw key events,
 *   not a regular KeyStroke. Always GLOBAL. Respects [isEnabled] on the binding.
 * - [HotkeyAction.REPLACE_WITH_TRANSLATION] — copies selected text, translates,
 *   pastes result back via [onReplaceWithTranslation].
 * - [HotkeyAction.CYCLE_TARGET_LANGUAGE] — default LOCAL, cycles the target language.
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
     * Token ledger for the native registrations.
     *
     * Dispatch is keyed on tokens rather than actions, so a native registration the platform
     * refused to release is not dispatchable even though it is still installed.
     */
    private val registrations = HotkeyRegistrationLedger()

    /**
     * Accelerators of native registrations that remain installed although no longer wanted.
     *
     * Reported by the backend at apply time (see [ApplyResult.Degraded]), not inferred from
     * events: waiting for a stale shortcut to fire before noticing would leave cleanup
     * incomplete indefinitely. Every reconcile re-applies the desired set, which is what retries
     * the release; the set clears when an apply reports nothing outstanding.
     */
    private var degradedLeftovers: Set<String> = emptySet()

    /** Leftover native registrations outstanding after the last apply. Diagnostics/tests only. */
    internal fun outstandingLeftovers(): Set<String> = degradedLeftovers.toSet()

    /**
     * Tokens observed firing without an accepted registration.
     *
     * An event can only arrive while its native registration is installed, so observing one is
     * direct evidence that an obsolete registration is still active — a platform release that
     * failed, leaving cleanup incomplete. Recorded once per token so a held shortcut cannot
     * flood the log. Concurrent because the input dispatcher thread writes here.
     */
    private val unacceptedTokens: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // Guards against double-initialization if initialize() is called concurrently. The
    // check-and-set itself only ever happens while holding [reconcileLock], together with the
    // rest of the lifecycle decision: see [initialize]/[shutdown] for why an outside-lock
    // check is not enough.
    private val initialized = AtomicBoolean(false)
    /**
     * The complete runtime input state. Recorded even before initialization so settings
     * applied early (or a disable issued while uninitialized) are honored by initialize.
     *
     * Written only while holding [reconcileLock] (see there): every writer also triggers a
     * reconcile from the same value, so the two must move together as one transaction.
     */
    @Volatile private var runtimeState: InputRuntimeState = InputRuntimeState()

    /**
     * Serializes the whole reconciliation transaction — reading [runtimeState], planning tokens,
     * applying the native set, accepting the plan, and recording the outcome — against itself and
     * against the lifecycle mutations ([initialize], [shutdown]) that touch the same state
     * ([backend], [registrations], [lastApplied], [degradedLeftovers]).
     *
     * Without this, two reconciliations triggered from different threads can interleave their
     * plan/apply/accept steps. In this application that is not hypothetical: the settings state
     * flow drives [updateRuntimeState] from `Dispatchers.Default`, while [setPaused] and the
     * initial [updateRuntimeState] calls run on the Swing EDT. An older reconciliation that reads
     * state first but is slower to apply can then publish ([HotkeyRegistrationLedger.accept] plus
     * [lastApplied]) *after* a newer one already did, leaving the accepted token map describing a
     * native set that was never actually the last one installed — or, if a registration's native
     * release was refused (degraded) by the newer reconciliation, letting a stale reconciliation
     * that started before it resurrect that registration's identity.
     *
     * A [ReentrantLock] rather than `synchronized` for the same mutual-exclusion guarantee plus a
     * real test seam: [ReentrantLock.hasQueuedThread] lets a test observe "a second reconciliation
     * is genuinely blocked behind this lock" directly, rather than inferring it from timing.
     *
     * Held across the native `applyHotkeys`/`setRawMask` calls: those are synchronous native calls
     * that never call back into this listener on the same thread (events arrive on the backend's
     * own dispatcher thread instead), so this cannot deadlock, and serializing exactly the native
     * round trip is the whole point — only one apply may be in flight for this listener at a time.
     */
    private val reconcileLock = ReentrantLock()

    /**
     * Creates and starts the backend, then applies the current [runtimeState] to it.
     *
     * The initialized check-and-set and the entire creation are one critical section under
     * [reconcileLock], not split around it. If the check ran before the lock, this interleaving
     * would be possible: initialize flips `initialized` to true; shutdown then observes true,
     * wins the lock first, tears down (there is no backend yet), sets `initialized` false and
     * releases; initialize finally acquires the lock, creates and starts a backend, and returns
     * with a live backend while `initialized == false` — a later shutdown then returns
     * immediately and orphans that backend and its native runtime. Serializing the whole
     * decision makes the winner of the lock the winner of the lifecycle ordering, so
     * `initialized`, `backend`, the accepted registrations, `lastApplied` and creation/destruction
     * always describe one coherent generation. A duplicate concurrent initialize still draws the
     * same check-and-set and stays harmless, and a failed creation still clears `initialized`
     * under the lock so a later retry can succeed.
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
     * Applies one explicit runtime input state through the single reconciliation path.
     * Every applied-state change (bindings, enabled flag, selection, dismissal) flows here,
     * so independent setters cannot leave the native side inconsistent. The Double Ctrl
     * opt-in is always derived from the bindings themselves, never trusted from callers.
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

    /**
     * Whether some other thread is currently blocked waiting to enter a reconciliation
     * transaction (or [shutdown]). Diagnostics/tests only: this is the concrete, observable fact
     * a concurrency test waits on to prove two transactions are genuinely serialized, rather than
     * inferring it from timing.
     */
    internal fun hasQueuedReconcile(): Boolean = reconcileLock.hasQueuedThreads()

    /**
     * Stops and discards the backend, retiring every accepted registration token.
     *
     * The initialized check sits inside the lock rather than before it: reading it outside would
     * let an initialize already waiting behind this shutdown be silently dropped — its
     * check-and-set would observe `initialized` still true (this shutdown has not cleared it yet)
     * and return — instead of being serialized after the teardown and honored. Inside the lock the
     * two lifecycle transitions are strictly ordered, one winner at a time, so a shutdown that
     * runs first is followed by a real initialize (a fresh backend) rather than a lost request.
     *
     * The lock also waits for an in-flight reconcile to fully publish (or for one already blocked
     * here to find `initialized` false and no-op) before tearing down: without this, a reconcile
     * that read `backend` just before shutdown nulled it could apply/accept against an already
     * closed backend, or shutdown's own clear could be silently undone by that reconcile's later
     * `accept()`/`lastApplied` write.
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
     * The single reconciliation path: derives the native registration set and raw
     * subscriptions from current QTranslate state and applies both together.
     *
     * Must only be called while holding [reconcileLock] — every caller already does. This
     * function does not acquire it itself so that a caller can fold its own state mutation
     * (writing [runtimeState]) into the same locked transaction as the reconcile it triggers;
     * see [reconcileLock]'s own doc for why that matters.
     *
     * ### Ordering (load-bearing)
     *
     * Tokens are planned first, then the native set is applied, and only a successful apply
     * promotes the plan to accepted ([HotkeyRegistrationLedger.accept]).
     *
     * That order is what keeps the accepted map equal to the best-known live native set at every
     * instant: until the native apply returns, the previously accepted registrations really are
     * the installed ones, so events carrying their tokens are legitimate; after it returns, only
     * the new plan is accepted and every superseded token — including one whose platform release
     * failed — is retired. Accepting before the apply, or keeping retired tokens on failure, would
     * open a window in which an event dispatches against a registration that is not the accepted
     * one.
     *
     * A failed apply promotes nothing and rolls the native set back to the last working set, so a
     * single bad update cannot leave the application with no hotkeys and cannot accept a token for
     * a registration that never became active.
     *
     * ### Degraded applies
     *
     * A degraded apply — the requested set active, but some obsolete registrations still
     * installed — is accepted exactly like a clean one, because the new registrations work either
     * way and their tokens are what dispatch. The safety property does not depend on the report:
     * retiring the superseded tokens makes the leftovers non-dispatchable regardless. The report
     * itself is recorded by [recordApplyOutcome] the moment the apply returns, so diagnostics know
     * cleanup is incomplete without waiting for a stale shortcut to fire; every later reconcile
     * re-applies the desired set, which retries the release, and the record clears when an apply
     * reports nothing outstanding.
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
        // Accepted only now that the native apply reported success: the plan's tokens name exactly
        // the registrations the backend just installed, and every superseded token is retired by
        // this replacement — including tokens whose platform release failed.
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
     * Clean clears whatever a previous degraded apply recorded, so the diagnostic tracks the
     * current state rather than history. New leftovers warn once per accelerator; repeats stay
     * quiet until the set changes, because every reconcile retries the release anyway.
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

        // Defense in depth behind the token, which already identifies the exact accepted
        // registration. The configuration can have moved on since the token was issued — for
        // instance while a failed apply left the previous tokens accepted — so re-check that the
        // action is still wanted before acting.
        //
        // The accelerator is deliberately NOT re-compared against the current configuration: a
        // token already pins one exact registration, and after a failed apply rolled the native
        // set back, the accepted registration legitimately corresponds to the previous
        // accelerator. Rejecting it there would leave the user with no working hotkey at all,
        // while gaining nothing — a superseded registration cannot reach this point, because
        // retiring its token is what stops it.
        val state = runtimeState
        if (!state.effectiveHotkeysEnabled) return
        val binding = state.bindings.firstOrNull { it.action == registration.action } ?: return
        if (!binding.isEnabled || binding.scope != HotkeyScope.GLOBAL || !binding.hasBinding) return

        dispatchBinding(binding)
    }

    /**
     * Records a token that fired without an accepted registration.
     *
     * This is the observable form of an incomplete cleanup: the registration is physically
     * installed (nothing else can produce the event) but no longer accepted. It is never
     * dispatched. The next reconciliation re-applies the desired set, which is what gives the
     * native layer another chance to release the leftover.
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

    /**
     * Registration tokens that fired without being accepted, since startup.
     *
     * Diagnostics/tests only: non-empty means a native registration QTranslate retired is still
     * installed and firing.
     */
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

    /**
     * Feeds raw keyboard events into the Double Ctrl detector. Injected events (such as the
     * Robot-synthesized Copy) are fed like any other key: the detector's combination rule
     * already treats Ctrl+C as a combination, never a tap.
     */
    private fun handleRawKey(event: GlobalInputEvent.Key) {
        // QInput's own synthetic input is tagged and never counts as genuine Double Ctrl
        // input. Third-party injected input keeps flowing: remappers and clipboard tools
        // must behave exactly like physical keys here.
        if (event.selfInjected) return
        if (event.keyClass == KeyClass.CONTROL) {
            if (event.pressed) {
                detector.onControlPressed()
                return
            }
            // Only fire if global hotkeys are on (and not paused) and the binding exists,
            // is enabled, AND the user has not opted out of the double-Ctrl mechanism
            // specifically. Tracking inside the detector runs regardless, so toggling these
            // cannot desynchronize its press/release bookkeeping; pause boundaries additionally
            // reset temporal state so no tap can combine across them.
            val state = runtimeState
            val binding = state.bindings.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            val active = state.effectiveHotkeysEnabled &&
                binding != null && binding.isEnabled && binding.isDoubleCtrlEnabled
            // Monotonic milliseconds, matching the native event timestamps: the Double Ctrl
            // window is a duration, so a wall clock (which can step backwards) is the wrong
            // source. The fallback keeps the same monotonic family rather than mixing clocks.
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
            // Reported before the selection-icon check, not after. This is the only notice
            // the application gets of a press that lands in another program, and the floating
            // popups rely on it to close when the user clicks away.
            onPointerPressed(event.location)
            if (event.button == MouseButtonId.LEFT) gestureTracker.onPressed(event.location)
            return
        }
        val wasDrag = gestureTracker.onReleased()
        if (event.button != MouseButtonId.LEFT) return
        if (!wasDrag || !runtimeState.selectionIconEnabled) return

        val pointer = event.location
        scope.launch {
            delay(SELECTION_SETTLE_DELAY_MS)
            handleSelectedText { text ->
                // Re-check the flag: the user may have disabled the option while
                // the capture was in flight.
                if (text.isNotBlank() && runtimeState.selectionIconEnabled) {
                    onSelectionDetected(text, pointer)
                }
            }
        }
    }

    /**
     * Selection-dependent capture flow shared by GLOBAL hotkeys and LOCAL Swing shortcuts:
     * the triggering binding is known, capture waits for the trigger to go physically neutral,
     * then exactly one clean Copy runs and the result routes to (or away from) the action.
     *
     * Neutralization is skipped only where the backend cannot answer a synchronous key-state
     * query, exactly as before; there is never an arbitrary settle delay.
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
            // Platforms without a synchronous key-state query keep legacy behavior: capture
            // proceeds immediately. No neutralization is possible there.
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
     * Routes a capture result to an action.
     *
     * Success dispatches everywhere. [CaptureResult.NoUsableText] — a confirmed clipboard
     * change that carried nothing usable — dispatches empty text only to the actions whose UX
     * tolerates it (quick/main dialogs); selection-strict actions stay silent instead of
     * opening on empty data. Neither state claims to know whether the source application had a
     * selection; see [CaptureResult]'s doc.
     *
     * An unconfirmed or known-failed Copy ([CaptureFailure.COPY_UNCONFIRMED] /
     * [CaptureFailure.COPY_INJECTION_FAILED]) is weaker evidence still, but the lenient
     * dialogs' existing empty-input UX is preserved for it anyway — this is a dispatch mapping,
     * not a reclassification: the [CaptureResult.Failed] the strict actions see (and that gets
     * logged) stays distinct from [CaptureResult.NoUsableText]. Every other failure category
     * never dispatches anywhere.
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
     * Selection-dependent actions take the exact same path as global selection hotkeys
     * ([captureForHotkey]): wait for the trigger to read physically neutral, then exactly one
     * capture. A locally-triggered Ctrl+Shift+&lt;key&gt; is still physically held when the Swing
     * action fires, so an unneutralized Copy would reach the target as Ctrl+Shift+C.
     *
     * Non-selection actions stay immediate: they do not depend on selected text and must not wait
     * for the shortcut to be released.
     */
    fun dispatchLocalAction(binding: HotkeyBinding) = dispatchBinding(binding)

    /**
     * The single dispatch decision shared by the global hotkey path and the LOCAL InputMap path.
     *
     * Selection-dependent actions always run through [captureForHotkey], so both scopes obey the
     * same deterministic trigger-neutralization contract; everything else is immediate.
     */
    private fun dispatchBinding(binding: HotkeyBinding) {
        if (binding.action in SELECTIVE_ACTIONS) scope.launch { captureForHotkey(binding) }
        else dispatchImmediate(binding.action)
    }

    /**
     * Dispatches a non-selection action immediately.
     *
     * Selection-dependent actions never reach here — both scopes route them through
     * [captureForHotkey] first — so their branches are deliberately empty. Dispatching one here
     * would inject a Copy without waiting for the trigger to go neutral, which is exactly the
     * contamination this path exists to prevent.
     *
     * Focus/UI actions are LOCAL-only and are handled by MainAppFrame's InputMap, which owns the
     * dialogs, clipboard and content view they need.
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
        // Mouse-gesture and double-tap paths predate trigger neutralization and have no
        // native trigger to wait on; their blank-on-anything-but-success behavior is unchanged.
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
     * Invokes the configured [CopyInjector] exactly once and reports its result. [CopyInjector]
     * owns its own complete attempt including any internal fallback (see that interface's doc):
     * this must never itself retry on a `false` result, or a bare [RobotCopyInjector] failing
     * (the default before native is available) would be followed by a second, unrelated Robot
     * attempt. The clipboard observation downstream stays the actual completion signal either
     * way — this return value only lets [SelectionCapture] tell a known-failed attempt apart
     * from one that might have landed, instead of treating both identically as silence.
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

        /**
         * Actions whose UX tolerates genuinely empty input (dialogs the user can type into).
         * Every other selective action stays silent on [CaptureResult.NoUsableText] and on
         * [CaptureResult.Failed] reasons in [INCONCLUSIVE_AS_EMPTY] instead of opening on empty
         * data; every other [CaptureResult.Failed] reason never dispatches anywhere.
         */
        val LENIENT_ACTIONS = setOf(
            HotkeyAction.SHOW_QUICK_TRANSLATE,
            HotkeyAction.SHOW_MAIN_WINDOW
        )

        /**
         * [CaptureFailure] reasons that, for [LENIENT_ACTIONS] only, are dispatched as empty
         * text rather than withheld — preserving the dialogs' pre-existing empty-input UX for
         * what is, if anything, weaker evidence than [CaptureResult.NoUsableText]. This is
         * purely a dispatch-layer mapping: the underlying [CaptureResult.Failed] stays distinct
         * from [CaptureResult.NoUsableText], and strict actions never see this mapping.
         */
        val INCONCLUSIVE_AS_EMPTY = setOf(
            CaptureFailure.COPY_UNCONFIRMED,
            CaptureFailure.COPY_INJECTION_FAILED,
        )

        /** Grace period after mouse release so the source app can settle its selection. */
        const val SELECTION_SETTLE_DELAY_MS = 80L
    }
}
