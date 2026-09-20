package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction

/**
 * Opaque identity of one accepted native hotkey registration.
 *
 * It is what the backend echoes back when a shortcut fires, so it must identify the *exact*
 * registration that was accepted — its action **and** its accelerator — not merely which action
 * it represents. An action alone is not a safe identity: if the platform refuses to release an
 * obsolete registration, the old accelerator and its replacement can both be installed for the
 * same action, and both would then be indistinguishable.
 *
 * Opaque on purpose: the only thing a caller may do with a token is hand it back to the ledger.
 * It carries no meaning outside the process and is deliberately never persisted.
 */
@JvmInline
value class HotkeyRegistrationToken(val value: Long)

/**
 * One global registration: the action it triggers and the exact accelerator it is bound to.
 *
 * Structural equality is what lets an unchanged registration keep its token across reconciles.
 */
data class GlobalRegistration(
    val action: HotkeyAction,
    val accelerator: String,
)

/** A [GlobalRegistration] paired with the token it will carry natively. */
data class PlannedRegistration(
    val token: HotkeyRegistrationToken,
    val registration: GlobalRegistration,
)

/**
 * Allocates and tracks the tokens of accepted native registrations.
 *
 * ### Why this exists
 *
 * The native registration set is replaced on every reconciliation, but the platform's release of
 * a superseded registration can fail. When that happens the obsolete registration stays installed
 * and keeps firing, so the application must be able to tell "the registration I currently accept"
 * apart from "a registration the platform still holds". Tracking a token per accepted
 * registration — rather than mapping events to actions — makes a superseded registration
 * non-dispatchable even while it remains physically active, because [accept] retires every token
 * outside the new plan.
 *
 * ### Lifetime rules
 *
 * - A token is issued by [plan] and only becomes accepted when [accept] is called for that plan.
 *   Planning is pure, so a failed native apply cannot leave a half-accepted map behind.
 * - An unchanged registration (same action *and* same accelerator) keeps its token, so repeated
 *   reconciles do not churn the native registrations.
 * - A changed accelerator, a new registration, or one that left and later returned gets a **fresh**
 *   token: [accept] replaces the map wholesale, so a retired registration's token is never
 *   re-accepted even if the same accelerator is wanted again later.
 * - Tokens are never reused within a process, which is what makes a superseded registration's
 *   events recognisably stale rather than accidentally matching a live registration.
 *
 * [mint] is injectable so token assignment is deterministic under test.
 */
class HotkeyRegistrationLedger(private val mint: () -> Long = monotonicTokenMinter()) {

    /**
     * The accepted registrations, replaced atomically by [accept].
     *
     * Volatile because [accept] runs on the caller's thread (settings/EDT) while [registrationFor]
     * runs on the input backend's dispatcher thread. The map itself is never mutated after
     * publication, so publishing the reference is enough.
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

    /**
     * Assigns a token to every desired registration without changing any state.
     *
     * Reuses the existing token when the exact registration is already accepted, so an unchanged
     * registration survives reconciliation; mints a new one otherwise.
     */
    fun plan(desired: List<GlobalRegistration>): List<PlannedRegistration> =
        desired.map { registration ->
            val reused = accepted.entries.firstOrNull { it.value == registration }?.key
            PlannedRegistration(reused ?: HotkeyRegistrationToken(mint()), registration)
        }

    /**
     * Makes [plan] the accepted set.
     *
     * Called only after the native apply reported success: every token outside the plan is retired
     * by this replacement, and nothing is accepted that was not just applied.
     */
    fun accept(plan: List<PlannedRegistration>) {
        accepted = plan.associate { it.token to it.registration }
    }

    /** Retires every token, so nothing dispatches. Used when the runtime shuts down. */
    fun clear() {
        accepted = emptyMap()
    }
}

/**
 * Tokens start at 1 and only increase, so a registration can never inherit the token of one that
 * was retired earlier in the same process.
 *
 * The counter is atomic so [HotkeyRegistrationLedger.plan] can never mint the same token twice
 * under concurrent calls. [MainGlobalKeyListener] additionally serializes every call to [plan]
 * through its own reconcile lock, but this minter does not rely on that discipline: it is correct
 * on its own for any caller, including a test that exercises it directly and concurrently.
 */
private fun monotonicTokenMinter(): () -> Long {
    val next = java.util.concurrent.atomic.AtomicLong(1L)
    return { next.getAndIncrement() }
}
