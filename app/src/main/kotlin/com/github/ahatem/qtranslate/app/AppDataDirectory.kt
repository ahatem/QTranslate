package com.github.ahatem.qtranslate.app

import java.io.File

/**
 * Resolves the directory where QTranslate stores all persistent data
 * (settings, plugins, history, themes, etc.).
 *
 * ### Strategy: JAR-relative first, OS-standard fallback
 *
 * QTranslate is designed as a portable app — the data folder lives next to
 * the JAR so the entire installation can be moved or backed up as one unit.
 *
 * ```
 * QTranslate/
 *   ├── QTranslate.jar
 *   ├── plugins/
 *   ├── themes/
 *   ├── datastore/
 *   └── plugins_data/
 * ```
 *
 * If the JAR's parent directory is not writable (e.g. installed in
 * `C:\Program Files`), the OS-standard location is used as a fallback
 * so the app still works without elevated permissions.
 *
 * | Platform | Fallback path |
 * |----------|---------------|
 * | Windows  | `%APPDATA%\QTranslate` |
 * | macOS    | `~/Library/Application Support/QTranslate` |
 * | Linux    | `$XDG_CONFIG_HOME/QTranslate` or `~/.config/QTranslate` |
 */
object AppDataDirectory {

    private const val APP_NAME = "QTranslate"

    /**
     * Returns the resolved app data directory, creating it if it does not exist.
     */
    fun resolve(): File {
        // Debug override — set -DappData="C:\path\to\data" in your run config.
        // Never set in production; falls through to the normal resolution below.
        System.getProperty("appData")?.let { return File(it).also { f -> f.mkdirs() } }

        val jarDir = jarLocation()
        if (jarDir != null && jarDir.canWrite()) {
            return jarDir.also { it.mkdirs() }
        }
        return osFallback().also { it.mkdirs() }
    }

    /**
     * Returns the directory containing the running JAR, or `null` if the
     * location cannot be determined (e.g. running from an IDE or test runner).
     */
    private fun jarLocation(): File? = runCatching {
        val uri = AppDataDirectory::class.java
            .protectionDomain
            .codeSource
            .location
            .toURI()
        File(uri).parentFile
    }.getOrNull()

    private fun osFallback(): File {
        val base = when {
            os().contains("win") ->
                System.getenv("APPDATA")
                    ?: (System.getProperty("user.home") + "\\AppData\\Roaming")
            os().contains("mac") ->
                System.getProperty("user.home") + "/Library/Application Support"
            else ->
                System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                    ?: (System.getProperty("user.home") + "/.config")
        }
        return File(base, APP_NAME)
    }

    private fun os(): String =
        System.getProperty("os.name").orEmpty().lowercase()
}