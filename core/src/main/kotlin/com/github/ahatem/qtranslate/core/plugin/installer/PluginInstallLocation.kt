package com.github.ahatem.qtranslate.core.plugin.installer

import java.io.File

/**
 * Decides whether a plugin JAR is the user's to remove.
 *
 * Uninstalling a bundled plugin used to delete the file the distribution shipped, which for an
 * installed copy left a missing component with no way back short of reinstalling. Install and
 * uninstall now act only on the user's own folder, and this is the single rule both paths use.
 */
internal object PluginInstallLocation {

    /**
     * Whether [jarFile] sits inside [userPluginsDirectory].
     *
     * Paths are normalised before comparing so that a relative path, a `..` segment or a differently
     * spelled parent still resolves to the same location.
     */
    fun isUserInstalled(userPluginsDirectory: File, jarFile: File): Boolean =
        runCatching {
            jarFile.toPath().toAbsolutePath().normalize()
                .startsWith(userPluginsDirectory.toPath().toAbsolutePath().normalize())
        }.getOrDefault(false)
}