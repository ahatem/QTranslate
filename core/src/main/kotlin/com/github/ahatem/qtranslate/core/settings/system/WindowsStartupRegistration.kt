package com.github.ahatem.qtranslate.core.settings.system

import java.io.File

/**
 * Windows "launch at system startup" registration.
 *
 * The `launchOnSystemStartup` setting in [com.github.ahatem.qtranslate.core.settings.data.Configuration]
 * used to be write-only: the checkbox persisted the flag but no code ever acted on it, so enabling
 * it on Windows changed nothing. This class is the missing side effect — it keeps the per-user
 * startup entry in sync with the persisted setting.
 *
 * ### Mechanism
 * The standard per-user Run key, which needs no elevation and is what installers use for the same
 * purpose:
 * ```
 * HKCU\Software\Microsoft\Windows\CurrentVersion\Run
 *   QTranslate = "<launcher command>"
 * ```
 * Only this application's own value is ever written or removed; the key and every unrelated value
 * are left alone.
 *
 * ### Launcher command
 * The Windows release is a jpackage app-image whose launcher path the runtime exposes as the
 * `jpackage.app-path` system property, so the registered command is the quoted path of the
 * running `QTranslate.exe` followed by the [STARTUP_ARGUMENT] marker — never dependent on the
 * working directory. A portable
 * `java -jar QTranslate.jar` run registers `javaw -jar "<jar>" --startup` instead. Anything else (IDE runs,
 * tests, a bare classes directory) resolves to nothing and registration refuses rather than
 * writing a bogus command — ordinary development runs must not pollute the user's startup.
 *
 * ### Execution boundary
 * Everything goes through `reg.exe` via the injectable [commandRunner], the same approach the
 * theme code already uses to read the registry. Tests inject a fake; production uses [ProcessBuilder].
 * No call in this class throws — failures surface as `false` and the caller decides how to log them.
 */
class WindowsStartupRegistration(
    private val commandRunner: (List<String>) -> CommandResult = ::runCommand,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val launcherCommand: () -> String? = ::resolveLauncherCommand,
    private val valueName: String = VALUE_NAME
) {
    /** The outcome of one `reg.exe` invocation. */
    data class CommandResult(val exitCode: Int, val output: String)

    /**
     * Makes the actual startup entry match [desiredEnabled].
     *
     * Idempotent: when the entry already matches nothing is written. A stale entry left by an
     * older install location is replaced. Never throws.
     *
     * @return true when the entry now matches [desiredEnabled], or when there is nothing to do
     *   off Windows. False when enabling was requested but no valid launcher command could be
     *   resolved, or when `reg.exe` reported a failure.
     */
    fun reconcile(desiredEnabled: Boolean): Boolean {
        if (!isSupported()) return true
        return runCatching {
            if (desiredEnabled) {
                val command = launcherCommand() ?: return false
                if (readCurrentCommand() == command) return true
                setEnabled(true)
            } else {
                if (readCurrentCommand() == null) return true
                setEnabled(false)
            }
        }.getOrDefault(false)
    }

    /**
     * Writes (or removes) the startup entry unconditionally.
     *
     * Prefer [reconcile], which skips the write when it would change nothing.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        if (!isSupported()) return false
        return runCatching {
            if (enabled) {
                val command = launcherCommand() ?: return false
                val result = commandRunner(
                    listOf("reg", "add", RUN_KEY, "/v", valueName, "/t", "REG_SZ",
                        "/d", regDataArgument(command), "/f")
                )
                result.exitCode == 0
            } else {
                if (readCurrentCommand() == null) return true
                val result = commandRunner(listOf("reg", "delete", RUN_KEY, "/v", valueName, "/f"))
                result.exitCode == 0
            }
        }.getOrDefault(false)
    }

    /**
     * Reads back this application's own startup command, or null when no entry exists, the OS is
     * not Windows, or the query failed. Never throws.
     */
    fun readCurrentCommand(): String? {
        if (!isSupported()) return null
        return runCatching {
            val result = commandRunner(listOf("reg", "query", RUN_KEY, "/v", valueName))
            if (result.exitCode != 0) null else parseCurrentCommand(result.output, valueName)
        }.getOrNull()
    }

    /** False off Windows, where there is no startup entry to manage. */
    fun isSupported(): Boolean = isWindows(osName)

    companion object {
        /** Per-user autostart key — writable without elevation. */
        const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

        /** This application's value under [RUN_KEY]. Only this value is ever touched. */
        const val VALUE_NAME = "QTranslate"

        /**
         * Explicit invocation marker appended to the registered startup command (#226).
         *
         * The persisted `launchOnSystemStartup` setting alone must never decide whether this
         * process starts hidden: a user with startup enabled may still launch QTranslate
         * manually later, and that manual launch must show the main window. Only a process
         * actually carrying this argument — i.e. one Windows started from the registration —
         * starts hidden in the tray.
         */
        const val STARTUP_ARGUMENT = "--startup"

        /** True when [osName] is a Windows release. Exposed for callers that skip wiring entirely. */
        fun isWindows(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
            osName.startsWith("Windows", ignoreCase = true)

        /** True on a Windows machine, for callers that skip the wiring entirely elsewhere. */
        fun isSupported(): Boolean = isWindows()

        /**
         * Quotes a path for the Run value. Always quoted — a bare
         * `C:\Program Files\...` would otherwise execute `C:\Program` with arguments.
         */
        fun quote(path: String): String =
            if (path.startsWith("\"") && path.endsWith("\"") && path.length >= 2) path
            else "\"$path\""

        /** Startup command for a packaged launcher such as the jpackage `QTranslate.exe`. */
        fun startupCommandForExecutable(exePath: String): String = "${quote(exePath)} $STARTUP_ARGUMENT"

        /** Startup command for a portable `java -jar` run. Console-free `javaw`, quoted throughout. */
        fun startupCommandForJar(javawPath: String, jarPath: String): String =
            "${quote(javawPath)} -jar ${quote(jarPath)} $STARTUP_ARGUMENT"

        /**
         * True when this process was invoked from the Windows startup registration (#226).
         *
         * Deliberately narrow — no CLI framework for one flag, and unknown arguments never
         * affect startup.
         */
        fun isStartupLaunch(args: Array<out String>): Boolean = STARTUP_ARGUMENT in args

        /**
         * Whether the main window must stay hidden at startup (#226).
         *
         * Pure so it stays deterministic in tests: hidden only when this invocation came from
         * the startup registration *and* a tray icon can actually restore the window. Starting
         * hidden without a tray would leave an invisible process with no recovery path, so a
         * missing tray always falls back to a visible window.
         */
        fun shouldStartHidden(launchedFromStartup: Boolean, traySupported: Boolean): Boolean =
            launchedFromStartup && traySupported

        /**
         * Encodes a startup [command] for the `/d` argument of `reg add` when invoked via
         * [ProcessBuilder].
         *
         * The logical command carries plain quotes (`"C:\Program Files\..."`), which is also how
         * `reg query` reports the stored value back. But `ProcessBuilder` hands `reg.exe` a single
         * Windows command line, and the C-runtime parsing on the receiving side strips one layer
         * of quoting — so plain embedded quotes never reach the registry and a path containing
         * spaces would be stored unquoted (verified live: the entry came back without quotes and
         * would execute `C:\Program` at login). Backslash-escaping each quote survives the trip
         * and lands the quotes in the stored value.
         */
        fun regDataArgument(command: String): String = command.replace("\"", "\\\"")

        /**
         * Resolves the command to register for the currently running application, or null when no
         * valid command can be determined.
         *
         * Order: the native launcher reported by the jpackage runtime first, then the running JAR
         * via `javaw -jar`. Anything else (IDE, tests, bare classes directory) yields null so
         * development runs never touch the user's real startup.
         */
        fun resolveLauncherCommand(
            jpackageAppPath: String? = System.getProperty("jpackage.app-path"),
            codeSourceUrl: java.net.URL? = runCatching {
                WindowsStartupRegistration::class.java.protectionDomain?.codeSource?.location
            }.getOrNull(),
            javaHome: String = System.getProperty("java.home").orEmpty()
        ): String? {
            if (!jpackageAppPath.isNullOrBlank()) return startupCommandForExecutable(jpackageAppPath)
            val jarFile = codeSourceUrl?.let { runCatching { File(it.toURI()) }.getOrNull() }
            if (jarFile != null && jarFile.isFile && jarFile.extension.equals("jar", ignoreCase = true)) {
                val javaw = File(javaHome, "bin/javaw.exe")
                if (javaw.isFile) return startupCommandForJar(javaw.absolutePath, jarFile.absolutePath)
            }
            return null
        }

        /**
         * Extracts this application's command from `reg query` output, or null when absent.
         *
         * A typical line looks like:
         * ```
         *     QTranslate    REG_SZ    "C:\Program Files\QTranslate\QTranslate.exe"
         * ```
         */
        fun parseCurrentCommand(queryOutput: String, valueName: String = VALUE_NAME): String? {
            for (line in queryOutput.lines()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith(valueName, ignoreCase = true)) continue
                val rest = trimmed.substring(valueName.length).trimStart()
                val match = Regex("^(REG_\\S+)\\s+(.*)$").find(rest) ?: continue
                return match.groupValues[2].trim()
            }
            return null
        }

        private fun runCommand(argv: List<String>): CommandResult =
            try {
                val process = ProcessBuilder(argv).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                CommandResult(process.waitFor(), output)
            } catch (e: Exception) {
                CommandResult(-1, e.message.orEmpty())
            }
    }
}
