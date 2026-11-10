package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The uninstall/disable boundary for a plugin's persisted data.
 *
 * Uninstalling a plugin wipes both its ordinary settings and its secret store: a removed
 * plugin's credentials must not sit on disk, and a later same-id install must not inherit
 * them. Disabling a plugin wipes neither — the data is only purged on uninstall.
 */
class PluginUninstallPurgeTest {

    private val id = "purge-test"

    private fun manager(directory: File, keyValue: PluginKeyValueStore): PluginManager {
        val settings = SettingsRepository(directory, Json, FixtureLogger)
        val fingerprints = PluginFingerprintRepository(directory, Json)
        return PluginManager(
            appDataDirectory = directory,
            settingsRepository = settings,
            pluginFingerprintRepository = fingerprints,
            pluginKeyValueStore = keyValue,
            loggerFactory = FixtureLoggerFactory,
            notificationBus = NotificationBus(),
            networkConfig = { NetworkConfig() }
        )
    }

    @Test
    fun `uninstall purges settings and secrets while disable preserves them`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-plugin-purge").toFile()
        try {
            writePluginJar(directory.resolve("plugins"), "plugin.jar", id, RecordingPlugin::class.java)
            val keyValue = PluginKeyValueStore(directory)
            val manager = manager(directory, keyValue)
            manager.loadAndProcessPlugins()

            keyValue.storeValue(id, "label", "a setting")
            keyValue.storeValue("$id.secrets", "default/apiKey", "a secret")

            manager.disablePlugin(id)
            assertEquals("a setting", keyValue.getValue(id, "label"), "disable must not purge settings")
            assertEquals("a secret", keyValue.getValue("$id.secrets", "default/apiKey"), "disable must not purge secrets")

            manager.uninstallPlugin(id)
            assertNull(keyValue.getValue(id, "label"), "uninstall must purge settings")
            assertNull(keyValue.getValue("$id.secrets", "default/apiKey"), "uninstall must purge secrets")
        } finally {
            directory.deleteRecursively()
        }
    }
}
