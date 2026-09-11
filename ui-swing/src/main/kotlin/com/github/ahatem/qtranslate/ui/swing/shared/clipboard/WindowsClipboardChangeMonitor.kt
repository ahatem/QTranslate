package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import com.github.ahatem.qtranslate.api.core.Logger
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary

/**
 * Clipboard change detection backed by the Windows clipboard sequence number.
 *
 * Windows advances the sequence on every clipboard write, even when the published data is
 * byte-for-byte identical, so a selection equal to the current clipboard contents is still
 * detected as a copy. The counter is a DWORD and wraps; tokens only ever compare for
 * inequality against a token taken moments earlier, so a wrap between mark and check still
 * reads as a change, which is the correct answer.
 *
 * The constructor takes the native binding so unit tests can substitute a fake; production
 * uses [create], which loads user32.
 */
internal class WindowsClipboardChangeMonitor internal constructor(
    private val user32: User32,
) : ClipboardChangeMonitor {

    /**
     * Null when the sequence cannot be observed. The native API returns zero on failure,
     * which is reported as unavailable rather than as a token: a zero token would compare
     * unequal to almost anything and fabricate changes.
     */
    override fun mark(): Long? {
        val sequence = runCatching { user32.GetClipboardSequenceNumber().toLong() }.getOrNull()
            ?: return null
        return sequence.takeIf { it != 0L }
    }

    override fun hasChangedSince(token: Long): Boolean = mark()?.let { it != token } ?: false

    internal interface User32 : StdCallLibrary {
        fun GetClipboardSequenceNumber(): Int
    }

    companion object {
        /** Null when user32 cannot be loaded, so the caller can fall back. */
        fun create(logger: Logger): WindowsClipboardChangeMonitor? = try {
            WindowsClipboardChangeMonitor(Native.load("user32", User32::class.java))
        } catch (t: Throwable) {
            logger.warn("Windows clipboard sequence number unavailable: ${t.message}")
            null
        }
    }
}
