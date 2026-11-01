package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.core.plugin.registry.PluginError
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprint
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.shared.util.Hashing
import com.github.michaelbull.result.Result
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T1/T2 trust suite: what may run before approval, and what the approval binds to.
 *
 * Loader-level tests prove assessment is inert even for a fully valid provider JAR, that
 * construction happens exactly once and only from the approved snapshot bytes, and that
 * approval does not survive a byte change. Manager-level tests walk the trust matrix —
 * first-seen, previously approved, changed, duplicate, disabled, installed, pending,
 * resolved, uninstalled — plus the regression for a paused modification surviving a restart
 * instead of degrading into trust-on-first-use.
 */
class PluginTrustTest {

    @BeforeEach
    fun resetFixtures() {
        PluginTestFixtures.Log.resetAll()
        PluginTestFixtures.Gate.reset()
    }

    @AfterEach
    fun releaseAnythingStillParked() {
        PluginTestFixtures.Gate.lastEnableHold?.release?.countDown()
        PluginTestFixtures.Gate.lastDisableHold?.release?.countDown()
        PluginTestFixtures.Gate.reset()
    }

    // -------------------------------------------------------------------------
    // T1 — assessment is inert
    // -------------------------------------------------------------------------

    @Test
    fun `assessment constructs nothing even when the provider class is inside the jar`() {
        val directory = Files.createTempDirectory("qtranslate-trust-inert").toFile()
        try {
            val jar = writePluginJar(directory, "plugin.jar", "inert", RecordingPlugin::class.java)
            val loader = PluginLoader(FixtureLogger, directory.resolve("cache"))

            val assessment = loader.assessFile(jar)

            assertNotNull(assessment, loader.loadFailures.map { it.message }.toString())
            assertEquals(0, PluginTestFixtures.Log.count("construct"), "assessment must not construct the provider")
            assessment.discard()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `directory assessment constructs nothing`() {
        val directory = Files.createTempDirectory("qtranslate-trust-inert-dir").toFile()
        try {
            writePluginJar(directory, "one.jar", "inert-one", RecordingPlugin::class.java)
            writePluginJar(directory, "two.jar", "inert-two", RecordingPlugin::class.java)

            val assessments = PluginLoader(FixtureLogger, directory.resolve("cache")).assessDirectory(directory)

            assertEquals(2, assessments.size)
            assertEquals(0, PluginTestFixtures.Log.count("construct"))
            assessments.forEach { it.discard() }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `manifest reading never constructs the provider`() {
        val directory = Files.createTempDirectory("qtranslate-trust-manifest").toFile()
        try {
            val jar = writePluginJar(directory, "plugin.jar", "manifest-only", RecordingPlugin::class.java)

            val manifest = PluginLoader(FixtureLogger, directory.resolve("cache")).getManifestFromJar(jar)

            assertEquals("manifest-only", manifest?.id)
            assertEquals(0, PluginTestFixtures.Log.count("construct"))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `assessment fails closed on a file that is not a jar`() {
        val directory = Files.createTempDirectory("qtranslate-trust-garbage").toFile()
        try {
            val broken = directory.resolve("broken.jar").apply { writeText("definitely not a zip archive") }
            val loader = PluginLoader(FixtureLogger, directory.resolve("cache"))

            assertNull(loader.assessFile(broken))

            assertEquals(1, loader.loadFailures.size)
            assertEquals(0, PluginTestFixtures.Log.count("construct"))
        } finally { directory.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // T2 — approval binds to the assessed artifact
    // -------------------------------------------------------------------------

    @Test
    fun `approved loading constructs the provider exactly once from the snapshot`() {
        val directory = Files.createTempDirectory("qtranslate-trust-once").toFile()
        try {
            val jar = writePluginJar(directory, "plugin.jar", "once", RecordingPlugin::class.java)
            val loader = PluginLoader(FixtureLogger, directory.resolve("cache"))
            val assessment = loader.assessFile(jar)!!

            val loaded = loader.loadApproved(assessment)

            assertNotNull(loaded, loader.loadFailures.map { it.message }.toString())
            assertEquals(1, PluginTestFixtures.Log.count("construct"))
            assertEquals(assessment.snapshotFile, loaded.snapshotFile)
            assertTrue((loaded.classLoader as URLClassLoader).providerDescriptorStillReachable())
            (loaded.classLoader as URLClassLoader).close()
            assessment.discard()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `approval does not survive a change to the assessed bytes`() {
        val directory = Files.createTempDirectory("qtranslate-trust-bytes").toFile()
        try {
            val jar = writePluginJar(directory, "plugin.jar", "bound", RecordingPlugin::class.java)
            val loader = PluginLoader(FixtureLogger, directory.resolve("cache"))
            val assessment = loader.assessFile(jar)!!

            assertTrue(assessment.snapshotFile.setWritable(true))
            Files.write(assessment.snapshotFile.toPath(), "tampered".toByteArray(), StandardOpenOption.APPEND)

            assertNull(loader.loadApproved(assessment))

            assertEquals(0, PluginTestFixtures.Log.count("construct"))
            assertTrue(
                loader.loadFailures.single().message.contains("snapshot changed"),
                loader.loadFailures.single().message
            )
            assessment.discard()
        } finally { directory.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // T2 — the manager trust matrix
    // -------------------------------------------------------------------------

    @Test
    fun `a first-seen external plugin is trusted on first use, constructed, and enabled`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-first-seen").toFile()
        try {
            val stores = Stores(directory)
            val jar = writePluginJar(directory.resolve("plugins"), "plugin.jar", "first-seen", RecordingPlugin::class.java)
            val manager = stores.manager()

            manager.loadAndProcessPlugins()

            assertEquals(1, PluginTestFixtures.Log.count("construct"))
            val state = manager.plugins.value.single()
            assertEquals(PluginStatus.ENABLED, state.status)
            assertEquals(jar.absolutePath, state.jarPath)
            assertFalse(state.bundled)
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `a previously approved unchanged plugin loads and enables again`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-approved").toFile()
        try {
            val stores = Stores(directory)
            val jar = writePluginJar(directory.resolve("plugins"), "plugin.jar", "approved", RecordingPlugin::class.java)
            stores.fingerprints.storeFingerprints(listOf(PluginFingerprint("approved", Hashing.sha256(jar))))
            val manager = stores.manager()

            manager.loadAndProcessPlugins()

            assertEquals(1, PluginTestFixtures.Log.count("construct"))
            assertEquals(PluginStatus.ENABLED, manager.plugins.value.single().status)
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `changed bytes for an approved plugin pause before any construction`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-changed").toFile()
        try {
            val stores = Stores(directory)
            val plugins = directory.resolve("plugins")
            val jar = writePluginJar(plugins, "plugin.jar", "changed", RecordingPlugin::class.java, "version one")
            val manager1 = stores.manager()
            manager1.loadAndProcessPlugins()
            manager1.shutdown()   // approves version one's hash

            writePluginJar(plugins, "plugin.jar", "changed", RecordingPlugin::class.java, "version two")
            val constructCountAfterFirstRun = PluginTestFixtures.Log.count("construct")
            val manager2 = stores.manager()

            manager2.loadAndProcessPlugins()

            assertEquals(constructCountAfterFirstRun, PluginTestFixtures.Log.count("construct"), "changed bytes must not be constructed")
            val state = manager2.plugins.value.single()
            assertEquals(PluginStatus.AWAITING_VERIFICATION, state.status)
            assertEquals(jar.absolutePath, state.jarPath)
            manager2.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `a paused modification is still detected after a restart`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-restart").toFile()
        try {
            val stores = Stores(directory)
            val plugins = directory.resolve("plugins")
            writePluginJar(plugins, "plugin.jar", "restart", RecordingPlugin::class.java, "version one")
            val manager1 = stores.manager()
            manager1.loadAndProcessPlugins()
            manager1.shutdown()

            writePluginJar(plugins, "plugin.jar", "restart", RecordingPlugin::class.java, "version two")
            val manager2 = stores.manager()
            manager2.loadAndProcessPlugins()
            manager2.shutdown()   // must preserve the baseline, not erase it

            val constructCount = PluginTestFixtures.Log.count("construct")
            val manager3 = stores.manager()

            manager3.loadAndProcessPlugins()

            assertEquals(constructCount, PluginTestFixtures.Log.count("construct"), "restart must not degrade a detected modification into first-run trust")
            assertEquals(PluginStatus.AWAITING_VERIFICATION, manager3.plugins.value.single().status)

            manager3.resolveAsUpdate("restart")
            assertEquals(constructCount + 1, PluginTestFixtures.Log.count("construct"))
            assertEquals(PluginStatus.ENABLED, manager3.plugins.value.single().status)
            manager3.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `resolving an update constructs the new approved bytes`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-resolve").toFile()
        try {
            val stores = Stores(directory)
            val plugins = directory.resolve("plugins")
            writePluginJar(plugins, "plugin.jar", "resolved", RecordingPlugin::class.java, "version one")
            val manager1 = stores.manager()
            manager1.loadAndProcessPlugins()
            manager1.shutdown()
            writePluginJar(plugins, "plugin.jar", "resolved", RecordingPlugin::class.java, "version two")

            val manager2 = stores.manager()
            manager2.loadAndProcessPlugins()
            assertEquals(PluginStatus.AWAITING_VERIFICATION, manager2.plugins.value.single().status)
            val before = PluginTestFixtures.Log.count("construct")

            manager2.resolveAsUpdate("resolved")

            assertEquals(before + 1, PluginTestFixtures.Log.count("construct"))
            assertEquals(PluginStatus.ENABLED, manager2.plugins.value.single().status)
            manager2.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `a clean install resolution wipes the plugin's data`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-clean").toFile()
        try {
            val stores = Stores(directory)
            val plugins = directory.resolve("plugins")
            writePluginJar(plugins, "plugin.jar", "clean", RecordingPlugin::class.java, "version one")
            val manager1 = stores.manager()
            manager1.loadAndProcessPlugins()
            manager1.shutdown()
            writePluginJar(plugins, "plugin.jar", "clean", RecordingPlugin::class.java, "version two")

            stores.keyValue.storeValue("clean", "api-key", "secret")

            val manager2 = stores.manager()
            manager2.loadAndProcessPlugins()
            assertEquals(PluginStatus.AWAITING_VERIFICATION, manager2.plugins.value.single().status)

            manager2.resolveAsCleanInstall("clean")

            assertNull(stores.keyValue.getValue("clean", "api-key"))
            assertEquals(PluginStatus.ENABLED, manager2.plugins.value.single().status)
            manager2.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `a disabled plugin is constructed but never enabled`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-disabled").toFile()
        try {
            val stores = Stores(directory)
            writePluginJar(directory.resolve("plugins"), "plugin.jar", "kept-off", RecordingPlugin::class.java)
            stores.settings.saveDisabledPluginIds(setOf("kept-off"))
            assertEquals(setOf("kept-off"), stores.settings.loadDisabledPluginIds(), "disabled state must be readable before loading")
            val manager = stores.manager()

            manager.loadAndProcessPlugins()

            // Trusted bytes are still constructed, initialized, and their services declared —
            // the disabled container exists and stays inspectable in the UI — but no activation
            // callback runs.
            assertEquals(1, PluginTestFixtures.Log.count("construct"))
            assertEquals(1, PluginTestFixtures.Log.count("initialize"))
            assertEquals(0, PluginTestFixtures.Log.count("enable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("getServices"))
            assertEquals(PluginStatus.DISABLED, manager.plugins.value.single().status)
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `duplicate ids in one folder are rejected without construction`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-dup").toFile()
        try {
            val stores = Stores(directory)
            val plugins = directory.resolve("plugins")
            writePluginJar(plugins, "a.jar", "duplicated", RecordingPlugin::class.java)
            writePluginJar(plugins, "b.jar", "duplicated", RecordingPlugin::class.java)
            val manager = stores.manager()

            manager.loadAndProcessPlugins()

            assertEquals(0, PluginTestFixtures.Log.count("construct"))
            val duplicateFailures = manager.plugins.value.map { it.lastError }.filterIsInstance<PluginError.DuplicateId>()
            assertEquals(2, duplicateFailures.size)
            assertTrue(manager.plugins.value.none { it.status == PluginStatus.ENABLED })
            assertTrue(File(directory, "plugin-snapshots").listFiles().orEmpty().isEmpty())
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `a bundled plugin loads with its origin reported`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-bundled").toFile()
        try {
            val stores = Stores(directory)
            val installationRoot = directory.resolve("distribution")
            val bundledJar = writePluginJar(
                installationRoot.resolve("plugins"), "bundled.jar", "bundled-plugin", RecordingPlugin::class.java
            )
            val manager = stores.manager(installationRoot)

            manager.loadAndProcessPlugins()

            assertEquals(1, PluginTestFixtures.Log.count("construct"))
            val state = manager.plugins.value.single()
            assertEquals(PluginStatus.ENABLED, state.status)
            assertTrue(state.bundled)
            assertEquals(bundledJar.absolutePath, state.jarPath)
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `installation constructs from the approved bytes and copies them verbatim`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-install").toFile()
        try {
            val stores = Stores(directory)
            val source = writePluginJar(directory.resolve("incoming"), "plugin.jar", "installed", RecordingPlugin::class.java)
            val manager = stores.manager()

            val result: Result<Unit, String> = manager.installPlugin(source)

            assertTrue(result.isOk, result.toString())
            assertEquals(1, PluginTestFixtures.Log.count("construct"))
            val state = manager.plugins.value.single()
            assertEquals(PluginStatus.ENABLED, state.status)
            assertEquals(File(directory, "plugins/plugin.jar").absolutePath, state.jarPath)
            assertFalse(state.bundled)
            // The installed JAR is a byte-for-byte copy of what was assessed.
            assertTrue(source.readBytes().contentEquals(File(directory, "plugins/plugin.jar").readBytes()))
            // The live instance's loader is open and serving the plugin's own entries.
            assertTrue(PluginTestFixtures.Log.constructedLoaders.single().providerDescriptorStillReachable())
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `installation refuses a duplicate plugin id`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-install-dup").toFile()
        try {
            val stores = Stores(directory)
            val incoming = directory.resolve("incoming")
            val first = writePluginJar(incoming, "first.jar", "installed-twice", RecordingPlugin::class.java)
            val second = writePluginJar(incoming, "second.jar", "installed-twice", RecordingPlugin::class.java, "other bytes")
            val manager = stores.manager()

            assertTrue(manager.installPlugin(first).isOk)
            assertTrue(manager.installPlugin(second).isErr)

            assertEquals(1, PluginTestFixtures.Log.count("construct"))
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `installation refuses a filename collision`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-install-name").toFile()
        try {
            val stores = Stores(directory)
            val first = writePluginJar(directory.resolve("incoming-a"), "plugin.jar", "first-id", RecordingPlugin::class.java)
            val second = writePluginJar(directory.resolve("incoming-b"), "plugin.jar", "second-id", ExplodingPlugin::class.java)
            val manager = stores.manager()

            assertTrue(manager.installPlugin(first).isOk)
            assertTrue(manager.installPlugin(second).isErr)

            assertEquals(1, PluginTestFixtures.Log.count("construct"))
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `installation fails closed when the provider cannot be constructed`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-install-explode").toFile()
        try {
            val stores = Stores(directory)
            val source = writePluginJar(directory.resolve("incoming"), "plugin.jar", "explodes", ExplodingPlugin::class.java)
            val manager = stores.manager()

            val result: Result<Unit, String> = manager.installPlugin(source)

            assertTrue(result.isErr)
            // The destination copy and the assessment snapshot are released: nothing is left
            // behind to fail discovery on every later launch.
            assertFalse(File(directory, "plugins/plugin.jar").exists())
            assertTrue(File(directory, "plugin-snapshots").listFiles().orEmpty().isEmpty())
            assertTrue(manager.plugins.value.isEmpty())
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `uninstall removes the instance and releases its loader and snapshot`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-uninstall").toFile()
        try {
            val stores = Stores(directory)
            writePluginJar(directory.resolve("plugins"), "plugin.jar", "uninstalled", RecordingPlugin::class.java)
            val manager = stores.manager()
            manager.loadAndProcessPlugins()
            val loaders = PluginTestFixtures.Log.constructedLoaders.toList()

            manager.uninstallPlugin("uninstalled")

            assertTrue(manager.plugins.value.isEmpty())
            loaders.forEach { assertFalse(it.providerDescriptorStillReachable(), "uninstall must close the plugin class loader") }
            assertTrue(File(directory, "plugin-snapshots").listFiles().orEmpty().isEmpty())
            assertFalse(File(directory, "plugins/plugin.jar").exists())
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `uninstalling a paused modification discards the assessment without constructing it`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-uninstall-pending").toFile()
        try {
            val stores = Stores(directory)
            val plugins = directory.resolve("plugins")
            writePluginJar(plugins, "plugin.jar", "paused", RecordingPlugin::class.java, "version one")
            val manager1 = stores.manager()
            manager1.loadAndProcessPlugins()
            manager1.shutdown()
            writePluginJar(plugins, "plugin.jar", "paused", RecordingPlugin::class.java, "version two")

            val manager2 = stores.manager()
            manager2.loadAndProcessPlugins()
            assertEquals(PluginStatus.AWAITING_VERIFICATION, manager2.plugins.value.single().status)
            val constructCount = PluginTestFixtures.Log.count("construct")

            manager2.uninstallPlugin("paused")

            assertEquals(constructCount, PluginTestFixtures.Log.count("construct"))
            assertTrue(manager2.plugins.value.isEmpty())
            assertTrue(File(directory, "plugin-snapshots").listFiles().orEmpty().isEmpty())
            assertFalse(File(directory, "plugins/plugin.jar").exists())
            manager2.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `a failed resolution keeps the pending row and the older baseline`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-resolve-fail").toFile()
        try {
            val stores = Stores(directory)
            val plugins = directory.resolve("plugins")
            writePluginJar(plugins, "plugin.jar", "unloadable", RecordingPlugin::class.java, "version one")
            val manager1 = stores.manager()
            manager1.loadAndProcessPlugins()
            manager1.shutdown()
            writePluginJar(plugins, "plugin.jar", "unloadable", RecordingPlugin::class.java, "version two")

            val manager2 = stores.manager()
            manager2.loadAndProcessPlugins()
            assertEquals(PluginStatus.AWAITING_VERIFICATION, manager2.plugins.value.single().status)
            val constructCount = PluginTestFixtures.Log.count("construct")

            // Tamper with the pending assessment's snapshot: the approved bytes no longer match.
            val snapshot = File(directory, "plugin-snapshots").listFiles().orEmpty().single()
            assertTrue(snapshot.setWritable(true))
            Files.write(snapshot.toPath(), "tampered".toByteArray(), StandardOpenOption.APPEND)

            manager2.resolveAsUpdate("unloadable")

            assertEquals(constructCount, PluginTestFixtures.Log.count("construct"))
            assertEquals(PluginStatus.AWAITING_VERIFICATION, manager2.plugins.value.single().status, "the pending row must survive a failed resolution")
            manager2.shutdown()

            // The older baseline was preserved, so the modification is re-detected on restart
            // with a fresh, loadable snapshot.
            val manager3 = stores.manager()
            manager3.loadAndProcessPlugins()
            assertEquals(PluginStatus.AWAITING_VERIFICATION, manager3.plugins.value.single().status)
            manager3.resolveAsUpdate("unloadable")
            assertEquals(constructCount + 1, PluginTestFixtures.Log.count("construct"))
            assertEquals(PluginStatus.ENABLED, manager3.plugins.value.single().status)
            manager3.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `stale snapshots from a crashed run are swept at startup`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-sweep").toFile()
        try {
            val stores = Stores(directory)
            val snapshots = directory.resolve("plugin-snapshots").apply { mkdirs() }
            val stale = File(snapshots, "plugin-stale.jar").apply { writeText("left over") }
            writePluginJar(directory.resolve("plugins"), "plugin.jar", "swept", RecordingPlugin::class.java)
            val manager = stores.manager()

            manager.loadAndProcessPlugins()

            assertFalse(stale.exists(), "the crashed run's snapshot must be removed")
            assertEquals(PluginStatus.ENABLED, manager.plugins.value.single().status)
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `installation refuses a symlinked destination`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-trust-symlink").toFile()
        try {
            val stores = Stores(directory)
            val plugins = directory.resolve("plugins").apply { mkdirs() }
            val target = File(directory, "elsewhere.jar").apply { writeText("decoy") }
            val link = plugins.resolve("plugin.jar").toPath()
            if (runCatching { Files.createSymbolicLink(link, target.toPath()) }.isFailure) return@runBlocking

            val source = writePluginJar(directory.resolve("incoming"), "plugin.jar", "via-symlink", RecordingPlugin::class.java)
            val manager = stores.manager()

            assertTrue(manager.installPlugin(source).isErr)
            assertTrue(Files.isSymbolicLink(link), "the link must be left untouched")
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    /**
     * One repository instance per file per test. androidx DataStore refuses a second active
     * instance for the same file, so every manager in a restart sequence must share these.
     */
    private class Stores(private val directory: File) {
        val settings = SettingsRepository(directory, Json, FixtureLogger)
        val fingerprints = PluginFingerprintRepository(directory, Json)
        val keyValue = PluginKeyValueStore(directory)

        fun manager(installationRoot: File? = null): PluginManager = PluginManager(
            appDataDirectory = directory,
            settingsRepository = settings,
            pluginFingerprintRepository = fingerprints,
            pluginKeyValueStore = keyValue,
            loggerFactory = FixtureLoggerFactory,
            notificationBus = NotificationBus(),
            networkConfig = { NetworkConfig() },
            installationRoot = installationRoot
        )
    }

    private fun writePluginJar(
        directory: File,
        name: String,
        id: String,
        providerClass: Class<*>,
        description: String = "fixture plugin"
    ): File = com.github.ahatem.qtranslate.core.plugin.writePluginJar(directory, name, id, providerClass, description)
}
