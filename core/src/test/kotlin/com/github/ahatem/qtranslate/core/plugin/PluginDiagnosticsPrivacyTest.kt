package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.CapturingLogger
import com.github.ahatem.qtranslate.core.shared.logging.CapturingLoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * Plugin runtime diagnostics must stay metadata-only.
 *
 * Provider code controls its exception messages. Those messages are useful on the typed
 * `PluginError` a UI may choose to render, but they must never reach a production log line,
 * and neither must a stack trace handed to the logger.
 */
class PluginDiagnosticsPrivacyTest {

    private val directories = mutableListOf<File>()

    @AfterEach
    fun deleteDirectories() {
        directories.forEach { it.deleteRecursively() }
        directories.clear()
    }

    @Test
    fun `provider lifecycle exceptions never reach the log`() = runBlocking {
        val directory = tempDirectory("qtranslate-diag-lifecycle")
        val logger = CapturingLogger()
        val manager = managerFor(directory, logger)
        writePluginJar(directory.resolve("plugins"), "sentinel-enable.jar", "sentinel-enable", SentinelEnableFailurePlugin::class.java)

        manager.loadAndProcessPlugins()
        manager.shutdown()

        assertMetadataOnly(logger)
        assertTrue(
            logger.messages.any { it.contains("enabling plugin 'sentinel-enable'") },
            "the plugin id and operation must survive sanitization; log was ${logger.messages}"
        )
        assertTrue(logger.loggedThrowables.isEmpty(), "no throwable may be handed to the logger")
    }

    @Test
    fun `provider initialization and final teardown exceptions never reach the log`() = runBlocking {
        val directory = tempDirectory("qtranslate-diag-init")
        val logger = CapturingLogger()
        val manager = managerFor(directory, logger)
        writePluginJar(directory.resolve("plugins"), "sentinel-init.jar", "sentinel-init", SentinelInitializeFailurePlugin::class.java)

        manager.loadAndProcessPlugins()
        manager.shutdown()

        assertMetadataOnly(logger)
        assertTrue(
            logger.messages.any { it.contains("initializing plugin 'sentinel-init'") },
            "the plugin id and operation must survive sanitization; log was ${logger.messages}"
        )
        assertTrue(logger.loggedThrowables.isEmpty(), "no throwable may be handed to the logger")
    }

    @Test
    fun `provider constructor exceptions never reach the install log`() = runBlocking {
        val directory = tempDirectory("qtranslate-diag-install")
        val logger = CapturingLogger()
        val manager = managerFor(directory, logger)
        val source = writePluginJar(
            directory.resolve("incoming"),
            "sentinel-construct.jar",
            "sentinel-construct",
            SentinelConstructorPlugin::class.java
        )

        assertTrue(manager.installPlugin(source).isErr)
        manager.shutdown()

        assertMetadataOnly(logger)
        assertTrue(logger.loggedThrowables.isEmpty(), "no throwable may be handed to the logger")
    }

    private fun assertMetadataOnly(logger: CapturingLogger) {
        assertFalse(
            logger.messages.any { it.contains(PLUGIN_DIAGNOSTIC_SENTINEL) },
            "provider-controlled text reached a log line: ${logger.messages}"
        )
        assertFalse(
            logger.loggedThrowables.any { it.contains(PLUGIN_DIAGNOSTIC_SENTINEL) },
            "provider-controlled text reached a logged throwable"
        )
    }

    private fun tempDirectory(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { directories += it }

    private fun managerFor(directory: File, logger: CapturingLogger): PluginManager = PluginManager(
        appDataDirectory = directory,
        settingsRepository = SettingsRepository(directory, Json, FixtureLogger),
        pluginFingerprintRepository = PluginFingerprintRepository(directory, Json),
        pluginKeyValueStore = PluginKeyValueStore(directory),
        loggerFactory = CapturingLoggerFactory(logger),
        notificationBus = NotificationBus(),
        networkConfig = { NetworkConfig() },
        // The diagnostics must be absent even when a callback is abandoned at the settle bound.
        transitionSettleTimeoutMs = 1_000
    )
}
