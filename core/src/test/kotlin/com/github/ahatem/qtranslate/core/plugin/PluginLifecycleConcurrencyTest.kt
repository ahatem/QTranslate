package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginLifecycleHandler
import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginTransitionOwner
import com.github.ahatem.qtranslate.core.plugin.registry.PluginContainer
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.text.PluginTextResolver
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.shared.util.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * T3 — deterministic lifecycle interleavings.
 *
 * Every test drives the real stack (manager, installer, lifecycle handler, real plugin JARs)
 * and holds transitions mid-flight with latches inside the plugin's own callbacks, so racing
 * transitions are forced to overlap in a controlled way. No sleeps: ordering is proven by
 * handshakes, and each assertion is structural rather than timing-based.
 */
class PluginLifecycleConcurrencyTest {

    private companion object {
        const val ID = "lifecycle"
    }

    @BeforeEach
    fun resetFixtures() {
        PluginTestFixtures.Log.resetAll()
        PluginTestFixtures.Gate.reset()
    }

    @AfterEach
    fun releaseAnythingStillParked() {
        // A failed assertion must not park a NonCancellable transition forever: release any
        // latch a half-finished test left behind so the suite can proceed and the JVM can exit.
        PluginTestFixtures.Gate.lastEnableHold?.release?.countDown()
        PluginTestFixtures.Gate.lastDisableHold?.release?.countDown()
        PluginTestFixtures.Gate.reset()
    }

    // -------------------------------------------------------------------------
    // Concurrent activation
    // -------------------------------------------------------------------------

    @Test
    fun `overlapping activation attempts produce exactly one live activation`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-double-enable").toFile()
        try {
            val manager = loadedManager(directory)
            manager.disablePlugin(ID)

            val enableHold = PluginTestFixtures.Gate.holdEnable()
            val jobs = List(3) { launch(Dispatchers.Default) { manager.enablePlugin(ID) } }

            enableHold.entered.await()   // one activation is in flight
            try {
                assertEquals(1, PluginTestFixtures.Log.count("enable-enter"), "only one activation may run")
            } finally {
                enableHold.release.countDown()
            }
            jobs.joinAll()

            assertEquals(1, PluginTestFixtures.Log.count("enable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("enable-exit"))
            assertEquals(1, PluginTestFixtures.Log.count("getServices"))
            assertEquals(PluginStatus.ENABLED, manager.plugins.value.single().status)
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `a disable racing an in-flight activation settles to disabled with one callback set`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-enable-disable").toFile()
        try {
            val stores = Stores(directory)
            val manager = loadedManager(directory, stores)
            manager.disablePlugin(ID)
            PluginTestFixtures.Log.reset()

            val enableHold = PluginTestFixtures.Gate.holdEnable()
            val enableJob = launch(Dispatchers.Default) { manager.enablePlugin(ID) }
            enableHold.entered.await()
            val disableJob = launch(Dispatchers.Default) { manager.disablePlugin(ID) }

            // The disable cannot act while the activation owns the plugin: it waits, then runs.
            try {
                assertEquals(0, PluginTestFixtures.Log.count("disable-enter"))
            } finally {
                enableHold.release.countDown()
            }
            enableJob.join()
            disableJob.join()

            assertEquals(PluginStatus.DISABLED, manager.plugins.value.single().status, "disable must win the final state")
            assertEquals(1, PluginTestFixtures.Log.count("enable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("enable-exit"))
            assertEquals(1, PluginTestFixtures.Log.count("disable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("disable-exit"))
            assertTrue(ID in stores.settings.loadDisabledPluginIds())
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `repeated activation and deactivation are idempotent`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-idempotent").toFile()
        try {
            val manager = loadedManager(directory)

            manager.enablePlugin(ID)   // already enabled: no-op
            assertEquals(0, PluginTestFixtures.Log.count("enable-enter"))

            manager.disablePlugin(ID)
            manager.disablePlugin(ID)  // already disabled: no-op
            assertEquals(1, PluginTestFixtures.Log.count("disable-enter"))

            manager.enablePlugin(ID)
            manager.enablePlugin(ID)   // already enabled: no-op
            assertEquals(1, PluginTestFixtures.Log.count("enable-enter"))

            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // Transitions of different plugins stay independent
    // -------------------------------------------------------------------------

    @Test
    fun `transitions of unrelated plugins are not serialized against each other`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-independent").toFile()
        try {
            val plugins = directory.resolve("plugins")
            writePluginJar(plugins, "alpha.jar", "alpha", RecordingPlugin::class.java)
            writePluginJar(plugins, "beta.jar", "beta", RecordingPlugin::class.java)
            val manager = Stores(directory).manager()
            manager.loadAndProcessPlugins()
            manager.disablePlugin("alpha")
            manager.disablePlugin("beta")
            PluginTestFixtures.Log.reset()

            val enableHold = PluginTestFixtures.Gate.holdEnable()   // gates the next enable: alpha's
            val alphaJob = launch(Dispatchers.Default) { manager.enablePlugin("alpha") }
            enableHold.entered.await()

            // Beta's activation must complete while alpha's is still held open.
            val betaJob = launch(Dispatchers.Default) { manager.enablePlugin("beta") }
            betaJob.join()

            try {
                assertEquals(1, PluginTestFixtures.Log.count("enable-exit"), "beta must not wait for alpha")
                assertEquals(PluginStatus.ENABLED, manager.plugins.value.single { it.id == "beta" }.status)
            } finally {
                enableHold.release.countDown()
            }
            alphaJob.join()
            assertEquals(PluginStatus.ENABLED, manager.plugins.value.single { it.id == "alpha" }.status)
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // Uninstall ownership
    // -------------------------------------------------------------------------

    @Test
    fun `uninstall owns an in-flight activation — the activated instance is torn down exactly once`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-enable-uninstall").toFile()
        try {
            val manager = loadedManager(directory)
            manager.disablePlugin(ID)
            PluginTestFixtures.Log.reset()

            val enableHold = PluginTestFixtures.Gate.holdEnable()
            val enableJob = launch(Dispatchers.Default) { manager.enablePlugin(ID) }
            enableHold.entered.await()
            val uninstallJob = launch(Dispatchers.Default) { manager.uninstallPlugin(ID) }

            try {
                assertEquals(0, PluginTestFixtures.Log.count("disable-enter"), "uninstall's teardown waits for the activation")
            } finally {
                enableHold.release.countDown()
            }
            enableJob.join()
            uninstallJob.join()

            assertTrue(manager.plugins.value.isEmpty(), "no executable instance may remain registered")
            assertEquals(1, PluginTestFixtures.Log.count("enable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("disable-enter"), "onDisable exactly once")
            assertEquals(1, PluginTestFixtures.Log.count("plugin-shutdown"), "final cleanup exactly once")
            PluginTestFixtures.Log.constructedLoaders.forEach { assertFalse(it.providerDescriptorStillReachable(), "loader must be closed") }
            assertTrue(File(directory, "plugin-snapshots").listFiles().orEmpty().isEmpty(), "snapshot must be released")
            assertFalse(File(directory, "plugins/plugin.jar").exists())
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `uninstall racing a disable fires onDisable exactly once`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-disable-uninstall").toFile()
        try {
            val manager = loadedManager(directory)

            val disableHold = PluginTestFixtures.Gate.holdDisable()
            val disableJob = launch(Dispatchers.Default) { manager.disablePlugin(ID) }
            disableHold.entered.await()
            val uninstallJob = launch(Dispatchers.Default) { manager.uninstallPlugin(ID) }

            try {
                assertEquals(0, PluginTestFixtures.Log.count("plugin-shutdown"), "uninstall waits for the disable")
            } finally {
                disableHold.release.countDown()
            }
            disableJob.join()
            uninstallJob.join()

            assertTrue(manager.plugins.value.isEmpty())
            assertEquals(1, PluginTestFixtures.Log.count("disable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("disable-exit"))
            assertEquals(1, PluginTestFixtures.Log.count("plugin-shutdown"))
            PluginTestFixtures.Log.constructedLoaders.forEach { assertFalse(it.providerDescriptorStillReachable()) }
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // Reload/change: resolution supersedes the paused assessment, never a live instance
    // -------------------------------------------------------------------------

    @Test
    fun `two overlapping update resolutions construct exactly one instance`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-double-resolve").toFile()
        try {
            val manager = pausedManager(directory)
            val before = PluginTestFixtures.Log.count("construct")

            supervisorScope {
                val first = async(Dispatchers.Default) { manager.resolveAsUpdate(ID) }
                val second = async(Dispatchers.Default) { manager.resolveAsUpdate(ID) }
                listOf(first, second).joinAll()
            }

            assertEquals(before + 1, PluginTestFixtures.Log.count("construct"), "exactly one new instance")
            assertEquals(1, PluginTestFixtures.Log.count("enable-enter"))
            assertEquals(PluginStatus.ENABLED, manager.plugins.value.single().status)
            // The first run's loader was closed at its shutdown; the winner's stays open.
            val loaders = PluginTestFixtures.Log.constructedLoaders
            assertFalse(loaders[0].providerDescriptorStillReachable(), "superseded run's loader must be closed")
            assertTrue(loaders[1].providerDescriptorStillReachable(), "live instance's loader must be open")
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `an install racing an uninstall finalizes the old instance before registering the new one`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-install-uninstall").toFile()
        try {
            val manager = loadedManager(directory)

            val disableHold = PluginTestFixtures.Gate.holdDisable()
            val uninstallJob = launch(Dispatchers.Default) { manager.uninstallPlugin(ID) }
            disableHold.entered.await()

            // Same id, same destination filename: only serialization makes this deterministic.
            val replacement = writePluginJar(directory.resolve("incoming"), "plugin.jar", ID, RecordingPlugin::class.java, "replacement")
            val installJob = launch(Dispatchers.Default) { manager.installPlugin(replacement) }

            try {
                assertEquals(0, PluginTestFixtures.Log.count("plugin-shutdown"), "install waits behind the uninstall")
            } finally {
                disableHold.release.countDown()
            }
            uninstallJob.join()
            installJob.join()

            assertEquals(1, PluginTestFixtures.Log.count("disable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("plugin-shutdown"))
            assertEquals(1, PluginTestFixtures.Log.count("construct"), "the replacement instance")
            assertEquals(1, PluginTestFixtures.Log.count("enable-enter"))

            val state = manager.plugins.value.single()
            assertEquals(PluginStatus.ENABLED, state.status)
            assertEquals(File(directory, "plugins/plugin.jar").absolutePath, state.jarPath)

            val loaders = PluginTestFixtures.Log.constructedLoaders
            assertFalse(loaders[0].providerDescriptorStillReachable(), "old instance's loader must be closed")
            assertTrue(loaders[1].providerDescriptorStillReachable(), "new instance's loader must be open")
            assertTrue(replacement.readBytes().contentEquals(File(directory, "plugins/plugin.jar").readBytes()))
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // Shutdown ownership
    // -------------------------------------------------------------------------

    @Test
    fun `shutdown settles an accepted activation before finalizing it`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-shutdown-settle").toFile()
        try {
            val stores = Stores(directory)
            val manager = loadedManager(directory, stores)
            val jar = File(directory, "plugins/plugin.jar")
            manager.disablePlugin(ID)
            PluginTestFixtures.Log.reset()

            val enableHold = PluginTestFixtures.Gate.holdEnable()
            val enableJob = launch(Dispatchers.Default) { manager.enablePlugin(ID) }
            enableHold.entered.await()
            val shutdownJob = launch(Dispatchers.Default) { manager.shutdown() }

            // Shutdown must not finalize — or abandon — the in-flight activation.
            try {
                assertEquals(0, PluginTestFixtures.Log.count("disable-enter"))
                assertEquals(0, PluginTestFixtures.Log.count("plugin-shutdown"))
            } finally {
                enableHold.release.countDown()
            }
            enableJob.join()
            shutdownJob.join()

            // The settled activation completed, then was finalized exactly once.
            assertEquals(1, PluginTestFixtures.Log.count("enable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("enable-exit"))
            assertEquals(1, PluginTestFixtures.Log.count("disable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("plugin-shutdown"))
            assertTrue(manager.plugins.value.isEmpty())
            PluginTestFixtures.Log.constructedLoaders.forEach { assertFalse(it.providerDescriptorStillReachable()) }
            assertTrue(File(directory, "plugin-snapshots").listFiles().orEmpty().isEmpty())

            // Fingerprints were persisted from the settled state, not a mid-transition one.
            assertEquals(
                mapOf(ID to Hashing.sha256(jar)),
                stores.fingerprints.loadFingerprints()
            )
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `after shutdown, new transitions are rejected and nothing is constructed or leaked`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-shutdown-reject").toFile()
        try {
            val stores = Stores(directory)
            val manager = loadedManager(directory, stores)
            manager.shutdown()
            val fingerprintsAfterFirstShutdown = stores.fingerprints.loadFingerprints()
            PluginTestFixtures.Log.reset()

            manager.shutdown()   // a second shutdown finalizes nothing twice
            assertEquals(0, PluginTestFixtures.Log.count("plugin-shutdown"))
            assertEquals(fingerprintsAfterFirstShutdown, stores.fingerprints.loadFingerprints(), "a duplicate shutdown must not wipe approved baselines")

            manager.enablePlugin(ID)
            manager.disablePlugin(ID)
            manager.uninstallPlugin(ID)
            assertEquals(0, PluginTestFixtures.Log.count("enable-enter"))
            assertEquals(0, PluginTestFixtures.Log.count("disable-enter"))
            assertTrue(manager.plugins.value.isEmpty())

            val late = writePluginJar(directory.resolve("incoming"), "late.jar", "late-comer", RecordingPlugin::class.java)
            assertTrue(manager.installPlugin(late).isErr, "install must be refused after shutdown")
            assertEquals(0, PluginTestFixtures.Log.count("construct"))
            // The refused install's inert snapshot was discarded, not leaked.
            assertTrue(File(directory, "plugin-snapshots").listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `two overlapping installs of one id register exactly one instance`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-double-install").toFile()
        try {
            val stores = Stores(directory)
            val manager = stores.manager()
            val first = writePluginJar(directory.resolve("a"), "one.jar", ID, RecordingPlugin::class.java)
            val second = writePluginJar(directory.resolve("b"), "two.jar", ID, RecordingPlugin::class.java, "other bytes")

            val results = supervisorScope {
                val a = async(Dispatchers.Default) { manager.installPlugin(first) }
                val b = async(Dispatchers.Default) { manager.installPlugin(second) }
                listOf(a, b).awaitAll()
            }

            assertEquals(1, results.count { it.isOk })
            assertEquals(1, results.count { it.isErr })
            assertEquals(1, PluginTestFixtures.Log.count("construct"))
            assertEquals(1, manager.plugins.value.count())
            manager.shutdown()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `an install racing shutdown settles then is finalized exactly once`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-install-shutdown").toFile()
        try {
            val stores = Stores(directory)
            val manager = stores.manager()
            val source = writePluginJar(directory.resolve("incoming"), "plugin.jar", ID, RecordingPlugin::class.java)

            val enableHold = PluginTestFixtures.Gate.holdEnable()
            val installJob = launch(Dispatchers.Default) { manager.installPlugin(source) }
            enableHold.entered.await()   // constructed and initialized; activation in flight
            val shutdownJob = launch(Dispatchers.Default) { manager.shutdown() }

            try {
                assertEquals(0, PluginTestFixtures.Log.count("disable-enter"))
                assertEquals(0, PluginTestFixtures.Log.count("plugin-shutdown"))
            } finally {
                enableHold.release.countDown()
            }
            installJob.join()
            shutdownJob.join()

            assertTrue(manager.plugins.value.isEmpty())
            assertEquals(1, PluginTestFixtures.Log.count("construct"))
            assertEquals(1, PluginTestFixtures.Log.count("disable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("plugin-shutdown"))
            PluginTestFixtures.Log.constructedLoaders.forEach { assertFalse(it.providerDescriptorStillReachable()) }
            assertTrue(File(directory, "plugin-snapshots").listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `an update resolution racing shutdown settles then is finalized exactly once`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-resolve-shutdown").toFile()
        try {
            val manager = pausedManager(directory)
            val constructBefore = PluginTestFixtures.Log.count("construct")

            val enableHold = PluginTestFixtures.Gate.holdEnable()
            val resolveJob = launch(Dispatchers.Default) { manager.resolveAsUpdate(ID) }
            enableHold.entered.await()
            val shutdownJob = launch(Dispatchers.Default) { manager.shutdown() }

            try {
                assertEquals(0, PluginTestFixtures.Log.count("disable-enter"))
                assertEquals(0, PluginTestFixtures.Log.count("plugin-shutdown"))
            } finally {
                enableHold.release.countDown()
            }
            resolveJob.join()
            shutdownJob.join()

            assertTrue(manager.plugins.value.isEmpty())
            assertEquals(constructBefore + 1, PluginTestFixtures.Log.count("construct"))
            assertEquals(1, PluginTestFixtures.Log.count("disable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("plugin-shutdown"))
            PluginTestFixtures.Log.constructedLoaders.forEach { assertFalse(it.providerDescriptorStillReachable()) }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `an uninstall racing shutdown tears down exactly once`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-uninstall-shutdown").toFile()
        try {
            val manager = loadedManager(directory)

            val disableHold = PluginTestFixtures.Gate.holdDisable()
            val uninstallJob = launch(Dispatchers.Default) { manager.uninstallPlugin(ID) }
            disableHold.entered.await()
            val shutdownJob = launch(Dispatchers.Default) { manager.shutdown() }

            try {
                assertEquals(0, PluginTestFixtures.Log.count("plugin-shutdown"))
            } finally {
                disableHold.release.countDown()
            }
            uninstallJob.join()
            shutdownJob.join()

            assertTrue(manager.plugins.value.isEmpty())
            assertEquals(1, PluginTestFixtures.Log.count("disable-enter"))
            assertEquals(1, PluginTestFixtures.Log.count("plugin-shutdown"))
            PluginTestFixtures.Log.constructedLoaders.forEach { assertFalse(it.providerDescriptorStillReachable()) }
            assertTrue(File(directory, "plugin-snapshots").listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `finalizing the same container twice runs the final callbacks once`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-double-finalize").toFile()
        try {
            val jar = writePluginJar(directory, "plugin.jar", ID, RecordingPlugin::class.java)
            val loader = PluginLoader(FixtureLogger, directory.resolve("cache"))
            val assessment = loader.assessFile(jar)!!
            val result = loader.loadApproved(assessment)!!
            val handler = PluginLifecycleHandler(
                appDataDirectory = directory,
                pluginKeyValueStore = PluginKeyValueStore(directory),
                notificationBus = NotificationBus(),
                textResolver = PluginTextResolver.Fallback,
                loggerFactory = FixtureLoggerFactory
            )
            val container = PluginContainer(
                plugin = result.plugin,
                manifest = result.manifest,
                context = handler.createContext(result),
                jarFile = result.jarFile,
                jarHash = result.jarHash,
                classLoader = result.classLoader,
                snapshotFile = result.snapshotFile,
                status = PluginStatus.ENABLED
            )
            PluginTestFixtures.Log.reset()

            handler.shutdown(container)
            handler.shutdown(container)

            assertEquals(1, PluginTestFixtures.Log.count("disable-enter"), "onDisable exactly once")
            assertEquals(1, PluginTestFixtures.Log.count("plugin-shutdown"), "final cleanup exactly once")
            assertFalse((result.classLoader as URLClassLoader).providerDescriptorStillReachable())
            assertFalse(result.snapshotFile!!.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `a transition outliving the shutdown settle is never overlapped by teardown`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-lc-straggler").toFile()
        try {
            val stores = Stores(directory)
            writePluginJar(directory.resolve("plugins"), "plugin.jar", ID, RecordingPlugin::class.java)
            val manager = stores.manager(settleTimeoutMs = 300)
            manager.loadAndProcessPlugins()
            manager.disablePlugin(ID)
            PluginTestFixtures.Log.reset()

            val enableHold = PluginTestFixtures.Gate.holdEnable()
            val enableJob = launch(Dispatchers.Default) { manager.enablePlugin(ID) }
            enableHold.entered.await()
            val shutdownJob = launch(Dispatchers.Default) { manager.shutdown() }

            // Shutdown gives up on the parked transition after the bound. While the accepted
            // callback is still running, no final lifecycle callback may be entered: exactly
            // once finalization does not make overlapping callbacks safe.
            shutdownJob.join()
            try {
                assertTrue(manager.plugins.value.isEmpty(), "shutdown must not wait forever for a stuck callback")
                assertEquals(0, PluginTestFixtures.Log.count("disable-enter"), "no onDisable overlap")
                assertEquals(0, PluginTestFixtures.Log.count("plugin-shutdown"), "no shutdown overlap")
            } finally {
                enableHold.release.countDown()
            }
            enableJob.join()

            // The straggler's own completion finalizes it, strictly after its callback returned.
            assertEquals(
                listOf(
                    "enable-enter", "enable-exit", "getServices",
                    "disable-enter", "disable-exit", "plugin-shutdown"
                ),
                PluginTestFixtures.Log.snapshot(),
                "every later callback must start after the parked callback finished"
            )
            assertEquals(1, PluginTestFixtures.Log.count("plugin-shutdown"), "finalization is claimed atomically")
            assertTrue(manager.plugins.value.isEmpty())
            PluginTestFixtures.Log.constructedLoaders.forEach { assertFalse(it.providerDescriptorStillReachable()) }
            assertTrue(File(directory, "plugin-snapshots").listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    /** A manager whose plugin loaded and enabled at startup; log and gates reset for the test. */
    private suspend fun loadedManager(directory: File, stores: Stores = Stores(directory)): PluginManager {
        writePluginJar(directory.resolve("plugins"), "plugin.jar", ID, RecordingPlugin::class.java)
        val manager = stores.manager()
        manager.loadAndProcessPlugins()
        PluginTestFixtures.Log.reset()
        PluginTestFixtures.Gate.reset()
        return manager
    }

    /** A manager holding a changed-JAR plugin in the pending state. */
    private suspend fun pausedManager(directory: File, stores: Stores = Stores(directory)): PluginManager {
        val plugins = directory.resolve("plugins")
        writePluginJar(plugins, "plugin.jar", ID, RecordingPlugin::class.java, "version one")
        val first = stores.manager()
        first.loadAndProcessPlugins()
        first.shutdown()
        writePluginJar(plugins, "plugin.jar", ID, RecordingPlugin::class.java, "version two")

        val manager = stores.manager()
        manager.loadAndProcessPlugins()
        assertEquals(PluginStatus.AWAITING_VERIFICATION, manager.plugins.value.single().status)
        PluginTestFixtures.Log.reset()
        PluginTestFixtures.Gate.reset()
        return manager
    }

    /** One repository instance per file per test: DataStore refuses a second active instance. */
    private class Stores(private val directory: File) {
        val settings = SettingsRepository(directory, Json, FixtureLogger)
        val fingerprints = PluginFingerprintRepository(directory, Json)
        val keyValue = PluginKeyValueStore(directory)

        fun manager(
            installationRoot: File? = null,
            settleTimeoutMs: Long = PluginTransitionOwner.DEFAULT_SETTLE_TIMEOUT_MS
        ): PluginManager = PluginManager(
            appDataDirectory = directory,
            settingsRepository = settings,
            pluginFingerprintRepository = fingerprints,
            pluginKeyValueStore = keyValue,
            loggerFactory = FixtureLoggerFactory,
            notificationBus = NotificationBus(),
            networkConfig = { NetworkConfig() },
            installationRoot = installationRoot,
            transitionSettleTimeoutMs = settleTimeoutMs
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
