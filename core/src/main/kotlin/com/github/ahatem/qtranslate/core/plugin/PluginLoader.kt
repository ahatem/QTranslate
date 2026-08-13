package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.ApiVersion
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.core.plugin.registry.PluginError
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
    private val _loadFailures = mutableListOf<PluginError.LoadFailure>()

    /** Failures from the most recent directory scan, retained for UI reporting. */
    val loadFailures: List<PluginError.LoadFailure>
        get() = _loadFailures.toList()

    /**
     * Scans [directory] for JAR files, loads each one, and returns the results sorted by
     * manifest ID alphabetically — stable and deterministic across all platforms and runs.
     *
     * Why not sort by version? Two plugins at the same version (e.g. both "1.0.0") would
     * have undefined relative order, and updating one plugin could reorder unrelated ones.
     *
     * Why sort JARs by name first? [File.listFiles] returns entries in OS-defined order
     * (hash-table order on Linux ext4, creation order on NTFS, etc.) which is not stable
     * between runs. Sorting filenames before loading ensures consistent processing order.
     */
    fun loadPluginsFromDirectory(directory: File): List<LoadedPluginResult> {
        _loadFailures.clear()
        if (!directory.isDirectory) {
            logger.warn("Plugin directory does not exist or is not a directory: ${directory.absolutePath}")
            return emptyList()
        }
        return directory.listFiles { f -> f.extension == "jar" }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { loadPluginFromFile(it) }
            .sortedBy { it.manifest.id }
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
    fun loadPluginFromFile(jarFile: File): LoadedPluginResult? {
        val classLoader = runCatching {
            URLClassLoader(arrayOf(jarFile.toURI().toURL()), javaClass.classLoader)
        }.getOrElse { error ->
            recordFailure(jarFile, "Failed to open plugin JAR: ${error.message}", error)
            return null
        }

        return try {
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
                    recordFailure(
                        jarFile,
                        "Plugin '${manifest.id}' v${manifest.version} is incompatible: ${compat.reason}",
                        null,
                        manifest.id
                    )
                    classLoader.close()
                    return null
                }
            }

            val hash = Hashing.sha256(jarFile)
            LoadedPluginResult(plugin, manifest, jarFile, hash, classLoader)
        } catch (error: Throwable) {
            runCatching { classLoader.close() }
            recordFailure(jarFile, "Failed to load plugin: ${error.message}", error)
            null
        }
    }

    private fun recordFailure(
        jarFile: File,
        message: String,
        cause: Throwable?,
        pluginId: String = jarFile.nameWithoutExtension
    ) {
        _loadFailures += PluginError.LoadFailure(pluginId, message, cause, jarFile.absolutePath)
        logger.error("PLUGIN FAILED  ${jarFile.name}: $message", cause)
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
