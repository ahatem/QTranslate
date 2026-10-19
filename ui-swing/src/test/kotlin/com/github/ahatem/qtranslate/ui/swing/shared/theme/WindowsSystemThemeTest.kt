package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.sun.jna.Native
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

    /** Records what the reader asked for and answers with the configured registry outcome. */
    private class FakeAdvapi32(
        private val status: Int = 0,
        private val storedValue: Int = 1,
        private val failure: Throwable? = null
    ) : WindowsSystemTheme.Advapi32 {
        data class Call(
            val handle: Pointer,
            val subKey: String,
            val valueName: String,
            val flags: Int,
            val bufferBytes: Int
        )

        val calls = mutableListOf<Call>()

        override fun RegGetValueW(
            hkey: Pointer,
            lpSubKey: WString,
            lpValue: WString,
            dwFlags: Int,
            pdwType: IntByReference?,
            pvData: IntByReference,
            pcbData: IntByReference
        ): Int {
            calls += Call(hkey, lpSubKey.toString(), lpValue.toString(), dwFlags, pcbData.value)
            failure?.let { throw it }
            pvData.value = storedValue
            return status
        }
    }

    @Test
    fun `zero means dark`() {
        assertEquals(true, WindowsSystemTheme(FakeAdvapi32(storedValue = 0)).isDarkMode())
    }

    @Test
    fun `any non-zero value means light`() {
        assertEquals(false, WindowsSystemTheme(FakeAdvapi32(storedValue = 1)).isDarkMode())
        assertEquals(false, WindowsSystemTheme(FakeAdvapi32(storedValue = 2)).isDarkMode())
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

    @Test
    fun `the call reads the AppsUseLightTheme DWORD once`() {
        val native = FakeAdvapi32(storedValue = 0)
        WindowsSystemTheme(native).isDarkMode()

        val call = native.calls.single()
        assertEquals("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", call.subKey)
        assertEquals("AppsUseLightTheme", call.valueName)
        // RRF_RT_REG_DWORD, and a four-byte destination for the DWORD itself.
        assertEquals(0x00000010, call.flags)
        assertEquals(4, call.bufferBytes)
    }

    @Test
    fun `the passed handle is HKEY_CURRENT_USER sign-extended to pointer width`() {
        val native = FakeAdvapi32()
        WindowsSystemTheme(native).isDarkMode()

        // Win32's predefined HKCU id is a signed 32-bit value cast to pointer width, so the low
        // half is 0x80000001 everywhere and the upper half is all ones at 64-bit pointer width.
        // A zero-padded 0x0000000080000001 would be a different handle to the registry API.
        val bits = Pointer.nativeValue(native.calls.single().handle)
        assertEquals(0x80000001L, bits and 0xFFFF_FFFFL, "predefined HKCU id in the low half")
        if (Native.POINTER_SIZE == 8) {
            // -0x7FFFFFFF is 0xFFFFFFFF80000001 at 64 bits: the id, sign-extended, not padded.
            assertEquals(-0x7FFFFFFFL, bits, "sign-extended, as Win32 declares it")
        }
    }
}
