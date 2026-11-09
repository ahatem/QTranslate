package com.github.ahatem.qtranslate.core.plugin.settings

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.core.plugin.FixtureLoggerFactory
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.storage.ScopedSecretStore
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-side secret handling for `SettingType.PASSWORD` settings fields.
 *
 * Password values must be persisted to the plugin's secret store only — never to the normal
 * plugin settings store — and a blank password edit must keep the stored secret. A plugin
 * failure (rejection or thrown exception) must persist nothing, and cancellation must
 * propagate instead of being converted into a typed error.
 */
class PluginSettingsManagerTest {

    private val pluginId = "settings-secret-test"

    private class FakeSettings : PluginSettings.Configurable() {
        @field:Setting(label = "API Key", type = SettingType.PASSWORD, order = 1, isRequired = true)
        var apiKey: String = ""

        @field:Setting(label = "Label", type = SettingType.TEXT, order = 2)
        var label: String = ""
    }

    private class FakePlugin : Plugin<FakeSettings> {
        var current: FakeSettings = FakeSettings()
        var received: FakeSettings? = null
        var onChanged: suspend (FakeSettings) -> Result<Unit, ServiceError> = { Ok(Unit) }

        override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> = Ok(Unit)
        override fun getServices(): List<Service> = emptyList()
        override fun getSettings(): FakeSettings = current
        override suspend fun onSettingsChanged(settings: FakeSettings): Result<Unit, ServiceError> {
            received = settings
            return onChanged(settings)
        }
    }

    private fun managerFor(store: PluginKeyValueStore) = PluginSettingsManager(store, FixtureLoggerFactory)

    private fun secretsFor(store: PluginKeyValueStore) =
        ScopedSecretStore(pluginId, ServiceId.DEFAULT_INSTANCE, store)

    @Test
    fun `password values are persisted to the secret store only`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-settings-secret-save").toFile()
        try {
            val store = PluginKeyValueStore(directory)
            val plugin = FakePlugin()

            val result = managerFor(store).applySettings(
                pluginId, plugin, mapOf("apiKey" to "synthetic-key-1", "label" to "hello")
            )

            assertTrue(result.isOk)
            assertEquals("synthetic-key-1", secretsFor(store).get("apiKey"))
            assertNull(store.getValue(pluginId, "apiKey"), "password must not enter the normal settings store")
            assertEquals("hello", store.getValue(pluginId, "label"))
            assertEquals("synthetic-key-1", plugin.received?.apiKey)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `blank password edit keeps the current secret`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-settings-secret-keep").toFile()
        try {
            val store = PluginKeyValueStore(directory)
            val secrets = secretsFor(store)
            secrets.put("apiKey", "existing-key-2")
            val plugin = FakePlugin()

            val result = managerFor(store).applySettings(
                pluginId, plugin, mapOf("apiKey" to "", "label" to "changed")
            )

            assertTrue(result.isOk)
            assertEquals(
                "existing-key-2", secrets.get("apiKey"),
                "a blank edit must keep the stored secret, not erase it"
            )
            assertNull(store.getValue(pluginId, "apiKey"))
            assertEquals("changed", store.getValue(pluginId, "label"))
            assertEquals(
                "existing-key-2", plugin.received?.apiKey,
                "the plugin must see the real key in the instance, never a blank"
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy password in the normal store migrates to the secret store once`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-settings-secret-migration").toFile()
        try {
            val store = PluginKeyValueStore(directory)
            store.storeValue(pluginId, "apiKey", "legacy-key-3")
            val plugin = FakePlugin()

            val model = managerFor(store).getSettingsModel(pluginId, plugin)
            val password = model?.schema?.filterIsInstance<PasswordSetting>()?.single()
            assertEquals("legacy-key-3", password?.currentValue)
            assertEquals("legacy-key-3", secretsFor(store).get("apiKey"))
            assertNull(store.getValue(pluginId, "apiKey"), "migration must remove the normal-store copy")

            // A fresh manager (no schema cache) sees the same state and changes nothing.
            val again = managerFor(store).getSettingsModel(pluginId, plugin)
            val againPassword = again?.schema?.filterIsInstance<PasswordSetting>()?.single()
            assertEquals("legacy-key-3", againPassword?.currentValue)
            assertNull(store.getValue(pluginId, "apiKey"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `plugin rejection persists nothing anywhere and surfaces the error`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-settings-secret-reject").toFile()
        try {
            val store = PluginKeyValueStore(directory)
            val plugin = FakePlugin().apply {
                onChanged = { Err(ServiceError.ValidationError("key does not look valid")) }
            }

            val result = managerFor(store).applySettings(
                pluginId, plugin, mapOf("apiKey" to "rejected-key-4", "label" to "hello")
            )

            assertTrue(result.getError() is ServiceError.ValidationError)
            assertNull(store.getValue(pluginId, "apiKey"))
            assertNull(store.getValue(pluginId, "label"))
            assertNull(secretsFor(store).get("apiKey"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `unexpected plugin exception becomes a typed failure and persists nothing`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-settings-secret-throw").toFile()
        try {
            val store = PluginKeyValueStore(directory)
            val plugin = FakePlugin().apply {
                onChanged = { throw IllegalStateException("synthetic provider failure") }
            }

            val result = managerFor(store).applySettings(
                pluginId, plugin, mapOf("apiKey" to "thrown-key-5", "label" to "hello")
            )

            assertTrue(result.getError() is ServiceError.UnknownError)
            assertNull(store.getValue(pluginId, "apiKey"))
            assertNull(store.getValue(pluginId, "label"))
            assertNull(secretsFor(store).get("apiKey"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cancellation from the plugin is never converted into an error result`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-settings-secret-cancel").toFile()
        try {
            val store = PluginKeyValueStore(directory)
            val plugin = FakePlugin().apply {
                onChanged = { throw CancellationException("synthetic cancellation") }
            }

            assertFailsWith<CancellationException> {
                managerFor(store).applySettings(
                    pluginId, plugin, mapOf("apiKey" to "cancelled-key-6", "label" to "hello")
                )
            }
            assertNull(store.getValue(pluginId, "label"))
            assertNull(secretsFor(store).get("apiKey"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
