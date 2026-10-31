package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.core.ApiVersion
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprint
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class PluginLoaderTest {
    private val providerDescriptor = "META-INF/services/com.github.ahatem.qtranslate.api.plugin.Plugin"

    private fun writeJar(directory: java.io.File, name: String, id: String, provider: String = "example.Provider", includeProviderClass: Boolean = false):
        java.io.File {
        val file = directory.resolve(name)
        JarOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(JarEntry(providerDescriptor))
            out.write(provider.toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry("plugin.json"))
            out.write("""{"id":"$id","name":"Test","version":"1.0.0","author":"test","description":"test","minApiVersion":"${ApiVersion.VERSION}"}""".toByteArray())
            out.closeEntry()
            if (includeProviderClass) {
                val classPath = provider.replace('.', '/') + ".class"
                out.putNextEntry(JarEntry(classPath))
                requireNotNull(FixturePlugin::class.java.classLoader.getResourceAsStream(classPath)).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `assessment reads descriptor without constructing or loading its class`() {
        val directory = Files.createTempDirectory("qtranslate-plugin-assess").toFile()
        try {
            val jar = writeJar(directory, "plugin.jar", "test", ConstructorSentinel::class.java.name)
            val loader = PluginLoader(NoOpLogger, directory.resolve("cache"))
            ConstructorSentinel.constructions = 0

            val assessment = loader.assessFile(jar)
            assertTrue(assessment != null, loader.loadFailures.map { it.message }.toString())

            assertEquals(0, ConstructorSentinel.constructions)
            assertEquals("test", assessment.manifest.id)
            assertEquals(jar.absolutePath, assessment.jarFile.absolutePath)
            assertTrue(assessment.snapshotFile.isFile)
            assessment.discard()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `approved loader rejects provider from parent classpath`() {
        val directory = Files.createTempDirectory("qtranslate-plugin-origin").toFile()
        try {
            val jar = writeJar(directory, "plugin.jar", "test", ConstructorSentinel::class.java.name)
            val loader = PluginLoader(NoOpLogger, directory.resolve("cache"))
            ConstructorSentinel.constructions = 0
            val assessment = loader.assessFile(jar)
            assertTrue(assessment != null, loader.loadFailures.map { it.message }.toString())

            assertNull(loader.loadApproved(assessment))
            assertEquals(0, ConstructorSentinel.constructions)
            assertTrue(loader.loadFailures.single().message.contains("approved JAR"))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `approved loader constructs provider from assessed snapshot after source changes`() {
        val directory = Files.createTempDirectory("qtranslate-plugin-approved").toFile()
        try {
            val jar = writeJar(directory, "plugin.jar", "original", FixturePlugin::class.java.name, includeProviderClass = true)
            val loader = PluginLoader(NoOpLogger, directory.resolve("cache"))
            val assessment = loader.assessFile(jar)!!
            writeJar(directory, "plugin.jar", "replacement")
            System.clearProperty(FIXTURE_PROBE_KEY)

            val loaded = loader.loadApproved(assessment)

            assertTrue(loaded != null, loader.loadFailures.map { it.message }.toString())
            assertEquals("constructed", System.getProperty(FIXTURE_PROBE_KEY))
            assertEquals("original", loaded.manifest.id)
            assertEquals(jar.absolutePath, loaded.jarFile.absolutePath)
            (loaded.classLoader as java.net.URLClassLoader).close()
            assessment.discard()
        } finally {
            System.clearProperty(FIXTURE_PROBE_KEY)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `assessed snapshot remains the original bytes when source jar changes`() {
        val directory = Files.createTempDirectory("qtranslate-plugin-snapshot").toFile()
        try {
            val jar = writeJar(directory, "plugin.jar", "original")
            val loader = PluginLoader(NoOpLogger, directory.resolve("cache"))
            val assessment = loader.assessFile(jar)
            assertTrue(assessment != null, loader.loadFailures.map { it.message }.toString())
            writeJar(directory, "plugin.jar", "replacement")

            assertEquals("original", loader.getManifestFromJar(assessment.snapshotFile)?.id)
            assertEquals("replacement", loader.getManifestFromJar(jar)?.id)
            assessment.discard()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `duplicate IDs in one folder are rejected before loading`() {
        val directory = Files.createTempDirectory("qtranslate-plugin-duplicates").toFile()
        try {
            writeJar(directory, "a.jar", "same")
            writeJar(directory, "b.jar", "same")
            val assessments = PluginLoader(NoOpLogger, directory.resolve("cache")).assessDirectory(directory)

            val outcome = PluginRegistry().validateAndFilter(assessments)

            assertTrue(outcome.successful.isEmpty())
            assertEquals(2, outcome.skipped.size)
            assertTrue(assessments.none { it.snapshotFile.exists() })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `changed known jar constructs provider only after approval from assessed snapshot`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-plugin-pending").toFile()
        try {
            directory.resolve("datastore").mkdirs()
            val plugins = directory.resolve("plugins").apply { mkdirs() }
            val jar = writeJar(plugins, "plugin.jar", "known", FixturePlugin::class.java.name, includeProviderClass = true)
            val fingerprints = PluginFingerprintRepository(directory, Json)
            fingerprints.storeFingerprints(listOf(PluginFingerprint("known", "old-hash")))
            val loggerFactory = object : LoggerFactory {
                override fun getLogger(name: String): Logger = NoOpLogger
            }
            val manager = PluginManager(
                appDataDirectory = directory,
                settingsRepository = SettingsRepository(directory, Json, NoOpLogger),
                pluginFingerprintRepository = fingerprints,
                pluginKeyValueStore = PluginKeyValueStore(directory),
                loggerFactory = loggerFactory,
                notificationBus = NotificationBus(),
                networkConfig = { NetworkConfig() }
            )
            System.clearProperty(FIXTURE_PROBE_KEY)

            manager.loadAndProcessPlugins()

            assertNull(System.getProperty(FIXTURE_PROBE_KEY))
            val state = manager.plugins.value.single()
            assertEquals(PluginStatus.AWAITING_VERIFICATION, state.status)
            assertEquals(jar.absolutePath, state.jarPath)
            writeJar(plugins, "plugin.jar", "replacement")
            manager.resolveAsUpdate("known")
            assertEquals("constructed", System.getProperty(FIXTURE_PROBE_KEY))
            assertEquals(PluginStatus.ENABLED, manager.plugins.value.single().status)
            assertEquals("known", manager.plugins.value.single().id)
            manager.shutdown()
        } finally {
            System.clearProperty(FIXTURE_PROBE_KEY)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `user jar shadows bundled jar before either provider is constructed`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-plugin-shadow").toFile()
        try {
            directory.resolve("datastore").mkdirs()
            val userJar = writeJar(directory.resolve("plugins").apply { mkdirs() }, "user.jar", "shared", FixturePlugin::class.java.name, true)
            val installationRoot = directory.resolve("distribution")
            writeJar(installationRoot.resolve("plugins").apply { mkdirs() }, "bundled.jar", "shared", FixturePlugin::class.java.name, true)
            val fingerprints = PluginFingerprintRepository(directory, Json)
            fingerprints.storeFingerprints(listOf(PluginFingerprint("shared", "old-hash")))
            val manager = PluginManager(
                appDataDirectory = directory,
                settingsRepository = SettingsRepository(directory, Json, NoOpLogger),
                pluginFingerprintRepository = fingerprints,
                pluginKeyValueStore = PluginKeyValueStore(directory),
                loggerFactory = object : LoggerFactory { override fun getLogger(name: String): Logger = NoOpLogger },
                notificationBus = NotificationBus(),
                networkConfig = { NetworkConfig() },
                installationRoot = installationRoot
            )
            System.clearProperty(FIXTURE_PROBE_KEY)

            manager.loadAndProcessPlugins()

            assertNull(System.getProperty(FIXTURE_PROBE_KEY))
            assertEquals(userJar.absolutePath, manager.plugins.value.single().jarPath)
            assertEquals(false, manager.plugins.value.single().bundled)
            manager.shutdown()
        } finally {
            System.clearProperty(FIXTURE_PROBE_KEY)
            directory.deleteRecursively()
        }
    }

    private class ConstructorSentinel : Plugin<PluginSettings.None> {
        companion object { var constructions = 0 }
        init { constructions++ }
        override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> = Ok(Unit)
        override fun getServices(): List<Service> = emptyList()
        override fun getSettings(): PluginSettings.None = PluginSettings.None
    }
    @Test
    fun `directory scan retains malformed jar failure for the plugin manager`() {
        val directory = Files.createTempDirectory("qtranslate-plugin-loader").toFile()
        try {
            val jar = directory.resolve("broken-plugin.jar")
            JarOutputStream(jar.outputStream()).use { }
            val loader = PluginLoader(NoOpLogger)

            val plugins = loader.loadPluginsFromDirectory(directory)

            assertTrue(plugins.isEmpty())
            assertEquals(1, loader.loadFailures.size)
            assertEquals(jar.absolutePath, loader.loadFailures.single().jarPath)
            assertTrue(loader.loadFailures.single().message.contains("Plugin implementation"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private object NoOpLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
}

/** Harmless test provider copied into a fixture JAR to verify approved byte origin. */
private const val FIXTURE_PROBE_KEY = "qtranslate.plugin.loader.fixture.constructed"

class FixturePlugin : Plugin<PluginSettings.None> {
    init { System.setProperty("qtranslate.plugin.loader.fixture.constructed", "constructed") }
    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> = Ok(Unit)
    override fun getServices(): List<Service> = emptyList()
    override fun getSettings(): PluginSettings.None = PluginSettings.None
}
