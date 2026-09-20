package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction

/**
 * Opaque identity of one accepted native hotkey registration.
 *
 * Identifies the accepted action **and** accelerator, not just the action: if the platform
 * fails to release a superseded registration, both the old and new accelerators can be live
 * for the same action, and only the pair distinguishes them.
 */
@JvmInline
value class HotkeyRegistrationToken(val value: Long)

/** Structural equality is what lets an unchanged registration keep its token across reconciles. */
data class GlobalRegistration(
    val action: HotkeyAction,
    val accelerator: String,
)

data class PlannedRegistration(
    val token: HotkeyRegistrationToken,
    val registration: GlobalRegistration,
)

/**
 * Allocates and tracks the tokens of accepted native registrations.
 *
 * Tracks a token per accepted registration, rather than mapping events to actions, so a
 * registration the platform fails to release keeps firing but is never dispatchable: [accept]
 * retires every token outside the new plan.
 *
 * [plan] only assigns tokens; [accept] is what commits them, so a failed native apply cannot
 * leave a half-accepted map. Tokens are never reused, so a superseded registration's events
 * stay recognisably stale.
 */
class HotkeyRegistrationLedger(private val mint: () -> Long = monotonicTokenMinter()) {

    /**
     * Volatile: [accept] writes from the caller's thread (settings/EDT); [registrationFor]
     * reads from the input backend's dispatcher thread. The map is never mutated after
     * publication.
     */
    @Volatile
    private var accepted: Map<HotkeyRegistrationToken, GlobalRegistration> = emptyMap()

    /** The accepted registration a token belongs to, or null when it is retired or unknown. */
    fun registrationFor(token: HotkeyRegistrationToken): GlobalRegistration? = accepted[token]

    /** The token currently accepted for [action], if any. For diagnostics and tests. */
    fun tokenFor(action: HotkeyAction): HotkeyRegistrationToken? =
        accepted.entries.firstOrNull { it.value.action == action }?.key

    /** The currently accepted registrations, in no particular order. */
    fun acceptedRegistrations(): Collection<GlobalRegistration> = accepted.values

    /** Reuses the token of an already-accepted exact match; mints a new one otherwise. */
    fun plan(desired: List<GlobalRegistration>): List<PlannedRegistration> =
        desired.map { registration ->
            val reused = accepted.entries.firstOrNull { it.value == registration }?.key
            PlannedRegistration(reused ?: HotkeyRegistrationToken(mint()), registration)
        }

    /** Commits [plan] as the accepted set; call only after the native apply succeeds. */
    fun accept(plan: List<PlannedRegistration>) {
        accepted = plan.associate { it.token to it.registration }
    }

    /** Retires every token, so nothing dispatches. Used when the runtime shuts down. */
    fun clear() {
        accepted = emptyMap()
    }
}

/**
 * Tokens start at 1 and only increase, so a retired token is never reissued.
 *
 * The counter is atomic: correct under concurrent calls on its own, independent of
 * [MainGlobalKeyListener]'s reconcile lock.
 */
private fun monotonicTokenMinter(): () -> Long {
    val next = java.util.concurrent.atomic.AtomicLong(1L)
    return { next.getAndIncrement() }
}
