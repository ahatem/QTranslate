package com.github.ahatem.qtranslate.core.workspace.translation

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Deterministic ownership interleavings for the secondary-output slot: a stale claim cannot displace the live job. */
class ExtraOutputWorkOwnerTest {

    @Test
    fun `a stale claim cannot displace the current job`() {
        val owner = ExtraOutputWorkOwner()
        val staleClaim = owner.claim()
        val currentClaim = owner.claim()
        val currentJob = Job()

        assertFalse(
            owner.register(staleClaim, Job()),
            "the older caller must not take over a slot the newer claim owns"
        )
        assertNull(owner.detach(), "the stale candidate must never have entered the slot")
        assertTrue(owner.register(currentClaim, currentJob))
        assertSame(currentJob, owner.detach(), "the live job is the one a cancel would reach")
    }

    @Test
    fun `claiming detaches the previous job for cancellation`() {
        val owner = ExtraOutputWorkOwner()
        val first = owner.claim()
        val firstJob = Job()
        assertTrue(owner.register(first, firstJob))

        val second = owner.claim()
        assertSame(firstJob, second.previousJob)
        assertNull(owner.detach(), "the previous job is no longer the current operation")

        val secondJob = Job()
        assertTrue(owner.register(second, secondJob))
        assertFalse(owner.isCurrent(first.generation))
        assertTrue(owner.isCurrent(second.generation))
        assertSame(secondJob, owner.detach())
    }

}
