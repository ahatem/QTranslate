package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.ApiVersion
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.core.plugin.registry.PluginError
import com.github.ahatem.qtranslate.core.shared.util.Hashing
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/** Inspects plugin artifacts without loading provider classes until the host approves them. */
class PluginLoader(
    private val logger: Logger,
    private val cacheDirectory: File = Files.createTempDirectory("qtranslate-plugin-snapshots").toFile()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _loadFailures = mutableListOf<PluginError.LoadFailure>()
    val loadFailures: List<PluginError.LoadFailure> get() = synchronized(_loadFailures) { _loadFailures.toList() }

    /** Copies each JAR into the private cache, then reads only inert ZIP entries. */
    internal fun assessDirectory(directory: File): List<PluginAssessment> {
        synchronized(_loadFailures) { _loadFailures.clear() }
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { f -> f.extension == "jar" }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull(::assessFile)
            .sortedBy { it.manifest.id }
    }

    internal fun assessFile(jarFile: File): PluginAssessment? {
        var snapshot: File? = null
        return try {
            cacheDirectory.mkdirs()
            snapshot = Files.createTempFile(cacheDirectory.toPath(), "plugin-", ".jar").toFile()
            Files.copy(jarFile.toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val manifest: PluginManifest
            val provider: String
            JarFile(snapshot).use { jar ->
                provider = jar.getJarEntry("META-INF/services/${Plugin::class.java.name}")?.let { entry ->
                    jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { stream ->
                        stream.lineSequence().map { it.substringBefore('#').trim() }.firstOrNull { it.isNotEmpty() }
                    }
                } ?: error("Plugin implementation descriptor is missing")
                require(provider.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]*"))) { "Invalid Plugin implementation descriptor" }
                manifest = jar.getJarEntry("plugin.json")?.let { entry ->
                    jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { json.decodeFromString<PluginManifest>(it.readText()) }
                } ?: error("plugin.json is missing")
            }
            require(manifest.id.isNotBlank()) { "Plugin ID cannot be blank" }
            when (val compatibility = ApiVersion.isCompatible(manifest.minApiVersion)) {
                is ApiVersion.CompatibilityResult.Compatible -> Unit
                is ApiVersion.CompatibilityResult.Incompatible -> error("Incompatible plugin API: ${compatibility.reason}")
            }
            check(snapshot.setReadOnly()) { "Could not make plugin snapshot read-only" }
            PluginAssessment(manifest, jarFile, snapshot, Hashing.sha256(snapshot), provider)
        } catch (error: Exception) {
            snapshot?.let { it.setWritable(true); it.delete() }
            recordFailure(jarFile, "Failed to inspect plugin: ${error.message}", error)
            null
        }
    }

    /** Constructs a provider only from the previously assessed, approved snapshot. */
    internal fun loadApproved(assessment: PluginAssessment): LoadedPluginResult? {
        var loader: URLClassLoader? = null
        return try {
            require(Hashing.sha256(assessment.snapshotFile) == assessment.jarHash) { "Plugin snapshot changed" }
            val classLoader = object : URLClassLoader(arrayOf(assessment.snapshotFile.toURI().toURL()), javaClass.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name != assessment.providerClassName) return super.loadClass(name, resolve)
                    synchronized(getClassLoadingLock(name)) {
                        val resolved = findLoadedClass(name) ?: runCatching { findClass(name) }.getOrNull()
                            ?: super.loadClass(name, false)
                        if (resolve) resolveClass(resolved)
                        return resolved
                    }
                }
            }
            loader = classLoader
            val providerClass = Class.forName(assessment.providerClassName, false, classLoader)
            require(Plugin::class.java.isAssignableFrom(providerClass)) { "Provider does not implement Plugin" }
            require(providerClass.protectionDomain.codeSource?.location?.toURI() == assessment.snapshotFile.toURI()) {
                "Provider class did not originate in the approved JAR"
            }
            val plugin = providerClass.getDeclaredConstructor().newInstance() as Plugin<*>
            LoadedPluginResult(plugin, assessment.manifest, assessment.jarFile, assessment.jarHash, classLoader, assessment.snapshotFile)
        } catch (error: Throwable) {
            runCatching { loader?.close() }
            recordFailure(assessment.jarFile, "Failed to load approved plugin: ${error.message}", error, assessment.manifest.id)
            null
        }
    }

    /** Compatibility helper for existing direct callers; direct invocation explicitly approves the file. */
    fun loadPluginFromFile(jarFile: File): LoadedPluginResult? = assessFile(jarFile)?.let { assessment ->
        loadApproved(assessment).also { if (it == null) assessment.discard() }
    }

    /** Compatibility helper for callers that explicitly request executable providers. */
    fun loadPluginsFromDirectory(directory: File): List<LoadedPluginResult> =
        assessDirectory(directory).mapNotNull { assessment ->
            loadApproved(assessment).also { if (it == null) assessment.discard() }
        }

    /** Reads the manifest as a ZIP entry; this never asks a class loader for resources. */
    fun getManifestFromJar(jarFile: File, classLoader: ClassLoader? = null): PluginManifest? =
        runCatching {
            JarFile(jarFile).use { jar ->
                jar.getJarEntry("plugin.json")?.let { entry ->
                    jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { json.decodeFromString<PluginManifest>(it.readText()) }
                }
            }
        }.getOrNull()

    private fun recordFailure(jarFile: File, message: String, cause: Throwable?, pluginId: String = jarFile.nameWithoutExtension) {
        synchronized(_loadFailures) {
            _loadFailures += PluginError.LoadFailure(pluginId, message, cause, jarFile.absolutePath)
        }
        logger.error("PLUGIN FAILED ${jarFile.name}: $message", cause)
    }
}
