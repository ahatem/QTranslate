package com.github.ahatem.qtranslate.core.plugin.lifecycle

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns plugin lifecycle transitions: at most one transition per plugin runs at a time.
 *
 * The [com.github.ahatem.qtranslate.api.plugin.Plugin] contract promises implementations that
 * all lifecycle methods are called sequentially, so they need no internal thread-safety. The
 * callers of those methods are not sequential by themselves: startup discovery, the settings
 * dialog (enable/disable/install/uninstall/resolve, launched from its own scope), and application
 * shutdown all drive transitions from independent dispatchers, and every one of them touches the
 * same container — the plugin instance, its class loader, its coroutine scope, and its snapshot.
 *
 * This owner enforces the contract with the smallest mechanism that does not serialize unrelated
 * plugins: one slot per plugin id. A slot is a mutex plus a rejection flag.
 *
 * ### Transition contract
 * - **Serialized per plugin.** Two transitions for the same id never interleave; transitions for
 *   different ids run concurrently.
 * - **Revalidate on entry.** The registry is re-read after the slot is acquired, so a transition
 *   queued behind an uninstall finds the container gone and no-ops instead of acting on a
 *   superseded instance.
 * - **Accepted means completed.** Once a transition starts it runs under [NonCancellable]: a
 *   caller whose coroutine is cancelled (the settings dialog closing mid-transition) cannot
 *   abandon a half-finished teardown. Individual callbacks are normally bounded by the timeouts
 *   in [PluginLifecycleHandler]; a callback that blocks a thread outright ignores them and is
 *   only escaped by the settle bound below.
 * - **Shutdown rejects, then settles within one global bound.** [shutdown] closes the gate, then
 *   waits concurrently for every in-flight transition against a single deadline, so N stuck
 *   plugins cost one settle bound rather than N. A slot that cannot be settled is reported to
 *   the caller instead of being torn down from the outside: its accepted transition owns the
 *   instance until its callback returns, and finalizes it through [onLateTransition] at that
 *   point. Application shutdown therefore never calls `onDisable()`/`shutdown()` while an
 *   accepted callback on the same instance is still running.
 *
 * The same shape as the global-input reconcile lock in `MainGlobalKeyListener`: the whole
 * transition is the critical section, and the state check lives inside it.
 */
internal class PluginTransitionOwner(
    private val logger: Logger,
    /**
     * How long shutdown waits for in-flight transitions before declaring them stragglers.
     * It is a latency budget for the whole shutdown, not per slot: every slot is settled
     * concurrently. It covers the ordinary initialize/enable path, but a legal
     * `validateServices` transition can exceed it because its ceiling follows the user's own
     * network timeouts; such a plugin is safely treated as a straggler and finalizes itself.
     * Injectable so a test can exercise that path without waiting the real bound.
     */
    private val settleTimeoutMs: Long = DEFAULT_SETTLE_TIMEOUT_MS,
    /**
     * Called before the slot is released when an accepted transition completes after shutdown
     * began. It runs inside the slot, after the transition's own callbacks, so teardown of a
     * straggler can never overlap the callback that outlived the settle bound.
     */
    private val onLateTransition: suspend (pluginId: String) -> Unit = {}
) {

    internal companion object {
        /**
         * The documented callback timeouts (init 30s + declared services 15s + enable 15s +
         * enable services 15s) already sum to this value, and a transition can carry more work
         * (context creation, validation bounded by the user's network settings, the install
         * copy). Any transition at or beyond that ceiling is therefore a straggler; it is safe,
         * not abandoned, and finalizes itself through the late-finalization hook.
         */
        const val DEFAULT_SETTLE_TIMEOUT_MS = 75_000L
    }

    private class Slot {
        val mutex = Mutex()
        @Volatile var rejected = false
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    @Volatile
    private var shuttingDown = false

    /**
     * Runs one transition for [pluginId], serialized against every other transition of the same
     * plugin and against shutdown.
     *
     * Returns the block's result, or `null` when the transition was not accepted — after
     * [shutdown] has run, or while it is settling in-flight work. Callers treat `null` as a
     * no-op (or, for install, as a refusal).
     */
    suspend fun <T> transition(pluginId: String, block: suspend () -> T): T? {
        val slot = slots.computeIfAbsent(pluginId) { Slot() }
        slot.mutex.withLock {
            if (shuttingDown || slot.rejected) return null
            return withContext(NonCancellable) {
                try {
                    block()
                } finally {
                    // Only a transition that completed after the gate closed needs the late
                    // finalizer; the slot is still held, so no callback can overlap it.
                    if (shuttingDown) onLateTransition(pluginId)
                }
            }
        }
    }

    /**
     * Closes the gate and settles accepted work within one global budget.
     *
     * New transitions are rejected from this point on. Every in-flight transition is allowed to
     * finish: all slots are awaited concurrently against a single [settleTimeoutMs] deadline,
     * and the rejection flag is set while holding each slot, so a transition queued behind one
     * in flight can never start afterwards.
     *
     * @return the ids whose transitions did not settle within the budget. They remain owned by
     * their in-flight transition, which finalizes them through [onLateTransition] when its
     * callback returns; the caller must not tear them down itself.
     */
    suspend fun shutdown(): Set<String> {
        shuttingDown = true
        val settled = ConcurrentHashMap.newKeySet<String>()

        val waiters = ArrayList<Pair<String, Job>>(slots.size)
        coroutineScope {
            slots.forEach { (pluginId, slot) ->
                waiters += pluginId to launch {
                    slot.mutex.withLock { slot.rejected = true }
                    settled += pluginId
                }
            }
            val jobs = waiters.map { (_, job) -> job }
            val finished = withTimeoutOrNull(settleTimeoutMs) {
                jobs.joinAll()
                true
            }
            if (finished == null) {
                // The budget is spent for the whole shutdown: stop waiting, let the remaining
                // transitions keep running, and report them as stragglers.
                jobs.forEach { it.cancel() }
                jobs.joinAll()
            }
        }

        val stragglers = waiters.mapTo(LinkedHashSet()) { (pluginId, _) -> pluginId }
        stragglers.removeAll(settled)
        stragglers.forEach { pluginId ->
            slots[pluginId]?.rejected = true
            logger.warn(
                "Plugin '$pluginId' outlived the shutdown settle (${settleTimeoutMs / 1000}s); " +
                        "it will finalize itself when the transition returns."
            )
        }
        return stragglers
    }

    /** Whether shutdown has begun; a transition that outlived the settle sees this. */
    fun isShutdown(): Boolean = shuttingDown
}
