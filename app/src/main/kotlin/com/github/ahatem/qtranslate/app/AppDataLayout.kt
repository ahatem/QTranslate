package com.github.ahatem.qtranslate.app

import java.io.File

/**
 * How QTranslate decided where user data lives.
 *
 * - [INSTALLED] — a normal installation. Data goes to the OS per-user data location and does not
 *   depend on whether the install directory happens to be writable or on the process being
 *   elevated.
 * - [PORTABLE] — a distribution that declares itself portable with a `portable.flag` beside the
 *   launcher, or a pre-marker 1.5.x layout whose existing data was adopted. Data lives in the
 *   distribution root and moves with it.
 * - [CUSTOM] — a data directory supplied explicitly with `-DappData`, or a development run.
 *   Used for its location only; it is never treated as a declared portable installation and never
 *   gets a `portable.flag` written next to it.
 */
enum class AppDataMode { INSTALLED, PORTABLE, CUSTOM }

/**
 * The two roots QTranslate works from, and the mode that chose them.
 *
 * [installationRoot] holds bundled, immutable distribution content: plugin JARs, language files,
 * theme files, icon sets. [userDataRoot] holds everything mutable: settings, history, logs, plugin
 * key/value data and secrets, the plugin registry, and user-installed plugins.
 *
 * The two are the same directory in [AppDataMode.PORTABLE] and deliberately different in
 * [AppDataMode.INSTALLED]. [installationRoot] is null only when no distribution root could be
 * determined at all, in which case only the classpath and [userDataRoot] supply resources.
 */
data class ResolvedAppData(
    val mode: AppDataMode,
    val installationRoot: File?,
    val userDataRoot: File,
    /**
     * True when an unmarked 1.5.x layout beside the application was recognised as a user's own
     * portable data and a marker was written to make that explicit. Nothing was moved or deleted.
     */
    val adoptedLegacyPortableData: Boolean = false
)

/** Raised when the selected user data directory cannot be created or written. */
class AppDataLayoutException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Chooses between two data directories that both hold real user state.
 *
 * There is no correct answer the resolver can infer on its own, so the application asks. A
 * headless run has nobody to ask and supplies a deterministic choice instead.
 */
fun interface LegacyDataConflictResolver {
    fun resolve(legacyPortableRoot: File, installedRoot: File): File
}

/**
 * Resolves the [ResolvedAppData] for this process.
 *
 * The mode is decided by an explicit override or by a marker file, never by whether a directory
 * happens to be writable. Writability was the previous rule and it was environment-dependent:
 * the same installation stored data in two different places depending on UAC elevation, and moving
 * a folder or mounting it read-only silently relocated a user's settings and history.
 */
object AppDataLayout {

    /** Present beside the launcher, this file is what declares a distribution portable. */
    const val PORTABLE_MARKER = "portable.flag"

    /** System property that overrides the data directory outright. */
    const val EXPLICIT_DATA_DIR_PROPERTY = "appData"

    /** Set by the jpackage launcher to the absolute path of the packaged executable. */
    const val LAUNCHER_PATH_PROPERTY = "jpackage.app-path"

    /**
     * Directories that prove the application itself wrote there.
     *
     * None of these ships in any distribution — not in the portable ZIP built by `assemblePortable`
     * and not in the Windows app-image built by `package-windows.ps1` — so none can be present in
     * a freshly extracted copy that has never been run. That is why shipped folders such as
     * `plugins/`, `languages/`, `themes/` and `icons/` are excluded: those are present in a fresh
     * extraction and would misreport it as somebody's existing installation.
     */
    private val MUTABLE_STATE_SENTINELS = listOf("datastore", "plugins_data", "logs")

    private const val APP_DIRECTORY_NAME = "QTranslate"

    /**
     * Headless fallback for [LegacyDataConflictResolver]: keep the OS-standard location and leave
     * the legacy directory untouched on disk. The release probe never reaches this because it
     * always sets [EXPLICIT_DATA_DIR_PROPERTY].
     */
    val KEEP_INSTALLED_DATA = LegacyDataConflictResolver { _, installedRoot -> installedRoot }

    /**
     * The ambient facts the rules are applied to, injected so each rule can be tested directly.
     *
     * [writable] is the filesystem's answer for the chosen directory. It is injected rather than read
     * through `File.canWrite()` at the point of decision because its value must not be able to
     * influence the mode: it is consulted only to report that a location cannot be used.
     */
    internal data class Environment(
        val explicitDataDir: String? = null,
        val launcherPath: String? = null,
        val codeSource: File? = null,
        /**
         * Whether this is a development run, decided by the code source being a directory of compiled
         * classes rather than a JAR or a packaged launcher.
         *
         * That is the signal rather than a folder name: a build directory called `build` also appears
         * in CI paths, and in any installation path a user chooses. What actually distinguishes a
         * development run is that nothing was packaged.
         */
        val developmentBuild: Boolean = codeSource?.isDirectory == true,
        val osName: String = "",
        val userHome: String = "",
        val environment: (String) -> String? = { null },
        val markerExists: (File) -> Boolean = { it.isFile },
        val mutableStatePresent: (File) -> Boolean = ::hasMutableState,
        val writable: (File) -> Boolean = { it.canWrite() }
    )

    /** Resolves the layout for the running process. */
    fun resolveForCurrentProcess(conflictResolver: LegacyDataConflictResolver): ResolvedAppData =
        resolve(
            Environment(
                explicitDataDir = System.getProperty(EXPLICIT_DATA_DIR_PROPERTY),
                launcherPath = System.getProperty(LAUNCHER_PATH_PROPERTY),
                codeSource = codeSourceLocation(),
                osName = System.getProperty("os.name").orEmpty(),
                userHome = System.getProperty("user.home").orEmpty(),
                environment = { System.getenv(it) }
            ),
            conflictResolver
        )

    /**
     * Applies the precedence rules.
     *
     * 1. An explicit `-DappData` wins outright.
     * 2. A `portable.flag` beside the launcher declares the distribution portable.
     * 3. Otherwise the data goes to the OS per-user location — except when unmarked data written
     *    by a pre-marker 1.5.x release is found beside the application and the OS location is
     *    empty, in which case that data is adopted and marked so the choice is stable from then on.
     *
     * A build output directory resolves before the marker is consulted, so running from
     * `build/classes` is never mistaken for a portable installation.
     */
    internal fun resolve(
        environment: Environment,
        conflictResolver: LegacyDataConflictResolver
    ): ResolvedAppData {
        val distributionRoot = distributionRoot(environment)

        environment.explicitDataDir?.trim()?.takeIf { it.isNotEmpty() }?.let { explicit ->
            return ResolvedAppData(
                mode = AppDataMode.CUSTOM,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(File(explicit), environment.writable)
            )
        }

        if (distributionRoot == null) {
            return ResolvedAppData(
                mode = AppDataMode.INSTALLED,
                installationRoot = null,
                userDataRoot = prepareUserDataRoot(installedUserDataRoot(environment), environment.writable)
            )
        }

        if (environment.developmentBuild && distributionRoot != null) {
            // A development run keeps writing beside its own classes, as it always has, so it never
            // writes into the real per-user location it would then share with an installed copy.
            return ResolvedAppData(
                mode = AppDataMode.CUSTOM,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(distributionRoot, environment.writable)
            )
        }

        if (environment.markerExists(File(distributionRoot, PORTABLE_MARKER))) {
            return ResolvedAppData(
                mode = AppDataMode.PORTABLE,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(distributionRoot, environment.writable)
            )
        }

        val installedRoot = installedUserDataRoot(environment)

        // Pre-marker 1.5.x stored data beside the launcher whenever it could. Recognising that data
        // is the only way to keep it; relocating it would be the one unrecoverable outcome.
        if (!environment.mutableStatePresent(distributionRoot)) {
            return ResolvedAppData(
                mode = AppDataMode.INSTALLED,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(installedRoot, environment.writable)
            )
        }

        val chosen = if (environment.mutableStatePresent(installedRoot)) {
            conflictResolver.resolve(distributionRoot, installedRoot)
        } else {
            distributionRoot
        }

        return if (isSameDirectory(chosen, distributionRoot)) {
            writePortableMarker(distributionRoot)
            ResolvedAppData(
                mode = AppDataMode.PORTABLE,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(distributionRoot, environment.writable),
                adoptedLegacyPortableData = true
            )
        } else {
            ResolvedAppData(
                mode = AppDataMode.INSTALLED,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(chosen, environment.writable)
            )
        }
    }

    /**
     * The directory holding the bundled distribution, or null when it cannot be determined.
     *
     * A packaged build reports it through [LAUNCHER_PATH_PROPERTY]; a plain JAR or a classes
     * directory reports it through the code source. A code source that is itself a directory is the
     * classes output, and is used directly rather than through its parent — that directory *is* the
     * run's root.
     */
    private fun distributionRoot(environment: Environment): File? =
        environment.launcherPath?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { File(it).parentFile }
            ?: environment.codeSource?.let { if (it.isDirectory) it else it.parentFile }

    /**
     * Whether [root] holds state this application wrote.
     *
     * See [MUTABLE_STATE_SENTINELS] for why the list is limited to never-shipped directories.
     */
    internal fun hasMutableState(root: File): Boolean =
        MUTABLE_STATE_SENTINELS.any { name ->
            File(root, name).let { it.isDirectory && it.list()?.isNotEmpty() == true }
        }

    

    /**
     * The OS per-user data location: `%APPDATA%\QTranslate`, `~/Library/Application
     * Support/QTranslate`, or `$XDG_CONFIG_HOME/QTranslate`.
     */
    internal fun installedUserDataRoot(environment: Environment): File {
        val osName = environment.osName.lowercase()
        val base = when {
            osName.contains("win") ->
                environment.environment("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: File(environment.userHome, "AppData/Roaming").path
            osName.contains("mac") ->
                File(File(environment.userHome, "Library"), "Application Support").path
            else ->
                environment.environment("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                    ?: File(environment.userHome, ".config").path
        }
        return File(base, APP_DIRECTORY_NAME)
    }

    /**
     * Creates [directory] if needed and proves it is usable.
     *
     * A failure here is reported rather than worked around. Falling back to another location —
     * least of all the installation directory — would mean starting up against a data directory the
     * user never chose, which is the exact failure this resolver exists to remove.
     */
    private fun prepareUserDataRoot(directory: File, writable: (File) -> Boolean): File {
        val path = directory.absolutePath
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw AppDataLayoutException(
                "QTranslate could not create its data directory:\n  $path\n" +
                    "Choose a writable location, or clear the read-only flag, and start QTranslate again."
            )
        }
        // Writability never selects the mode; it only reports that the location already chosen is
        // unusable. Checking it here turns a silent divergence into a clear failure rather than
        // letting the application quietly store data somewhere the user did not pick.
        if (!writable(directory)) {
            throw AppDataLayoutException(
                "QTranslate's data directory is not writable:\n  $path\n" +
                    "QTranslate will not store your data somewhere else. Move the application to a " +
                    "writable folder, or clear the read-only flag, and start QTranslate again."
            )
        }
        return directory
    }

    /**
     * Writes [PORTABLE_MARKER] so an adopted legacy layout keeps resolving as portable.
     *
     * Best effort: a distribution on read-only media cannot be written to, and re-detecting the
     * same legacy data on the next run is harmless, so this is not worth failing startup over.
     */
    private fun writePortableMarker(distributionRoot: File) {
        val marker = File(distributionRoot, PORTABLE_MARKER)
        runCatching { if (!marker.exists()) marker.createNewFile() }
    }

    private fun isSameDirectory(left: File, right: File): Boolean =
        left.absoluteFile.normalize() == right.absoluteFile.normalize()

    private fun codeSourceLocation(): File? = runCatching {
        AppDataLayout::class.java.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
    }.getOrNull()
}