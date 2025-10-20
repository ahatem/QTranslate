package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.plugin.Plugin
import java.io.File

/**
 * The result of a successful plugin load
 */
data class LoadedPluginResult(
    val plugin: Plugin<*>,
    val manifest: PluginManifest,
    val jarFile: File,
    val jarHash: String,
    val classLoader: ClassLoader
)