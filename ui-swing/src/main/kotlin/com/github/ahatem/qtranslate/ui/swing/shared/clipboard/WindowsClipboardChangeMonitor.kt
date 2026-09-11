package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import com.github.ahatem.qtranslate.api.core.Logger
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary

/**
 * Clipboard change detection backed by the Windows clipboard sequence number.
 *
 * Windows advances the sequence on every clipboard write, even when the published data is
 * byte-for-byte identical, so a selection equal to the current clipboard contents is still
 * detected as a copy.
 */
internal class WindowsClipboardChangeMonitor private constructor(
    private val user32: User32,
) : ClipboardChangeMonitor {

    override fun mark(): Long? = runCatching { user32.GetClipboardSequenceNumber().toLong() }.getOrNull()

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
