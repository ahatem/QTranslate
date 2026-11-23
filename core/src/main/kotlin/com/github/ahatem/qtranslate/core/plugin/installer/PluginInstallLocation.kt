package com.github.ahatem.qtranslate.core.plugin.installer

import java.io.File

/**
 * Decides whether a plugin JAR is the user's to remove.
 *
 * Only plugins in the user's own folder are removable. Deleting a bundled plugin would leave the
 * installation missing a component with no way back short of reinstalling.
 */
internal object PluginInstallLocation {

    /**
     * Whether [jarFile] sits inside [userPluginsDirectory]. Paths are normalised first so a relative
     * path or a `..` segment still resolves to the same location.
     */
    fun isUserInstalled(userPluginsDirectory: File, jarFile: File): Boolean =
        runCatching {
            jarFile.toPath().toAbsolutePath().normalize()
                .startsWith(userPluginsDirectory.toPath().toAbsolutePath().normalize())
        }.getOrDefault(false)
}