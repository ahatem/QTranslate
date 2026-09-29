package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Change-monitor failure semantics: an unobservable clipboard must never read as a
 * confirmed change, and the Windows sequence wrapper must honor the native API contract.
 * No test here touches a real system clipboard.
 */
class ClipboardMonitorsTest {

    @Test
    fun `signature monitor treats a failed read as no change`() {
        val clipboard = RecordingClipboard("text").apply { signatureProvider = { null } }
        val monitor = SignatureClipboardChangeMonitor(clipboard)

        assertFalse(monitor.hasChangedSince(123L))
    }

    @Test
    fun `signature monitor detects a real change`() {
        val clipboard = RecordingClipboard("text").apply { signatureProvider = { 11L } }
        val monitor = SignatureClipboardChangeMonitor(clipboard)

        assertTrue(monitor.hasChangedSince(10L))
        assertFalse(monitor.hasChangedSince(11L))
    }

    @Test
    fun `windows monitor reports an unchanged sequence as no change`() {
        val monitor = WindowsClipboardChangeMonitor(FakeUser32(sequence = 7))

        assertEquals(7L, monitor.mark())
        assertFalse(monitor.hasChangedSince(7L))
    }

    @Test
    fun `windows monitor reports an advanced sequence as a change`() {
        val user32 = FakeUser32(sequence = 7)
        val monitor = WindowsClipboardChangeMonitor(user32)

        val token = monitor.mark()!!
        user32.sequence = 8

        assertTrue(monitor.hasChangedSince(token))
    }

    @Test
    fun `windows monitor tolerates DWORD wrap between mark and check`() {
        val user32 = FakeUser32(sequence = Int.MAX_VALUE)
        val monitor = WindowsClipboardChangeMonitor(user32)

        val token = monitor.mark()!!
        user32.sequence = Int.MIN_VALUE

        assertTrue(monitor.hasChangedSince(token))
    }

    @Test
    fun `windows monitor maps a zero sequence to unavailable`() {
        // GetClipboardSequenceNumber returns zero on failure, so zero is never a token.
        val monitor = WindowsClipboardChangeMonitor(FakeUser32(sequence = 0))

        assertNull(monitor.mark())
    }

    @Test
    fun `windows monitor maps a native failure to unavailable and no change`() {
        val monitor = WindowsClipboardChangeMonitor(FakeUser32(fail = true))

        assertNull(monitor.mark())
        assertFalse(monitor.hasChangedSince(7L))
    }

    private class FakeUser32(
        var sequence: Int = 1,
        var fail: Boolean = false,
    ) : WindowsClipboardChangeMonitor.User32 {

        override fun GetClipboardSequenceNumber(): Int {
            if (fail) throw RuntimeException("user32 unavailable")
            return sequence
        }
    }
}
