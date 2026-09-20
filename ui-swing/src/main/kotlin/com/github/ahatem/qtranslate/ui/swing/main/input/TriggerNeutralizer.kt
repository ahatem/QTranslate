package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import io.github.ahatem.qinput.AwtPortableKeys
import kotlinx.coroutines.delay

/**
 * Waits until a trigger chord is physically neutral before synthetic Copy begins.
 *
 * Neutral means the trigger's main key AND every modifier that could contaminate a Ctrl+C
 * observation are all physically up, read through a focus-independent synchronous query
 * (`GetAsyncKeyState` on Windows). The Copy modifier (Ctrl) itself is exempt: holding it is
 * harmless because injection preserves requested Ctrl and the target still observes Ctrl+C,
 * while a held Shift would turn the copy into Ctrl+Shift+C at the target.
 *
 * Keys are portable QInput usages, not platform virtual keys; the backend translates them.
 * The watched set is queried as one batch, so a poll costs one backend query rather than one per
 * key (on X11 that is one server round-trip instead of one per key).
 *
 * The wait is bounded and exists only for the lifetime of one pending capture: there is no
 * idle polling loop. On timeout the caller fails the capture cleanly instead of injecting
 * a contaminated copy.
 */
class TriggerNeutralizer(
    private val anyDown: (usages: List<Int>) -> Boolean,
    private val triggerUsages: (binding: HotkeyBinding) -> List<Int> = {
        AwtPortableKeys.neutralizationSet(it.keyCode, it.modifiers)
    },
    private val pollMs: Long = DEFAULT_POLL_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /**
     * Returns true once every watched key reads up. Query failures count as down (fail
     * closed): an unobservable trigger can never be proven neutral.
     */
    suspend fun awaitNeutral(binding: HotkeyBinding): Boolean {
        val watched = try {
            triggerUsages(binding)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return awaitNeutralUsages(watched)
    }

    /**
     * Waits for an explicit portable-usage set to read up. Used by paste injection, whose
     * watched set is derived from the paste chord rather than a hotkey binding.
     */
    suspend fun awaitNeutralUsages(watched: List<Int>): Boolean {
        val distinct = watched.distinct()
        var waited = 0L
        while (true) {
            val stillDown = runCatching { anyDown(distinct) }.getOrDefault(true)
            if (!stillDown) return true
            if (waited >= timeoutMs) return false
            delay(pollMs)
            waited += pollMs
        }
    }

    companion object {
        const val DEFAULT_POLL_MS = 20L
        /** Deliberately holding a shortcut for well over a second must still succeed. */
        const val DEFAULT_TIMEOUT_MS = 2000L
    }
}
