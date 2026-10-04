package com.github.ahatem.qtranslate.app

import java.io.File

/**
 * How QTranslate decided where user data lives.
 *
 * - [INSTALLED] — a normal installation. Data goes to the OS per-user location and does not depend on
 *   whether the install directory is writable or on the process being elevated.
 * - [PORTABLE] — a distribution declaring itself portable with a `portable.flag` beside the launcher.
 *   Data lives in the distribution root and moves with it.
 * - [CUSTOM] — a data directory supplied with `-DappData`, or a development run. Used for its
 *   location only; never treated as a declared portable installation.
 */
enum class AppDataMode { INSTALLED, PORTABLE, CUSTOM }

/**
 * The two roots QTranslate works from, and the mode that chose them.
 *
 * [installationRoot] holds bundled, immutable distribution content: plugin JARs, language files,
 * theme files, icon sets. [userDataRoot] holds everything mutable. The two are the same directory in
 * [AppDataMode.PORTABLE] and deliberately different in [AppDataMode.INSTALLED] — an installed copy
 * must never write into the directory it was installed to.
 */
data class ResolvedAppData(
    val mode: AppDataMode,
    val installationRoot: File?,
    val userDataRoot: File,
    /** True when unmarked 1.5.x data beside the application was adopted and a marker written. */
    val adoptedLegacyPortableData: Boolean = false
)

/** Raised when the selected user data directory cannot be created or written. */
class AppDataLayoutException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Chooses between two data directories that both hold real user state. There is no correct answer the
 * resolver can infer, so the application asks; a headless run has nobody to ask.
 */
fun interface LegacyDataConflictResolver {
    fun resolve(legacyPortableRoot: File, installedRoot: File): File
}

/** Which storage mode a user picked when their data existed in two places at once. */
enum class LegacyConflictChoice { PORTABLE, INSTALLED }

/**
 * Resolves the [ResolvedAppData] for this process.
 *
 * The mode comes from an explicit override or a marker file, never from whether a directory happens
 * to be writable. Writability was the previous rule and it was environment-dependent: the same
 * installation stored data in two places depending on UAC elevation, and moving a folder or mounting
 * it read-only silently relocated a user's settings and history.
 */
object AppDataLayout {

    /** Present beside the launcher, this file is what declares a distribution portable. */
    const val PORTABLE_MARKER = "portable.flag"

    /** System property that overrides the data directory outright. */
    const val EXPLICIT_DATA_DIR_PROPERTY = "appData"

    /** Set by the jpackage launcher to the absolute path of the packaged executable. */
    const val LAUNCHER_PATH_PROPERTY = "jpackage.app-path"

    /**
     * Records that a user with data in two places chose the OS-standard location, so the question is
     * not asked again on every launch.
     *
     * It lives in the user's own data directory rather than beside the application, because choosing
     * the installed location must not require writing to the installation directory — which is
     * exactly the one that is read-only under Program Files. It holds only the chosen mode: no user
     * data and nothing secret.
     */
    const val MODE_DECISION_FILE = "data-location-choice"

    /**
     * Directories that prove the application itself wrote there.
     *
     * None ships in any distribution, so none can be present in a freshly extracted copy that has
     * never been run. That is why shipped folders such as `plugins/`, `languages/`, `themes/` and
     * `icons/` are excluded: they are present in a fresh extraction and would misreport it as
     * somebody's existing installation.
     */
    private val MUTABLE_STATE_SENTINELS = listOf("datastore", "plugins_data", "logs")

    private const val APP_DIRECTORY_NAME = "QTranslate"

    /**
     * Headless fallback for [LegacyDataConflictResolver]: keep the OS-standard location and leave the
     * legacy directory untouched. The release probe never reaches this — it always sets
     * [EXPLICIT_DATA_DIR_PROPERTY].
     */
    val KEEP_INSTALLED_DATA = LegacyDataConflictResolver { _, installedRoot -> installedRoot }

    /**
     * The ambient facts the rules are applied to, injected so each rule can be tested directly.
     *
     * [writable] is the filesystem's answer for the chosen directory, injected rather than read at
     * the point of decision because its value must not be able to influence the mode. [developmentBuild]
     * is decided by the code source being a directory of classes rather than a JAR or a launcher, so
     * that no path a user or CI happens to choose can be mistaken for a development run.
     */
    internal data class Environment(
        val explicitDataDir: String? = null,
        val launcherPath: String? = null,
        val codeSource: File? = null,
        val developmentBuild: Boolean = codeSource?.isDirectory == true,
        val osName: String = "",
        val userHome: String = "",
        val environment: (String) -> String? = { null },
        val markerExists: (File) -> Boolean = { it.isFile },
        val mutableStatePresent: (File) -> Boolean = ::hasMutableState,
        val writable: (File) -> Boolean = { it.canWrite() },
        val installedChoiceRecorded: (File) -> Boolean = { File(it, MODE_DECISION_FILE).isFile }
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
     * 3. Otherwise data goes to the OS per-user location — except for unmarked data a pre-marker 1.5.x
     *    release left beside the application, which is adopted and marked so the choice is stable.
     *
     * A development run is resolved first of all, so `build/classes` is never read as an installation.
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

        if (environment.developmentBuild) {
            // Keeps writing beside its own classes, so a developer run never writes into the real
            // per-user location it would then share with an installed copy.
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

        // Checked before the legacy branch because that is exactly where the prompt would otherwise
        // reappear on every launch.
        if (environment.installedChoiceRecorded(installedRoot)) {
            return ResolvedAppData(
                mode = AppDataMode.INSTALLED,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(installedRoot, environment.writable)
            )
        }

        // Pre-marker 1.5.x stored data beside the launcher whenever it could. Recognising it is the only
        // way to keep it; relocating it would be the one unrecoverable outcome.
        if (!environment.mutableStatePresent(distributionRoot)) {
            return ResolvedAppData(
                mode = AppDataMode.INSTALLED,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(installedRoot, environment.writable)
            )
        }

        if (!environment.mutableStatePresent(installedRoot)) {
            // Only one place holds data, so there is no choice to make: keep the legacy data where it
            // is and mark the distribution so this stays true from now on.
            writePortableMarker(distributionRoot)
            return ResolvedAppData(
                mode = AppDataMode.PORTABLE,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(distributionRoot, environment.writable),
                adoptedLegacyPortableData = true
            )
        }

        val chosen = conflictResolver.resolve(distributionRoot, installedRoot)
        return if (isSameDirectory(chosen, distributionRoot)) {
            writePortableMarker(distributionRoot)
            ResolvedAppData(
                mode = AppDataMode.PORTABLE,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(distributionRoot, environment.writable),
                adoptedLegacyPortableData = true
            )
        } else {
            recordInstalledChoice(installedRoot)
            ResolvedAppData(
                mode = AppDataMode.INSTALLED,
                installationRoot = distributionRoot,
                userDataRoot = prepareUserDataRoot(chosen, environment.writable)
            )
        }
    }

    /** The directory holding the bundled distribution, or null when it cannot be determined. */
    private fun distributionRoot(environment: Environment): File? =
        environment.launcherPath?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { File(it).parentFile }
            ?: environment.codeSource?.let { if (it.isDirectory) it else it.parentFile }

    /** Whether [root] holds state this application wrote. See [MUTABLE_STATE_SENTINELS]. */
    internal fun hasMutableState(root: File): Boolean =
        MUTABLE_STATE_SENTINELS.any { name ->
            File(root, name).let { it.isDirectory && it.list()?.isNotEmpty() == true }
        }

    /** The OS per-user data location: `%APPDATA%`, `~/Library/Application Support`, or `$XDG_CONFIG_HOME`. */
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
     * A failure is reported rather than worked around. Falling back to another location — least of all
     * the installation directory — would mean starting up against a data directory the user never
     * chose, which is the failure this resolver exists to remove.
     */
    private fun prepareUserDataRoot(directory: File, writable: (File) -> Boolean): File {
        val path = directory.absolutePath
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw AppDataLayoutException(
                "QTranslate could not create its data directory:\n  $path\n" +
                    "Choose a writable location, or clear the read-only flag, and start QTranslate again."
            )
        }
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
     * Best effort: a distribution on read-only media cannot be written to, and re-detecting the same
     * legacy data on the next run is harmless, so this is not worth failing startup over.
     */
    private fun writePortableMarker(distributionRoot: File) {
        val marker = File(distributionRoot, PORTABLE_MARKER)
        runCatching { if (!marker.exists()) marker.createNewFile() }
    }

    /**
     * Records the OS-standard choice so the conflict is not raised again.
     *
     * Nothing is deleted or moved: the legacy directory beside the application is left untouched, and
     * deleting this one file reopens the question. Best effort for the same reason as
     * [writePortableMarker] — losing it costs a repeat question, not data.
     */
    private fun recordInstalledChoice(installedRoot: File) {
        runCatching {
            val file = File(installedRoot, MODE_DECISION_FILE)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(LegacyConflictChoice.INSTALLED.name)
            }
        }
    }

    private fun isSameDirectory(left: File, right: File): Boolean =
        left.absoluteFile.normalize() == right.absoluteFile.normalize()

    private fun codeSourceLocation(): File? = runCatching {
        AppDataLayout::class.java.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
    }.getOrNull()
}