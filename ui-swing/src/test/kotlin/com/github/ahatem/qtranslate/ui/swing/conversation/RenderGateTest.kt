package com.github.ahatem.qtranslate.ui.swing.conversation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenderGateTest {

    @Test
    fun `pointer inside freezes updates and increments pending count`() {
        var now = 1_000L
        val gate = RenderGate<String>(graceMillis = 900L, clock = { now })

        assertTrue(gate.submit("initial"))
        assertEquals(0, gate.pendingCount)

        gate.onPointerEntered()
        assertTrue(gate.isFrozen())
        assertFalse(gate.submit("update-1"))
        assertEquals(1, gate.pendingCount)
        assertEquals("update-1", gate.bufferedValue)

        // Submitting identical value does not increment pendingCount
        assertFalse(gate.submit("update-1"))
        assertEquals(1, gate.pendingCount)

        assertFalse(gate.submit("update-2"))
        assertEquals(2, gate.pendingCount)
        assertEquals("update-2", gate.bufferedValue)
    }

    @Test
    fun `pointer leave stays frozen until grace period ends`() {
        var now = 1_000L
        val gate = RenderGate<String>(graceMillis = 900L, clock = { now })

        gate.onPointerEntered()
        assertFalse(gate.submit("v1"))
        assertEquals(1, gate.pendingCount)

        gate.onPointerExited()
        now += 500L
        assertTrue(gate.isWithinGracePeriod())
        assertTrue(gate.isFrozen())
        assertNull(gate.tryFlushIfUnfrozen())
        assertFalse(gate.submit("v2"))
        assertEquals(2, gate.pendingCount)

        // At 900 ms grace period expires
        now += 400L
        assertFalse(gate.isWithinGracePeriod())
        assertFalse(gate.isFrozen())
        assertEquals("v2", gate.tryFlushIfUnfrozen())
        assertEquals(0, gate.pendingCount)
        assertFalse(gate.hasPendingUpdates())
    }

    @Test
    fun `popup visible or selection keeps gate frozen even after grace period`() {
        var now = 1_000L
        val gate = RenderGate<String>(graceMillis = 900L, clock = { now })

        gate.popupVisible = true
        assertTrue(gate.isFrozen())
        assertFalse(gate.submit("popup-update"))
        assertEquals(1, gate.pendingCount)

        now += 5_000L
        assertTrue(gate.isFrozen())
        assertNull(gate.tryFlushIfUnfrozen())

        gate.popupVisible = false
        gate.hasSelection = true
        assertTrue(gate.isFrozen())
        assertNull(gate.tryFlushIfUnfrozen())

        gate.hasSelection = false
        assertFalse(gate.isFrozen())
        assertEquals("popup-update", gate.tryFlushIfUnfrozen())
    }

    @Test
    fun `clicking pause pill forces immediate flush while still frozen`() {
        var now = 1_000L
        val gate = RenderGate<String>(graceMillis = 900L, clock = { now })

        gate.onPointerEntered()
        assertFalse(gate.submit("batch-1"))
        assertFalse(gate.submit("batch-2"))
        assertEquals(2, gate.pendingCount)

        val flushed = gate.forceFlush()
        assertEquals("batch-2", flushed)
        assertEquals(0, gate.pendingCount)
        assertFalse(gate.hasPendingUpdates())
        assertTrue(gate.isFrozen())

        // Subsequent identical state is ignored because it matches what was just flushed
        assertFalse(gate.submit("batch-2"))
        assertEquals(0, gate.pendingCount)

        // New distinct update while still hovering buffers again from 1
        assertFalse(gate.submit("batch-3"))
        assertEquals(1, gate.pendingCount)
    }
}
