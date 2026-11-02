package com.github.ahatem.qtranslate.ui.swing.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StagedSecretTest {
    @Test
    fun `successful persist advances the baseline`() = runTest {
        val secret = StagedSecret("old")
        secret.stage("new")
        var written = ""

        assertEquals(null, secret.persist { written = it })
        assertEquals("new", written)
        assertFalse(secret.hasPendingChanges())
    }

    @Test
    fun `failed persist keeps the new value pending and retryable`() = runTest {
        val secret = StagedSecret("old")
        secret.stage("new")
        var attempts = 0

        assertTrue(secret.persist {
            attempts++
            error("write failed")
        } is IllegalStateException)
        assertTrue(secret.hasPendingChanges())
        assertEquals("new", secret.read())

        assertEquals(null, secret.persist { attempts++ })
        assertEquals(2, attempts)
        assertFalse(secret.hasPendingChanges())
    }

    @Test
    fun `discard restores the persisted value`() {
        val secret = StagedSecret("old")
        secret.stage("new")

        secret.discard()

        assertEquals("old", secret.read())
        assertFalse(secret.hasPendingChanges())
    }
    @Test fun `later edit remains pending when earlier snapshot completes`() = runTest {
        val secret = StagedSecret("old")
        secret.stage("first")
        val snapshot = secret.snapshot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val write = async { secret.persistSnapshot(snapshot) { entered.complete(Unit); release.await() } }
        entered.await()
        secret.stage("later")
        release.complete(Unit)
        assertEquals(null, write.await())
        assertEquals("later", secret.read())
        assertTrue(secret.hasPendingChanges())
    }

    @Test fun `late initial load cannot replace user edit`() {
        val secret = StagedSecret()
        secret.stage("new")
        secret.initialize("old")
        assertEquals("new", secret.read())
        assertTrue(secret.hasPendingChanges())
    }

    @Test fun `late load retains baseline when user cleared secret`() {
        val secret = StagedSecret()
        secret.stage("temporary")
        secret.stage("")
        secret.initialize("old")
        assertEquals("", secret.read())
        assertTrue(secret.hasPendingChanges())
    }

}
