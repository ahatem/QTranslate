package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.ApiVersion
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.core.shared.util.Hashing
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.util.*

/**
 * Handles scanning, loading, and inspecting plugin JAR files.
 */
class PluginLoader(
    private val logger: Logger
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Loads all plugins from a directory, sorted by jar filename descending.
     */
    fun loadPluginsFromDirectory(directory: File): List<LoadedPluginResult> {
        if (!directory.isDirectory) return emptyList()

        val jarFiles = directory.listFiles { it.extension == "jar" }.orEmpty()
        return jarFiles.mapNotNull { loadPluginFromFile(it) }
            .sortedByDescending { it.manifest.version }
    }

    /**
     * Loads a single plugin from a JAR file.
     * Returns null if invalid or incompatible.
     */
    fun loadPluginFromFile(jarFile: File): LoadedPluginResult? = runCatching {
        val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), javaClass.classLoader)

        val plugin = ServiceLoader.load(Plugin::class.java, classLoader)
            .firstOrNull() ?: throw IllegalStateException("No Plugin implementation found in ${jarFile.name}")

        val manifest = getManifestFromJar(jarFile, classLoader)
            ?: throw IllegalStateException("plugin.json manifest missing or invalid in ${jarFile.name}")

        if (!isApiCompatible(manifest.minApiVersion, ApiVersion.VERSION)) {
            logger.error(
                "Skipping '${manifest.id}' (v${manifest.version}): requires API v${manifest.minApiVersion}, app is v${ApiVersion.VERSION}"
            )
            return null
        }

        val hash = Hashing.sha256(jarFile)

        LoadedPluginResult(plugin, manifest, jarFile, hash, classLoader)
    }.getOrElse {
        logger.error("Failed to load plugin from ${jarFile.name}", it)
        null
    }

    /**
     * Reads and parses plugin.json from a JAR using the plugin's classloader.
     */
    fun getManifestFromJar(jarFile: File, classLoader: ClassLoader? = null): PluginManifest? = runCatching {
        val loader = classLoader ?: URLClassLoader(arrayOf(jarFile.toURI().toURL()), javaClass.classLoader)
        loader.getResourceAsStream("plugin.json")?.use { stream ->
            stream.reader(Charsets.UTF_8).use { reader ->
                json.decodeFromString<PluginManifest>(reader.readText())
            }
        }
    }.getOrNull()

    /**
     * Checks major version compatibility only.
     */
    private fun isApiCompatible(pluginApi: String, appApi: String): Boolean {
        val pluginMajor = pluginApi.substringBefore('.').toIntOrNull() ?: 0
        val appMajor = appApi.substringBefore('.').toIntOrNull() ?: 0
        return pluginMajor != 0 && pluginMajor == appMajor
    }
}

