package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Token ledger semantics: which registrations are dispatchable at any moment.
 *
 * These are the properties the changed-accelerator case depends on: the reason an action alone
 * cannot be the native identity.
 */
class HotkeyRegistrationLedgerTest {

    private fun registration(action: HotkeyAction, accelerator: String) =
        GlobalRegistration(action, accelerator)

    @Test
    fun `planned tokens are not accepted until the plan is accepted`() {
        val ledger = HotkeyRegistrationLedger()

        val plan = ledger.plan(listOf(registration(HotkeyAction.SHOW_IMAGES, "control+shift+KeyQ")))

        assertEquals(1, plan.size)
        // Planning is pure: nothing is dispatchable before the native apply is reported.
        assertNull(ledger.registrationFor(plan.single().token))
        assertNull(ledger.tokenFor(HotkeyAction.SHOW_IMAGES))

        ledger.accept(plan)
        assertEquals(
            registration(HotkeyAction.SHOW_IMAGES, "control+shift+KeyQ"),
            ledger.registrationFor(plan.single().token)
        )
    }

    /** An unchanged registration keeps its token, so reconciles do not churn native identity. */
    @Test
    fun `unchanged registration reuses its token`() {
        val ledger = HotkeyRegistrationLedger()
        val desired = listOf(registration(HotkeyAction.SHOW_IMAGES, "control+shift+KeyQ"))

        val first = ledger.plan(desired).single()
        ledger.accept(listOf(first))
        val second = ledger.plan(desired).single()

        assertEquals(first.token, second.token)
    }

    /**
     * The changed-accelerator case: the same action on a new accelerator must get a different
     * token, and the old one must stop being accepted.
     */
    @Test
    fun `changed accelerator gets a new token and retires the old one`() {
        val ledger = HotkeyRegistrationLedger()

        val old = ledger.plan(listOf(registration(HotkeyAction.SHOW_IMAGES, "control+shift+KeyQ"))).single()
        ledger.accept(listOf(old))

        val new = ledger.plan(listOf(registration(HotkeyAction.SHOW_IMAGES, "control+F9"))).single()
        ledger.accept(listOf(new))

        assertNotEquals(old.token, new.token)
        assertNull(ledger.registrationFor(old.token), "the superseded token must not resolve")
        assertEquals(
            registration(HotkeyAction.SHOW_IMAGES, "control+F9"),
            ledger.registrationFor(new.token)
        )
    }

    /** A registration that left and later returned gets a fresh token, never the retired one. */
    @Test
    fun `a re-added registration does not revive its old token`() {
        val ledger = HotkeyRegistrationLedger()
        val desired = listOf(registration(HotkeyAction.SHOW_IMAGES, "control+shift+KeyQ"))

        val first = ledger.plan(desired).single()
        ledger.accept(listOf(first))

        ledger.accept(emptyList())
        assertNull(ledger.registrationFor(first.token))

        val revived = ledger.plan(desired).single()
        assertNotEquals(first.token, revived.token)
        ledger.accept(listOf(revived))
        assertNull(ledger.registrationFor(first.token))
    }

    /** Accepting an empty plan retires everything, which is what disabling relies on. */
    @Test
    fun `accepting an empty plan retires every token`() {
        val ledger = HotkeyRegistrationLedger()
        val plan = ledger.plan(
            listOf(
                registration(HotkeyAction.SHOW_IMAGES, "control+shift+KeyQ"),
                registration(HotkeyAction.OPEN_OCR, "control+KeyI")
            )
        )
        ledger.accept(plan)

        ledger.accept(emptyList())

        assertTrue(ledger.acceptedRegistrations().isEmpty())
        assertNull(ledger.tokenFor(HotkeyAction.SHOW_IMAGES))
        assertNull(ledger.tokenFor(HotkeyAction.OPEN_OCR))
        plan.forEach { assertNull(ledger.registrationFor(it.token)) }
    }

    /** Clearing (shutdown) retires everything without needing a plan. */
    @Test
    fun `clear retires every token`() {
        val ledger = HotkeyRegistrationLedger()
        val plan = ledger.plan(listOf(registration(HotkeyAction.SHOW_IMAGES, "control+shift+KeyQ")))
        ledger.accept(plan)

        ledger.clear()

        assertNull(ledger.registrationFor(plan.single().token))
    }

    /** Tokens are never reused, so a stale event can never match a later registration. */
    @Test
    fun `tokens are never reused across plans`() {
        val ledger = HotkeyRegistrationLedger()
        val seen = mutableSetOf<HotkeyRegistrationToken>()

        repeat(5) { index ->
            val plan = ledger.plan(listOf(registration(HotkeyAction.SHOW_IMAGES, "control+Key$index")))
            plan.forEach { assertTrue(seen.add(it.token), "token ${it.token} was reused") }
            ledger.accept(plan)
        }
    }

    /** Two registrations in one plan carry distinct tokens. */
    @Test
    fun `tokens are distinct within a plan`() {
        val ledger = HotkeyRegistrationLedger()
        val plan = ledger.plan(
            listOf(
                registration(HotkeyAction.SHOW_IMAGES, "control+shift+KeyQ"),
                registration(HotkeyAction.OPEN_OCR, "control+KeyI"),
                registration(HotkeyAction.SHOW_DICTIONARY, "control+KeyD")
            )
        )

        assertEquals(plan.size, plan.map { it.token }.toSet().size)
    }

    /** Minting is injectable so token assignment is deterministic under test. */
    @Test
    fun `token minting can be injected`() {
        var next = 100L
        val ledger = HotkeyRegistrationLedger { next++ }
        val plan = ledger.plan(
            listOf(
                registration(HotkeyAction.SHOW_IMAGES, "control+shift+KeyQ"),
                registration(HotkeyAction.OPEN_OCR, "control+KeyI")
            )
        )

        assertEquals(listOf(100L, 101L), plan.map { it.token.value })
    }

    /**
     * A registration whose action is unchanged but whose accelerator differs is *not* the same
     * registration: this is the case an ordinal-keyed map could not distinguish.
     */
    @Test
    fun `same action on a different accelerator is a different registration`() {
        val a = registration(HotkeyAction.SHOW_IMAGES, "control+shift+KeyQ")
        val b = registration(HotkeyAction.SHOW_IMAGES, "control+F9")

        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    /**
     * The default minter must not hand out the same token twice under concurrent [plan] calls.
     * `MainGlobalKeyListener` additionally serializes every call to [HotkeyRegistrationLedger.plan]
     * through its own reconcile lock, but the minter must not depend on that external discipline:
     * this drives the ledger directly, with no such lock, using a [CyclicBarrier] so every thread's
     * `plan` call is lined up to start together rather than relying on scheduling luck or a sleep.
     */
    @Test
    fun `concurrent planning never mints a duplicate token`() {
        val ledger = HotkeyRegistrationLedger()
        val threadCount = 16
        val barrier = CyclicBarrier(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val futures = (0 until threadCount).map { index ->
                pool.submit<List<HotkeyRegistrationToken>> {
                    barrier.await(10, TimeUnit.SECONDS)
                    // Each thread plans one registration nothing else could already have
                    // accepted, so a correct minter must hand out a fresh token every time.
                    ledger.plan(listOf(registration(HotkeyAction.SHOW_IMAGES, "control+Key$index")))
                        .map { it.token }
                }
            }
            val allTokens = futures.flatMap { it.get(10, TimeUnit.SECONDS) }
            assertEquals(threadCount, allTokens.size)
            assertEquals(
                threadCount,
                allTokens.toSet().size,
                "concurrent planning minted a duplicate token: $allTokens"
            )
        } finally {
            pool.shutdown()
        }
    }
}
