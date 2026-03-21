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
 * Handles scanning, loading, and inspecting plugin JAR files from the filesystem.
 *
 * `PluginLoader` is a pure I/O component — it reads JARs and produces [LoadedPluginResult]s.
 * It does not touch the in-memory registry, initialize plugins, or manage state.
 * All of that is the responsibility of [PluginManager] and its collaborators.
 */
class PluginLoader(
    private val logger: Logger
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Scans [directory] for JAR files, loads each one, and returns the results sorted by
     * manifest version descending (newest first).
     */
    fun loadPluginsFromDirectory(directory: File): List<LoadedPluginResult> {
        if (!directory.isDirectory) {
            logger.warn("Plugin directory does not exist or is not a directory: ${directory.absolutePath}")
            return emptyList()
        }
        return directory.listFiles { f -> f.extension == "jar" }
            .orEmpty()
            .mapNotNull { loadPluginFromFile(it) }
            .sortedByDescending { it.manifest.version }
    }

    /**
     * Loads and inspects a single plugin JAR file.
     *
     * Steps:
     * 1. Creates a [URLClassLoader] for the JAR.
     * 2. Discovers the [Plugin] implementation via [ServiceLoader].
     * 3. Reads and parses `plugin.json` from the JAR's resources.
     * 4. Verifies API compatibility via [ApiVersion.isCompatible].
     * 5. Computes a SHA-256 hash of the JAR for integrity tracking.
     *
     * @return A [LoadedPluginResult] on success, or `null` if any step fails.
     */
    fun loadPluginFromFile(jarFile: File): LoadedPluginResult? = runCatching {
        val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), javaClass.classLoader)

        val plugin = ServiceLoader.load(Plugin::class.java, classLoader).firstOrNull()
            ?: throw IllegalStateException("No Plugin implementation found via ServiceLoader in ${jarFile.name}. " +
                    "Ensure META-INF/services/com.github.ahatem.qtranslate.api.plugin.Plugin is present.")

        val manifest = getManifestFromJar(jarFile, classLoader)
            ?: throw IllegalStateException("plugin.json is missing or could not be parsed in ${jarFile.name}")

        // Delegate to ApiVersion — this checks both MAJOR and MINOR, not just MAJOR.
        when (val compat = ApiVersion.isCompatible(manifest.minApiVersion)) {
            is ApiVersion.CompatibilityResult.Compatible -> {
                logger.debug("Plugin '${manifest.id}' API version ${manifest.minApiVersion} is compatible.")
            }
            is ApiVersion.CompatibilityResult.Incompatible -> {
                logger.error(
                    "Skipping '${manifest.id}' (v${manifest.version}): ${compat.reason}"
                )
                return null
            }
        }

        val hash = Hashing.sha256(jarFile)
        LoadedPluginResult(plugin, manifest, jarFile, hash, classLoader)

    }.getOrElse { e ->
        logger.error("Failed to load plugin from ${jarFile.name}: ${e.message}", e)
        null
    }

    /**
     * Reads and parses `plugin.json` from a JAR without fully loading the plugin.
     * Useful for manifest-only inspection (e.g. during install validation).
     */
    fun getManifestFromJar(jarFile: File, classLoader: ClassLoader? = null): PluginManifest? =
        runCatching {
            val loader = classLoader
                ?: URLClassLoader(arrayOf(jarFile.toURI().toURL()), javaClass.classLoader)
            loader.getResourceAsStream("plugin.json")?.use { stream ->
                stream.reader(Charsets.UTF_8).use { json.decodeFromString<PluginManifest>(it.readText()) }
            }
        }.getOrNull()
}
