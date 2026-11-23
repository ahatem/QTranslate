package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Registry mapping for Windows light/dark mode, driven through the native seam: the DWORD's
 * value, a rejected read, and a failing call — without loading `advapi32` or starting anything.
 */
class WindowsSystemThemeTest {

    /** Answers with the configured registry outcome. */
    private class FakeAdvapi32(
        private val status: Int = 0,
        private val storedValue: Int = 1,
        private val failure: Throwable? = null
    ) : WindowsSystemTheme.Advapi32 {
        override fun RegGetValueW(
            hkey: Pointer,
            lpSubKey: WString,
            lpValue: WString,
            dwFlags: Int,
            pdwType: IntByReference?,
            pvData: IntByReference,
            pcbData: IntByReference
        ): Int {
            failure?.let { throw it }
            pvData.value = storedValue
            return status
        }
    }

    @Test
    fun `the DWORD maps to dark mode`() {
        for ((stored, dark) in listOf(0 to true, 1 to false, 2 to false)) {
            assertEquals(dark, WindowsSystemTheme(FakeAdvapi32(storedValue = stored)).isDarkMode(), "stored value $stored")
        }
    }

    @Test
    fun `an unreadable preference reports no answer`() {
        // ERROR_FILE_NOT_FOUND: no Personalize key here, or a value that is not a DWORD.
        assertNull(WindowsSystemTheme(FakeAdvapi32(status = 2)).isDarkMode())
    }

    @Test
    fun `a failing native call reports no answer`() {
        assertNull(
            WindowsSystemTheme(FakeAdvapi32(failure = UnsatisfiedLinkError("advapi32 missing")))
                .isDarkMode()
        )
    }

}
