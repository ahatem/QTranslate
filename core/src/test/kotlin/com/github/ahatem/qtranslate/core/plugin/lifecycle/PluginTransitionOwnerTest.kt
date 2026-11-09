package com.github.ahatem.qtranslate.core.plugin.lifecycle

import com.github.ahatem.qtranslate.core.plugin.FixtureLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic shutdown-budget tests.
 *
 * `runTest` runs the owner's settle timeout on virtual time, so the elapsed virtual time is an
 * exact structural fact: one budget for all stuck slots, or N budgets if the settle were
 * sequential. No wall-clock assertions.
 */
class PluginTransitionOwnerTest {

    @Test
    fun `shutdown charges one global budget for every stuck plugin`() = runTest {
        val budget = 5_000L
        val owner = PluginTransitionOwner(FixtureLogger, budget)
        val ids = (1..5).map { "stuck-$it" }
        val entered = ids.associateWith { CompletableDeferred<Unit>() }
        val release = CompletableDeferred<Unit>()

        val jobs = ids.map { id ->
            backgroundScope.launch {
                owner.transition(id) {
                    entered.getValue(id).complete(Unit)
                    release.await()
                }
            }
        }
        ids.forEach { entered.getValue(it).await() }

        val startedAt = testScheduler.currentTime
        val stragglers = owner.shutdown()

        assertEquals(ids.toSet(), stragglers, "every parked slot is reported exactly once")
        assertEquals(
            budget,
            testScheduler.currentTime - startedAt,
            "N stuck plugins must share one settle budget, not N"
        )

        release.complete(Unit)
        jobs.joinAll()
    }

    @Test
    fun `shutdown settles idle slots immediately and rejects new transitions`() = runTest {
        val owner = PluginTransitionOwner(FixtureLogger, 5_000L)
        val completed = CompletableDeferred<Unit>()
        launch { owner.transition("idle") { completed.complete(Unit) } }
        completed.await()

        val startedAt = testScheduler.currentTime
        val stragglers = owner.shutdown()

        assertTrue(stragglers.isEmpty(), "an idle slot settles without spending the budget")
        assertEquals(0L, testScheduler.currentTime - startedAt)
        assertEquals(null, owner.transition("idle") { Unit }, "transitions after shutdown are refused")
    }
}
