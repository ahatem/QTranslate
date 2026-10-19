package com.github.ahatem.qtranslate.ui.swing.shared.theme

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Proves the real `advapi32` binding loads and can be called on Windows.
 *
 * [WindowsSystemThemeTest] fakes the native interface, which keeps the value mapping cheap to
 * test but says nothing about the binding itself: only calling the real library catches a wrong
 * symbol name, calling convention, parameter order, `RRF_RT_REG_DWORD`, or `HKEY_CURRENT_USER`
 * value.
 *
 * The host's own preference is data, not an assertion. A missing `Personalize` key, a missing
 * `AppsUseLightTheme`, a value of another type, or a key this account may not read are all states
 * production supports (they read as "light"), and a CI account may have none of them, so such
 * hosts skip only the value assertion. The mapping itself stays pinned deterministically in
 * [WindowsSystemThemeTest].
 */
class WindowsSystemThemeBindingTest {

    @Test
    fun `the real binding loads and calls advapi32`() {
        assumeTrue(
            System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true),
            "Windows registry only; skipped"
        )

        val reader = assertNotNull(WindowsSystemTheme.create(), "advapi32 must load")

        // Unguarded by design: a wrong symbol, calling convention, parameter order, flag, or
        // handle fails here — with an exception, or with a status the registry does not return
        // for these inputs — instead of being condensed into "no answer" like a missing value.
        val read = reader.read()

        when (read.status) {
            ERROR_SUCCESS -> {
                // The preference exists: the live DWORD must be interpreted the documented way.
                val dark = assertNotNull(reader.isDarkMode(), "a successful read must answer")
                assertEquals(read.value == 0, dark, "zero means dark, non-zero means light")
            }

            // Supported "no answer" states, all possible on a CI account: no key, no value, a
            // value of another type, or a key this account cannot read. Failing here would hold
            // the runner's configuration against a binding that behaved correctly.
            ERROR_FILE_NOT_FOUND, ERROR_ACCESS_DENIED, ERROR_UNSUPPORTED_TYPE ->
                assumeTrue(
                    false,
                    "host has no readable AppsUseLightTheme (LSTATUS ${read.status}); " +
                        "value assertion skipped"
                )

            else ->
                fail("RegGetValueW rejected a valid call with LSTATUS ${read.status}")
        }
    }

    private companion object {
        const val ERROR_SUCCESS = 0

        /** No `Personalize` key, or no `AppsUseLightTheme` value under it. */
        const val ERROR_FILE_NOT_FOUND = 2

        /** The key exists but this account cannot read it. */
        const val ERROR_ACCESS_DENIED = 5

        /** `RRF_RT_REG_DWORD` was requested and the stored value is some other type. */
        const val ERROR_UNSUPPORTED_TYPE = 1630
    }
}
