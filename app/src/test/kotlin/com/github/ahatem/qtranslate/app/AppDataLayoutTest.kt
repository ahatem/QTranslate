package com.github.ahatem.qtranslate.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the mode contract and the legacy-data migration.
 *
 * Every case builds a real directory layout on disk rather than mocking the filesystem, because the
 * behaviour under test is precisely which directory gets chosen and which does not.
 */
class AppDataLayoutTest {

    private val sandbox = Files.createTempDirectory("qtranslate-layout").toFile()
    private val installedRoot = File(sandbox, "AppData/Roaming/QTranslate")
    private val distributionRoot = File(sandbox, "QTranslate")

    private fun windowsEnvironment(
        explicitDataDir: String? = null,
        launcherPath: String? = null,
        codeSource: File? = null,
        markerExists: (File) -> Boolean = { it.isFile },
        mutableStatePresent: (File) -> Boolean = AppDataLayout::hasMutableState,
        writable: (File) -> Boolean = { it.canWrite() }
    ) = AppDataLayout.Environment(
        explicitDataDir = explicitDataDir,
        launcherPath = launcherPath,
        codeSource = codeSource,
        osName = "Windows 11",
        userHome = sandbox.absolutePath,
        environment = { key -> if (key == "APPDATA") File(sandbox, "AppData/Roaming").path else null },
        markerExists = markerExists,
        mutableStatePresent = mutableStatePresent,
        writable = writable
    )

    private fun resolve(
        environment: AppDataLayout.Environment,
        conflictResolver: LegacyDataConflictResolver = AppDataLayout.KEEP_INSTALLED_DATA
    ): ResolvedAppData {
        distributionRoot.mkdirs()
        return AppDataLayout.resolve(environment, conflictResolver)
    }

    /** Creates the distribution folder with a JAR in it, the plain portable-ZIP shape. */
    private fun distributionWithJar(): File {
        distributionRoot.mkdirs()
        File(distributionRoot, "QTranslate.jar").writeText("jar")
        return distributionRoot
    }

    /** Creates the distribution folder and a marker in it, for the cases that need one. */
    private fun distributionWithMarker(): File {
        distributionRoot.mkdirs()
        File(distributionRoot, "portable.flag").writeText("")
        return distributionRoot
    }

    /** Creates a sentinel directory that proves the application wrote there. */
    private fun writeMutableState(root: File) {
        File(root, "datastore").mkdirs()
        File(root, "datastore/app_settings.preferences_pb").writeText("state")
    }

    // ── Explicit override ────────────────────────────────────────────────────

    @Test
    fun `an explicit data directory wins over the marker`() {
        distributionWithMarker()
        val explicit = File(sandbox, "explicit")
        val layout = resolve(windowsEnvironment(explicitDataDir = explicit.absolutePath))

        assertEquals(AppDataMode.CUSTOM, layout.mode)
        assertEquals(explicit.canonicalFile, layout.userDataRoot.canonicalFile)
    }

    @Test
    fun `a blank or whitespace data directory is ignored rather than becoming the working directory`() {
        // -DappData= with no value used to resolve to File(""), whose absolute path is the JVM's
        // current working directory — an arbitrary location that then received the user's settings.
        for (blank in listOf("", "   ")) {
            val layout = resolve(windowsEnvironment(explicitDataDir = blank))
            assertEquals(
                AppDataMode.INSTALLED,
                layout.mode,
                "A blank appData property ($blank) must not be honoured"
            )
            assertEquals(installedRoot.canonicalFile, layout.userDataRoot.canonicalFile)
        }
    }

    // ── Portable marker ──────────────────────────────────────────────────────

    @Test
    fun `a marker beside the launcher declares the distribution portable`() {
        distributionWithMarker()
        val layout = resolve(windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path))

        assertEquals(AppDataMode.PORTABLE, layout.mode)
        assertEquals(distributionRoot.canonicalFile, layout.userDataRoot.canonicalFile)
        assertEquals(distributionRoot.canonicalFile, layout.installationRoot?.canonicalFile)
        assertFalse(layout.adoptedLegacyPortableData)
    }

    @Test
    fun `a marker-free distribution is installed, not portable`() {
        val layout = resolve(windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path))

        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertEquals(installedRoot.canonicalFile, layout.userDataRoot.canonicalFile)
        // Bundled resources still come from the installation.
        assertEquals(distributionRoot.canonicalFile, layout.installationRoot?.canonicalFile)
    }

    @Test
    fun `a directory named portable flag is not a marker`() {
        // A directory of that name would satisfy a naive isDirectory check and silently make every
        // copy portable.
        distributionRoot.mkdirs()
        File(distributionRoot, "portable.flag").mkdirs()
        val layout = resolve(windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path))

        assertEquals(AppDataMode.INSTALLED, layout.mode)
    }

    // ── Writability must not decide the mode ─────────────────────────────────

    @Test
    fun `a writable installation directory does not make the mode portable`() {
        val layout = resolve(windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path))

        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertEquals(installedRoot.canonicalFile, layout.userDataRoot.canonicalFile)
        assertFalse(File(distributionRoot, "datastore").exists())
    }

    @Test
    fun `an elevated writable Program Files style directory does not relocate user data`() {
        // The defect this replaces: running elevated made canWrite() true under Program Files, so the
        // same installation stored data beside itself for an admin and in %APPDATA% for everyone else.
        val programFiles = File(sandbox, "Program Files/QTranslate").apply { mkdirs() }
        val layout = resolve(windowsEnvironment(launcherPath = File(programFiles, "QTranslate.exe").path))

        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertEquals(installedRoot.canonicalFile, layout.userDataRoot.canonicalFile)
        assertFalse(File(programFiles, "datastore").exists())
        assertFalse(File(programFiles, "logs").exists())
    }

    @Test
    fun `an unwritable data directory fails without switching to the other location`() {
        // Writability must not take part in choosing where data goes. Both answers are fed through
        // the same decision, and the location named in each outcome is identical — so an elevated
        // launch and a normal one cannot end up storing data in different places.
        val launcher = File(distributionRoot, "QTranslate.exe").path
        val base = windowsEnvironment(launcherPath = launcher)

        val chosenWhenWritable = AppDataLayout
            .resolve(base.copy(writable = { true }), AppDataLayout.KEEP_INSTALLED_DATA)
        val locationWhenNot = assertFailsWith<AppDataLayoutException> {
            AppDataLayout.resolve(base.copy(writable = { false }), AppDataLayout.KEEP_INSTALLED_DATA)
        }.message!!

        assertEquals(AppDataMode.INSTALLED, chosenWhenWritable.mode)
        assertTrue(
            locationWhenNot.contains(chosenWhenWritable.userDataRoot.absolutePath),
            "An unwritable directory was reported as ${locationWhenNot.take(120)} but the chosen " +
                "location is ${chosenWhenWritable.userDataRoot.absolutePath}"
        )
        // Crucially, not the installation directory.
        assertFalse(locationWhenNot.contains(distributionRoot.absolutePath))
    }

    @Test
    fun `an unwritable portable distribution fails instead of becoming installed`() {
        distributionWithMarker()
        val launcher = File(distributionRoot, "QTranslate.exe").path

        val chosen = AppDataLayout.resolve(
            windowsEnvironment(launcherPath = launcher).copy(writable = { true }),
            AppDataLayout.KEEP_INSTALLED_DATA
        )
        val failure = assertFailsWith<AppDataLayoutException> {
            AppDataLayout.resolve(
                windowsEnvironment(launcherPath = launcher).copy(writable = { false }),
                AppDataLayout.KEEP_INSTALLED_DATA
            )
        }.message!!

        assertEquals(AppDataMode.PORTABLE, chosen.mode)
        assertEquals(distributionRoot.canonicalFile, chosen.userDataRoot.canonicalFile)
        assertTrue(failure.contains(distributionRoot.absolutePath))
        assertFalse(failure.contains(installedRoot.absolutePath))
    }

    @Test
    fun `writability is never consulted to decide between roots`() {
        // The rule the old code broke: it asked canWrite() and then chose a mode. Here the answer is
        // only ever a complaint about the directory already chosen, so it cannot influence which
        // directory that is.
        val launcher = File(distributionRoot, "QTranslate.exe").path
        val asked = mutableListOf<File>()
        val environment = windowsEnvironment(launcherPath = launcher).copy(
            writable = { directory -> asked += directory; true }
        )
        val layout = AppDataLayout.resolve(environment, AppDataLayout.KEEP_INSTALLED_DATA)

        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertEquals(listOf(layout.userDataRoot), asked, "Something other than the chosen data root was probed")
        assertFalse(asked.any { it == distributionRoot }, "The installation directory must never be probed as a data candidate")
    }

    // ── Unwritable selected directory ────────────────────────────────────────

    @Test
    fun `a portable distribution that is not writable fails instead of silently using APPDATA`() {
        val readOnlyDir = File(sandbox, "readonly").apply { mkdirs() }
        val failure = assertFailsWith<AppDataLayoutException> {
            resolve(
                windowsEnvironment(
                    launcherPath = File(readOnlyDir, "QTranslate.exe").path,
                    markerExists = { true },
                    writable = { false }
                )
            )
        }
        assertTrue(
            failure.message!!.contains(readOnlyDir.absolutePath),
            "The message must name the directory it could not use, was: ${failure.message}"
        )
        // The OS-standard directory must not have been substituted in its place.
        assertFalse(File(installedRoot, "datastore").exists())
    }

    @Test
    fun `a data directory that cannot be created fails instead of falling back to the install directory`() {
        // A regular file where the data directory should be: mkdirs() can never succeed there.
        val blocked = File(sandbox, "blocked").apply { writeText("not a directory") }
        val failure = assertFailsWith<AppDataLayoutException> {
            resolve(windowsEnvironment(explicitDataDir = blocked.absolutePath))
        }
        assertTrue(failure.message!!.contains(blocked.absolutePath))
    }

    // ── Legacy migration ─────────────────────────────────────────────────────

    @Test
    fun `legacy data beside the app is adopted and marked rather than relocated`() {
        writeMutableState(distributionRoot)

        val layout = resolve(windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path))

        assertEquals(AppDataMode.PORTABLE, layout.mode)
        assertTrue(layout.adoptedLegacyPortableData)
        assertEquals(distributionRoot.canonicalFile, layout.userDataRoot.canonicalFile)
        // The marker makes the choice stable, and nothing was moved.
        assertTrue(File(distributionRoot, "portable.flag").isFile)
        assertTrue(File(distributionRoot, "datastore/app_settings.preferences_pb").isFile)
        assertFalse(File(installedRoot, "datastore").exists())
    }

    @Test
    fun `shipped folders alone do not look like legacy user data`() {
        // plugins/, languages/, themes/ and icons/ ship in every distribution, so treating their
        // presence as evidence would make a freshly extracted copy adopt a portable mode it never had.
        listOf("plugins", "languages", "themes", "icons").forEach { name ->
            File(distributionRoot, name).mkdirs()
            File(distributionRoot, "$name/sample").writeText("bundled")
        }

        val layout = resolve(windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path))

        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertFalse(layout.adoptedLegacyPortableData)
        assertFalse(File(distributionRoot, "portable.flag").exists())
    }

    @Test
    fun `every mutable state sentinel is recognised`() {
        listOf("datastore", "plugins_data", "logs").forEach { sentinel ->
            val root = File(sandbox, "sentinel-$sentinel").apply { mkdirs() }
            assertFalse(AppDataLayout.hasMutableState(root), "$sentinel alone must be required to be non-empty")
            File(root, sentinel).mkdirs()
            assertFalse(AppDataLayout.hasMutableState(root), "An empty $sentinel/ is not user state")
            File(root, "$sentinel/entry").writeText("x")
            assertTrue(AppDataLayout.hasMutableState(root), "$sentinel/ with content is user state")
        }
    }

    @Test
    fun `an empty sentinel directory is not mistaken for user data`() {
        File(distributionRoot, "datastore").mkdirs()
        val layout = resolve(windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path))

        assertEquals(AppDataMode.INSTALLED, layout.mode)
    }

    @Test
    fun `data in both locations is a conflict the user must resolve`() {
        writeMutableState(distributionRoot)
        writeMutableState(installedRoot)

        var asked = false
        val layout = resolve(
            windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path),
            LegacyDataConflictResolver { legacy, installed ->
                asked = true
                assertTrue(File(legacy, "datastore").isDirectory)
                assertTrue(File(installed, "datastore").isDirectory)
                installed
            }
        )

        assertTrue(asked, "Both locations hold real state, so the user must be asked")
        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertEquals(installedRoot.canonicalFile, layout.userDataRoot.canonicalFile)
        // Neither directory was merged into, moved, or removed.
        assertTrue(File(distributionRoot, "datastore/app_settings.preferences_pb").isFile)
        assertTrue(File(installedRoot, "datastore/app_settings.preferences_pb").isFile)
        assertFalse(File(distributionRoot, "portable.flag").exists())
    }

    @Test
    fun `choosing the legacy location in a conflict adopts it explicitly`() {
        writeMutableState(distributionRoot)
        writeMutableState(installedRoot)

        val layout = resolve(
            windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path),
            LegacyDataConflictResolver { legacy, _ -> legacy }
        )

        assertEquals(AppDataMode.PORTABLE, layout.mode)
        assertTrue(layout.adoptedLegacyPortableData)
        assertTrue(File(distributionRoot, "portable.flag").isFile)
    }

    @Test
    fun `a fresh copy with no data at all is installed`() {
        val layout = resolve(windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path))

        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertFalse(layout.adoptedLegacyPortableData)
        assertFalse(File(distributionRoot, "portable.flag").exists())
    }

    @Test
    fun `data only in the OS location stays installed`() {
        writeMutableState(installedRoot)
        val layout = resolve(windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path))

        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertFalse(layout.adoptedLegacyPortableData)
    }

    // ── Conflict stability ───────────────────────────────────────────────────

    @Test
    fun `choosing portable in a conflict is stable and is not asked again`() {
        writeMutableState(distributionRoot)
        writeMutableState(installedRoot)
        var asked = false

        val first = resolve(
            windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path),
            LegacyDataConflictResolver { legacy, _ -> asked = true; legacy }
        )
        assertTrue(asked)
        assertEquals(AppDataMode.PORTABLE, first.mode)

        // Second launch: the marker written by the first settles it without asking.
        asked = false
        val second = resolve(
            windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path),
            LegacyDataConflictResolver { _, _ -> asked = true; error("must not be asked again") }
        )

        assertFalse(asked, "The portable choice must not be asked again")
        assertEquals(AppDataMode.PORTABLE, second.mode)
        assertEquals(distributionRoot.canonicalFile, second.userDataRoot.canonicalFile)
    }

    @Test
    fun `choosing installed in a conflict is stable and is not asked again`() {
        writeMutableState(distributionRoot)
        writeMutableState(installedRoot)
        var asked = false

        val first = resolve(
            windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path),
            LegacyDataConflictResolver { _, installed -> asked = true; installed }
        )
        assertTrue(asked)
        assertEquals(AppDataMode.INSTALLED, first.mode)

        // The decision is recorded in the user's own data directory, which is writable even when the
        // installation directory is not, so the question does not come back on the next launch.
        assertTrue(
            File(installedRoot, AppDataLayout.MODE_DECISION_FILE).isFile,
            "The installed choice must be recorded so it is not asked again"
        )

        asked = false
        val second = resolve(
            windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path),
            LegacyDataConflictResolver { _, _ -> asked = true; error("must not be asked again") }
        )

        assertFalse(asked, "The installed choice must not be asked again")
        assertEquals(AppDataMode.INSTALLED, second.mode)
        assertEquals(installedRoot.canonicalFile, second.userDataRoot.canonicalFile)
    }

    @Test
    fun `recording the installed choice writes nothing into the installation directory`() {
        // Choosing the installed location must not depend on being able to write to the installation
        // directory, which is read-only under Program Files.
        writeMutableState(distributionRoot)
        writeMutableState(installedRoot)
        val before = distributionRoot.list()!!.toSet()

        resolve(
            windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path),
            LegacyDataConflictResolver { _, installed -> installed }
        )

        assertEquals(before, distributionRoot.list()!!.toSet(), "Nothing may be written beside the application")
        assertFalse(File(distributionRoot, AppDataLayout.PORTABLE_MARKER).exists())
    }

    @Test
    fun `the recorded decision holds only the chosen mode and no user data`() {
        writeMutableState(distributionRoot)
        writeMutableState(installedRoot)

        resolve(
            windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path),
            LegacyDataConflictResolver { _, installed -> installed }
        )

        val recorded = File(installedRoot, AppDataLayout.MODE_DECISION_FILE).readText()
        assertEquals(LegacyConflictChoice.INSTALLED.name, recorded)
    }

    @Test
    fun `deleting the recorded decision lets the user change their mind`() {
        writeMutableState(distributionRoot)
        writeMutableState(installedRoot)
        val environment = windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path)

        resolve(environment, LegacyDataConflictResolver { _, installed -> installed })
        assertEquals(
            AppDataMode.INSTALLED,
            resolve(environment, LegacyDataConflictResolver { _, _ -> error("settled") }).mode
        )

        // The one file is the whole record; removing it reopens the question, and the legacy data is
        // still sitting there untouched.
        File(installedRoot, AppDataLayout.MODE_DECISION_FILE).delete()
        val reopened = resolve(environment, LegacyDataConflictResolver { legacy, _ -> legacy })

        assertEquals(AppDataMode.PORTABLE, reopened.mode)
        assertTrue(File(distributionRoot, "datastore/app_settings.preferences_pb").isFile)
    }

    @Test
    fun `closing the prompt falls back to installed and settles the question`() {
        // Documented policy: closing is not a decision to keep the legacy data, so it takes the
        // default and records it, rather than reappearing on every launch.
        writeMutableState(distributionRoot)
        writeMutableState(installedRoot)
        val environment = windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path)

        // LegacyDataConflictPrompt returns installedRoot for both a dismissal and the first button;
        // here the resolver stands in for that dismissal.
        val dismissed = resolve(environment, LegacyDataConflictResolver { _, installed -> installed })
        assertEquals(AppDataMode.INSTALLED, dismissed.mode)

        val second = resolve(environment, LegacyDataConflictResolver { _, _ -> error("settled by dismissal") })
        assertEquals(AppDataMode.INSTALLED, second.mode)
        // Neither directory was merged or removed.
        assertTrue(File(distributionRoot, "datastore/app_settings.preferences_pb").isFile)
        assertTrue(File(installedRoot, "datastore/app_settings.preferences_pb").isFile)
    }

    @Test
    fun `the recorded choice is honoured even when the legacy data is gone`() {
        writeMutableState(installedRoot)
        val environment = windowsEnvironment(launcherPath = File(distributionRoot, "QTranslate.exe").path)
        resolve(environment, LegacyDataConflictResolver { _, installed -> installed })

        File(distributionRoot, "datastore").deleteRecursively()

        val layout = resolve(environment, LegacyDataConflictResolver { _, _ -> error("must not ask") })
        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertEquals(installedRoot.canonicalFile, layout.userDataRoot.canonicalFile)
    }

// ── Distribution root ────────────────────────────────────────────────────

    @Test
    fun `a classes directory is a development run and never a portable installation`() {
        // A development run must not be read as a self-contained distribution, or a developer's own
        // build output would claim to deserve keeping its data.
        val classesDir = File(sandbox, "classes/kotlin/main").apply { mkdirs() }
        writeMutableState(classesDir)

        // The code source of a development run is the classes directory itself.
        val layout = resolve(windowsEnvironment(codeSource = classesDir))

        assertEquals(AppDataMode.CUSTOM, layout.mode)
        assertEquals(classesDir.canonicalFile, layout.userDataRoot.canonicalFile)
        assertFalse(File(classesDir, "portable.flag").exists())
    }

    @Test
    fun `a build directory named in the path is still an installation`() {
        // The previous implementation read a `build` path segment as "development". That is wrong:
        // CI extracts releases under paths containing it, and a user may unpack anywhere.
        val unpacked = File(sandbox, "build/release/QTranslate").apply { mkdirs() }
        val jar = File(unpacked, "QTranslate.jar").apply { writeText("jar") }

        val layout = resolve(windowsEnvironment(codeSource = jar))

        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertEquals(installedRoot.canonicalFile, layout.userDataRoot.canonicalFile)
    }

    @Test
    fun `legacy data in a path containing build is still recognised`() {
        // The same path mistake would have skipped migration for any user whose portable copy lives
        // under a directory named `build` or `target`.
        val unpacked = File(sandbox, "build/QTranslate").apply { mkdirs() }
        writeMutableState(unpacked)

        val layout = resolve(
            windowsEnvironment(codeSource = File(unpacked, "QTranslate.jar").apply { writeText("jar") })
        )

        assertEquals(AppDataMode.PORTABLE, layout.mode)
        assertTrue(layout.adoptedLegacyPortableData)
        assertEquals(unpacked.canonicalFile, layout.userDataRoot.canonicalFile)
    }

    @Test
    fun `a JAR beside its distribution root resolves that root`() {
        distributionWithJar()
        val layout = resolve(windowsEnvironment(codeSource = File(distributionRoot, "QTranslate.jar")))

        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertEquals(distributionRoot.canonicalFile, layout.installationRoot?.canonicalFile)
    }

    @Test
    fun `a JAR with a marker beside it is portable`() {
        // The plain portable ZIP: one JAR, one marker, one folder that travels together.
        distributionWithJar()
        distributionWithMarker()
        val layout = resolve(windowsEnvironment(codeSource = File(distributionRoot, "QTranslate.jar")))

        assertEquals(AppDataMode.PORTABLE, layout.mode)
        assertEquals(distributionRoot.canonicalFile, layout.userDataRoot.canonicalFile)
    }

    @Test
    fun `the launcher path wins over the code source`() {
        val launcherRoot = File(sandbox, "launcher-root").apply { mkdirs() }
        val jarRoot = File(sandbox, "jar-root").apply { mkdirs() }
        val layout = resolve(
            windowsEnvironment(
                launcherPath = File(launcherRoot, "QTranslate.exe").path,
                codeSource = File(jarRoot, "QTranslate.jar").apply { writeText("jar") }
            )
        )

        assertEquals(launcherRoot.canonicalFile, layout.installationRoot?.canonicalFile)
    }

    @Test
    fun `an undeterminable distribution root still resolves installed data`() {
        val layout = AppDataLayout.resolve(
            windowsEnvironment(launcherPath = null, codeSource = null),
            AppDataLayout.KEEP_INSTALLED_DATA
        )

        assertEquals(AppDataMode.INSTALLED, layout.mode)
        assertEquals(null, layout.installationRoot)
        assertEquals(installedRoot.canonicalFile, layout.userDataRoot.canonicalFile)
    }

    // ── Portability across platforms ─────────────────────────────────────────

    @Test
    fun `the OS data location follows the platform`() {
        val cases = listOf(
            Triple("Windows 11", "APPDATA" to "AppData/Roaming", "AppData/Roaming/QTranslate"),
            Triple("Mac OS X", null to null, "Library/Application Support/QTranslate"),
            Triple("Linux", null to null, ".config/QTranslate")
        )
        for ((osName, xdg, expectedSuffix) in cases) {
            val environment = AppDataLayout.Environment(
                osName = osName,
                userHome = "/home/tester",
                environment = { key -> xdg?.takeIf { it.first == key }?.second }
            )
            val root = AppDataLayout.installedUserDataRoot(environment)
            assertTrue(
                root.path.replace('\\', '/').endsWith(expectedSuffix),
                "$osName resolved to ${root.path}, expected it to end with $expectedSuffix"
            )
        }
    }

    @Test
    fun `XDG_CONFIG_HOME is honoured on Linux`() {
        val environment = AppDataLayout.Environment(
            osName = "Linux",
            userHome = "/home/tester",
            environment = { key -> if (key == "XDG_CONFIG_HOME") "/custom/config" else null }
        )
        assertEquals(
            File("/custom/config", "QTranslate").absolutePath,
            AppDataLayout.installedUserDataRoot(environment).absolutePath
        )
    }

    @Test
    fun `a marker selects portable mode on every platform`() {
        distributionWithMarker()
        val environment = AppDataLayout.Environment(
            launcherPath = File(distributionRoot, "QTranslate.exe").path,
            osName = "Linux",
            userHome = "/home/tester",
            environment = { null },
            markerExists = { it.isFile }
        )
        val layout = AppDataLayout.resolve(environment, AppDataLayout.KEEP_INSTALLED_DATA)

        assertEquals(AppDataMode.PORTABLE, layout.mode)
        assertEquals(distributionRoot.canonicalFile, layout.userDataRoot.canonicalFile)
    }
}