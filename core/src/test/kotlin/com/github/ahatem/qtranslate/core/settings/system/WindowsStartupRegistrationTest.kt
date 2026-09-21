package com.github.ahatem.qtranslate.core.settings.system

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the Windows startup-registration part of #226: enabling
 * "launch on system startup" persisted the setting but never created a startup entry,
 * because no registration code existed at all.
 *
 * Every test runs against a fake `reg.exe` — nothing here touches the real registry,
 * so the suite is safe on any OS and in CI.
 */
class WindowsStartupRegistrationTest {

    private class FakeReg(
        var queryOutput: String = "",
        var queryExitCode: Int = 1,
        var writeExitCode: Int = 0
    ) {
        val calls = mutableListOf<List<String>>()

        fun run(argv: List<String>): WindowsStartupRegistration.CommandResult {
            calls += argv
            return when {
                argv.take(2) == listOf("reg", "query") ->
                    WindowsStartupRegistration.CommandResult(queryExitCode, queryOutput)
                else ->
                    WindowsStartupRegistration.CommandResult(writeExitCode, "")
            }
        }
    }

    private fun registration(
        fake: FakeReg,
        osName: String = "Windows 10",
        launcherCommand: String? = "\"C:\\Program Files\\QTranslate\\QTranslate.exe\""
    ) = WindowsStartupRegistration(
        commandRunner = fake::run,
        osName = osName,
        launcherCommand = { launcherCommand }
    )

    private fun queryOutputFor(command: String): String =
        "\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\n" +
            "    QTranslate    REG_SZ    $command\n"

    // ---- command construction ------------------------------------------------

    @Test
    fun `exe startup command carries the startup marker after the quoted path`() {
        assertEquals(
            "\"C:\\Program Files\\QTranslate\\QTranslate.exe\" --startup",
            WindowsStartupRegistration.startupCommandForExecutable("C:\\Program Files\\QTranslate\\QTranslate.exe")
        )
    }

    @Test
    fun `jar fallback uses javaw with both paths quoted and the startup marker last`() {
        assertEquals(
            "\"C:\\Java\\bin\\javaw.exe\" -jar \"C:\\Apps\\QTranslate\\QTranslate.jar\" --startup",
            WindowsStartupRegistration.startupCommandForJar("C:\\Java\\bin\\javaw.exe", "C:\\Apps\\QTranslate\\QTranslate.jar")
        )
    }

    @Test
    fun `already-quoted paths are not double-quoted`() {
        assertEquals(
            "\"C:\\QTranslate\\QTranslate.exe\"",
            WindowsStartupRegistration.quote("\"C:\\QTranslate\\QTranslate.exe\"")
        )
    }

    // ---- enable ---------------------------------------------------------------

    @Test
    fun `enable writes the marker-bearing command to the per-user Run key`() {
        val fake = FakeReg()
        val command = "\"C:\\Program Files\\QTranslate\\QTranslate.exe\" --startup"

        assertTrue(registration(fake, launcherCommand = command).setEnabled(true))

        val write = fake.calls.single { it.getOrNull(1) == "add" }
        assertEquals(
            listOf("reg", "add", WindowsStartupRegistration.RUN_KEY, "/v", "QTranslate",
                "/t", "REG_SZ", "/d", WindowsStartupRegistration.regDataArgument(command), "/f"),
            write
        )
    }

    @Test
    fun `reg data argument escapes quotes while leaving the startup marker intact`() {
        // ProcessBuilder routes argv through one Windows command line whose parsing strips a
        // layer of quoting: plain embedded quotes never reach the registry, and a path with
        // spaces would be stored unquoted. Verified against live reg.exe during development.
        assertEquals(
            "\\\"C:\\Program Files\\QTranslate\\QTranslate.exe\\\" --startup",
            WindowsStartupRegistration.regDataArgument("\"C:\\Program Files\\QTranslate\\QTranslate.exe\" --startup")
        )
    }

    @Test
    fun `enable without a resolvable launcher writes nothing`() {
        val fake = FakeReg()

        assertEquals(false, registration(fake, launcherCommand = null).setEnabled(true))
        assertTrue(fake.calls.isEmpty())
    }

    // ---- disable ---------------------------------------------------------------

    @Test
    fun `disable removes only this application's value`() {
        val fake = FakeReg(
            queryOutput = queryOutputFor("\"C:\\Program Files\\QTranslate\\QTranslate.exe\""),
            queryExitCode = 0
        )

        assertTrue(registration(fake).setEnabled(false))

        val delete = fake.calls.single { it.getOrNull(1) == "delete" }
        assertEquals(
            listOf("reg", "delete", WindowsStartupRegistration.RUN_KEY, "/v", "QTranslate", "/f"),
            delete
        )
        // The key itself is never deleted, and no other value is named.
        assertTrue(fake.calls.none { it.getOrNull(1) == "delete" && "/va" in it })
    }

    @Test
    fun `disable with no entry present succeeds without deleting`() {
        val fake = FakeReg(queryExitCode = 1)

        assertTrue(registration(fake).setEnabled(false))
        assertEquals(listOf(listOf("reg", "query", WindowsStartupRegistration.RUN_KEY, "/v", "QTranslate")), fake.calls)
    }

    // ---- reconcile: idempotence and staleness -----------------------------------

    @Test
    fun `reconcile skips the write when the entry is already correct`() {
        val command = "\"C:\\Program Files\\QTranslate\\QTranslate.exe\" --startup"
        val fake = FakeReg(queryOutput = queryOutputFor(command), queryExitCode = 0)

        assertTrue(registration(fake, launcherCommand = command).reconcile(true))
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `reconcile replaces a legacy entry left without the startup marker`() {
        // Entries written before the --startup marker existed have no invocation marker, so a
        // login launch from them would wrongly show the main window. They read back as stale
        // and are replaced with the marker-bearing command.
        val fake = FakeReg(
            queryOutput = queryOutputFor("\"C:\\Program Files\\QTranslate\\QTranslate.exe\""),
            queryExitCode = 0
        )
        val command = "\"C:\\Program Files\\QTranslate\\QTranslate.exe\" --startup"

        assertTrue(registration(fake, launcherCommand = command).reconcile(true))

        val write = fake.calls.single { it.getOrNull(1) == "add" }
        assertEquals(
            WindowsStartupRegistration.regDataArgument(command),
            write[write.indexOf("/d") + 1]
        )
    }

    @Test
    fun `reconcile replaces a stale entry from an older install location`() {
        val fake = FakeReg(
            queryOutput = queryOutputFor("\"D:\\Old\\QTranslate.exe\" --startup"),
            queryExitCode = 0
        )
        val command = "\"C:\\Program Files\\QTranslate\\QTranslate.exe\" --startup"

        assertTrue(registration(fake, launcherCommand = command).reconcile(true))

        val write = fake.calls.single { it.getOrNull(1) == "add" }
        assertEquals(
            WindowsStartupRegistration.regDataArgument(command),
            write[write.indexOf("/d") + 1]
        )
    }

    @Test
    fun `reconcile creates a missing entry when enabled`() {
        val fake = FakeReg(queryExitCode = 1)
        val command = "\"C:\\Program Files\\QTranslate\\QTranslate.exe\" --startup"

        assertTrue(registration(fake, launcherCommand = command).reconcile(true))
        assertTrue(fake.calls.any { it.getOrNull(1) == "add" })
    }

    @Test
    fun `reconcile refuses to enable when no launcher command resolves`() {
        val fake = FakeReg(queryExitCode = 1)

        assertEquals(false, registration(fake, launcherCommand = null).reconcile(true))
        // Unresolvable before any system call — nothing is read or written.
        assertTrue(fake.calls.isEmpty())
    }

    // ---- platform guard ----------------------------------------------------------

    @Test
    fun `non-windows performs no system calls`() {
        val fake = FakeReg()

        assertTrue(registration(fake, osName = "Linux").reconcile(true))
        assertTrue(registration(fake, osName = "Mac OS X").reconcile(false))
        assertTrue(fake.calls.isEmpty())
    }

    // ---- parsing ------------------------------------------------------------------

    @Test
    fun `query output with spaces in the path parses back exactly`() {
        val command = "\"C:\\Program Files\\QTranslate\\QTranslate.exe\""
        assertEquals(command, WindowsStartupRegistration.parseCurrentCommand(queryOutputFor(command)))
    }

    @Test
    fun `marker-bearing query output parses back exactly`() {
        val command = "\"C:\\Program Files\\QTranslate\\QTranslate.exe\" --startup"
        assertEquals(command, WindowsStartupRegistration.parseCurrentCommand(queryOutputFor(command)))
    }

    @Test
    fun `missing value parses to null`() {
        assertEquals(
            null,
            WindowsStartupRegistration.parseCurrentCommand("ERROR: The system was unable to find the specified registry key or value.")
        )
    }

    // ---- launcher resolution ---------------------------------------------------------

    @Test
    fun `jpackage launcher path wins over everything else`() {
        assertEquals(
            "\"C:\\Program Files\\QTranslate\\QTranslate.exe\" --startup",
            WindowsStartupRegistration.resolveLauncherCommand(
                jpackageAppPath = "C:\\Program Files\\QTranslate\\QTranslate.exe",
                codeSourceUrl = null
            )
        )
    }

    @Test
    fun `non-packaged class locations resolve to nothing rather than a bogus command`() {
        // An IDE/test run: code source is a directory, not a JAR.
        val classesDir = File(System.getProperty("java.io.tmpdir")).toURI().toURL()
        assertEquals(
            null,
            WindowsStartupRegistration.resolveLauncherCommand(
                jpackageAppPath = null,
                codeSourceUrl = classesDir
            )
        )
    }

    @Test
    fun `default resolution in a dev or test worker yields nothing`() {
        // No jpackage.app-path is ever set outside the packaged launcher, and the code source
        // here is a classes directory rather than a JAR — so this must resolve to nothing,
        // which is what keeps ordinary development and test runs out of the user's startup.
        // (Runs only where it is meaningful: skipped off Windows, where reconcile is a no-op.)
        if (!WindowsStartupRegistration.isSupported()) return
        assertEquals(null, WindowsStartupRegistration.resolveLauncherCommand())
    }

    // ---- startup invocation marker -------------------------------------------------

    @Test
    fun `only the explicit startup argument marks a login launch`() {
        assertEquals(false, WindowsStartupRegistration.isStartupLaunch(arrayOf()))
        assertEquals(true, WindowsStartupRegistration.isStartupLaunch(arrayOf("--startup")))
        assertEquals(
            true,
            WindowsStartupRegistration.isStartupLaunch(arrayOf("--startup", "--something-else"))
        )
        assertEquals(false, WindowsStartupRegistration.isStartupLaunch(arrayOf("--minimized")))
        assertEquals(false, WindowsStartupRegistration.isStartupLaunch(arrayOf("--verbose", "--debug")))
    }

    @Test
    fun `unknown arguments never crash startup detection`() {
        assertEquals(false, WindowsStartupRegistration.isStartupLaunch(arrayOf("garbage", "--", "")))
    }

    // ---- initial visibility ----------------------------------------------------------

    @Test
    fun `normal launch requests a visible window even when tray is available`() {
        assertEquals(
            false,
            WindowsStartupRegistration.shouldStartHidden(
                launchedFromStartup = false,
                traySupported = true
            )
        )
    }

    @Test
    fun `startup launch with tray support starts hidden`() {
        assertEquals(
            true,
            WindowsStartupRegistration.shouldStartHidden(
                launchedFromStartup = true,
                traySupported = true
            )
        )
    }

    @Test
    fun `startup launch without tray support falls back to visible`() {
        // Hidden with no tray would leave an invisible process with no restore path —
        // global hotkeys alone are not a recovery mechanism when tray setup failed.
        assertEquals(
            false,
            WindowsStartupRegistration.shouldStartHidden(
                launchedFromStartup = true,
                traySupported = false
            )
        )
    }

    @Test
    fun `manual launch shows the window regardless of the persisted startup setting`() {
        // The `launchOnSystemStartup` setting is deliberately not an input here: a user with
        // startup enabled who launches QTranslate by hand must still get the main window.
        // Only the explicit --startup invocation marker hides it.
        assertEquals(false, WindowsStartupRegistration.isStartupLaunch(arrayOf()))
        assertEquals(
            false,
            WindowsStartupRegistration.shouldStartHidden(
                launchedFromStartup = false,
                traySupported = true
            )
        )
    }
}
