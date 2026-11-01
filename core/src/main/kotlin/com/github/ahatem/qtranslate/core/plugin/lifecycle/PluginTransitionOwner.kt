package com.github.ahatem.qtranslate.core.plugin.lifecycle

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.NonCancellable
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
 *   abandon a half-finished teardown. Plugin callbacks themselves remain bounded by the
 *   timeouts in [PluginLifecycleHandler].
 * - **Shutdown settles, then rejects.** [shutdown] closes the gate and waits out every in-flight
 *   transition (it acquires each slot), so accepted work finishes before teardown begins; every
 *   transition requested afterwards is rejected with `null` rather than silently started.
 *
 * The same shape as the global-input reconcile lock in `MainGlobalKeyListener`: the whole
 * transition is the critical section, and the state check lives inside it.
 */
internal class PluginTransitionOwner(
    private val logger: Logger,
    /**
     * How long shutdown waits for one in-flight transition before declaring it a straggler.
     * Above the worst case a legal transition can take — initialize 30s plus two bounded
     * service getters plus enable 15s — so only a genuinely stuck plugin takes the straggler
     * path. Injectable so a test can exercise that path without waiting the real bound.
     */
    private val settleTimeoutMs: Long = DEFAULT_SETTLE_TIMEOUT_MS
) {

    internal companion object {
        /**
         * Init (30s) + declared services (15s) + enable (15s) + enable services (15s) is the
         * legal worst case; the default leaves headroom above it. (A blocked constructor is the
         * one unbounded piece; it can only be escaped by this bound.)
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
            return withContext(NonCancellable) { block() }
        }
    }

    /**
     * Closes the gate and settles accepted work.
     *
     * New transitions are rejected from this point on. Transitions already accepted are allowed
     * to finish: acquiring each slot waits for its in-flight transition, and the rejection flag
     * is set while holding the slot, so a transition queued behind one in flight can never
     * start afterwards. Each wait is bounded — a callback that blocks a thread outright must not
     * hold application exit — and a timed-out slot is still rejected so it can accept nothing
     * more; its caller finalizes a late registration (see the transition guards in the manager
     * and installer).
     */
    suspend fun shutdown() {
        shuttingDown = true
        slots.forEach { (pluginId, slot) ->
            val settled = withTimeoutOrNull(settleTimeoutMs) {
                slot.mutex.withLock { slot.rejected = true }
            }
            if (settled == null) {
                slot.rejected = true
                logger.warn(
                    "Plugin '$pluginId' outlived the shutdown settle (${settleTimeoutMs / 1000}s); " +
                            "it will finalize itself when the transition returns."
                )
            }
        }
    }

    /** Whether shutdown has begun; a transition that outlived the settle sees this. */
    fun isShutdown(): Boolean = shuttingDown
}
