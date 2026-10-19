package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows' light/dark app preference, read in-process from the registry.
 *
 * Windows stores the choice as the `AppsUseLightTheme` DWORD under
 * `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize`: zero means dark, any
 * non-zero value means light. It is read with `advapi32!RegGetValueW`, which needs no key
 * handle to open and therefore none to close on any path, including failure.
 *
 * Reading in-process is the point. The 10-second follow-the-OS check used to shell out to
 * `reg.exe`; that recurring child-process lifecycle matches the cadence of the "Program Close"
 * sound reported in #323, and it was wasteful either way.
 *
 * The native binding is a constructor parameter so tests can drive every outcome, including a
 * failing registry call; production uses [create].
 */
internal class WindowsSystemTheme internal constructor(
    private val advapi32: Advapi32
) {

    /**
     * True for dark app mode, and false for light. Null when the preference cannot be read — a
     * missing key, a value of another type, or a failing registry call — so "the registry did not
     * answer" is never confused with "the registry said light" inside this class. See
     * [ThemeManager.isSystemInDarkMode] for what the caller does with a null answer.
     */
    fun isDarkMode(): Boolean? = runCatching { read() }
        .getOrNull()
        ?.takeIf { it.status == ERROR_SUCCESS }
        ?.let { it.value == LIGHT_THEME_OFF }

    /**
     * One registry read: the raw `LSTATUS` and the DWORD the call filled, if it filled one.
     *
     * Deliberately unguarded. [isDarkMode] is what turns a failed read into "no answer", while the
     * real-binding test needs the status itself to tell "this host has no preference" (a supported
     * state) apart from "this binding cannot call `advapi32` at all" (a defect).
     */
    internal fun read(): Read {
        val value = IntByReference(0)
        val size = IntByReference(DWORD_BYTES)
        val status = advapi32.RegGetValueW(
            HKEY_CURRENT_USER,
            WString(PERSONALIZE_KEY),
            WString(APPS_USE_LIGHT_THEME),
            RRF_RT_REG_DWORD,
            null,
            value,
            size
        )
        return Read(status, value.value)
    }

    /** The `LSTATUS` of one [read] and the DWORD it filled on success. */
    internal data class Read(val status: Int, val value: Int)

    /** The one `advapi32` entry point this reader needs. */
    internal interface Advapi32 : StdCallLibrary {
        /**
         * `LSTATUS RegGetValueW(HKEY, LPCWSTR, LPCWSTR, DWORD, LPDWORD, PVOID, LPDWORD)` with a
         * DWORD destination. [WString] stands in for the wide `LPCWSTR` parameters so no type
         * mapper is needed; [pvData] points at four bytes and [pcbData] says so on entry.
         */
        fun RegGetValueW(
            hkey: Pointer,
            lpSubKey: WString,
            lpValue: WString,
            dwFlags: Int,
            pdwType: IntByReference?,
            pvData: IntByReference,
            pcbData: IntByReference
        ): Int
    }

    companion object {
        /**
         * Loads `advapi32`; null when the library is unavailable, so the caller can fall back.
         *
         * Loaded once per process. A failed load is therefore not retried: the app reports light
         * mode for the rest of the session, which is what the previous `reg.exe` read produced
         * whenever it threw, and a retry on every poll would keep re-attempting the very
         * operation that failed (JNA's `jnidispatch` lookup) every ten seconds.
         */
        fun create(): WindowsSystemTheme? = try {
            WindowsSystemTheme(Native.load("advapi32", Advapi32::class.java))
        } catch (t: Throwable) {
            null
        }

        private const val ERROR_SUCCESS = 0
        private const val DWORD_BYTES = 4

        /** `RRF_RT_REG_DWORD`: only accept a DWORD, so a value of another type reads as unreadable. */
        private const val RRF_RT_REG_DWORD = 0x00000010

        /** Windows' "apps use the light theme" flag, where zero means dark mode. */
        private const val LIGHT_THEME_OFF = 0

        private const val APPS_USE_LIGHT_THEME = "AppsUseLightTheme"
        private const val PERSONALIZE_KEY =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"

        /**
         * `HKEY_CURRENT_USER`, a predefined pseudo-handle: no key to open or close.
         *
         * Win32 declares it as `(HKEY)(ULONG_PTR)((LONG)0x80000001)`: a *signed* 32-bit id
         * sign-extended to pointer width, so the canonical 64-bit handle is `0xFFFFFFFF80000001`
         * and not `0x0000000080000001`. That distinction matters here because
         * [Pointer.createConstant] takes a `long` and keeps every bit it is given. On 32-bit
         * Windows only the low half reaches the native call, which is the same handle there.
         *
         * Sign-extending the id is all this needs from `jna-platform`'s `WinReg`, so it is not a
         * dependency.
         */
        internal val HKEY_CURRENT_USER: Pointer =
            Pointer.createConstant(0x80000001.toInt().toLong())
    }
}
