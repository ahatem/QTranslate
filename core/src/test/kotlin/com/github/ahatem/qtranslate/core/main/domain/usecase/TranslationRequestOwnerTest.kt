package com.github.ahatem.qtranslate.core.main.domain.usecase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic interleavings for primary-request ownership.
 *
 * The stale-caller race needs no threads: the exact order — A claims, B claims and registers,
 * A resumes — is spelled out, and the assertions prove A can neither replace nor cancel B.
 */
class TranslationRequestOwnerTest {

    @Test
    fun `a stale caller resumes after the newer request registered and cannot cancel it`() = runTest {
        val owner = TranslationRequestOwner()

        // Both callers claim in the order a race can produce: A first, then B.
        val claimA = owner.claim()
        val claimB = owner.claim()

        val bCompleted = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Unit>()
        val jobB = launch {
            bRelease.await()
            bCompleted.complete(Unit)
        }
        assertTrue(owner.install(claimB, jobB))
        assertFalse(owner.isCurrent(claimA.requestId), "B owns the request now")

        // A resumes and tries to take the slot back. It is refused, so B's job is untouched.
        val jobA = launch(start = CoroutineStart.LAZY) { Unit }
        assertFalse(
            owner.install(claimA, jobA),
            "a stale claim must not replace the current job"
        )
        jobA.cancel()
        assertTrue(jobB.isActive, "B's job must survive A's late resume")

        bRelease.complete(Unit)
        advanceUntilIdle()
        assertTrue(bCompleted.isCompleted, "B must still complete")
        assertTrue(owner.isCurrent(claimB.requestId))
    }

    @Test
    fun `claiming detaches the job it supersedes, so cancel never reaches a stale one`() = runTest {
        val owner = TranslationRequestOwner()
        val claimA = owner.claim()
        val jobA = Job()
        assertTrue(owner.install(claimA, jobA))

        val claimB = owner.claim()
        assertSame(jobA, claimB.previousJob, "the superseding claim owns the old job's cancellation")
        assertFalse(owner.isCurrent(claimA.requestId))

        val jobB = Job()
        assertTrue(owner.install(claimB, jobB))
        val canceled = owner.cancel()
        assertSame(jobB, canceled.previousJob, "cancel reaches the live job, not the superseded one")
    }

    @Test
    fun `publication follows both request identity and generation`() = runTest {
        val owner = TranslationRequestOwner()
        val claim = owner.claim()

        assertEquals("published", owner.withCurrentRequest(claim.requestId) { "published" })
        assertEquals("compared", owner.withCurrentGeneration(claim.generation) { "compared" })

        val newer = owner.claim()
        assertEquals(null, owner.withCurrentRequest(claim.requestId) { "stale" })
        assertEquals(null, owner.withCurrentGeneration(claim.generation) { "stale" })
        assertTrue(owner.isCurrent(newer.requestId))
    }
}
