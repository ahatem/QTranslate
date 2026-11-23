package com.github.ahatem.qtranslate.plugins.lens

import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.michaelbull.result.getOrThrow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleLensPluginTest {

    @Test
    fun `initialize reads persisted override and exposes it through getSettings`() = runBlocking {
        val context = FakePluginContext()
        context.secrets.put("apiKey", "saved-key")
        val plugin = GoogleLensPlugin()

        plugin.initialize(context).getOrThrow { AssertionError(it.message) }

        assertEquals("saved-key", plugin.getSettings().apiKey)
    }

    @Test
    fun `onSettingsChanged persists trimmed override and updates live settings`() = runBlocking {
        val context = FakePluginContext()
        val plugin = GoogleLensPlugin()
        plugin.initialize(context).getOrThrow { AssertionError(it.message) }

        plugin.onSettingsChanged(GoogleLensSettings(apiKey = "  custom-key  "))
            .getOrThrow { AssertionError(it.message) }

        assertEquals("custom-key", context.secrets.get("apiKey"))
        assertTrue(context.secrets.contains("apiKey"))
        assertEquals("custom-key", plugin.getSettings().apiKey)
    }

    @Test
    fun `onSettingsChanged with blank override clears persisted value`() = runBlocking {
        val context = FakePluginContext()
        context.secrets.put("apiKey", "saved-key")
        val plugin = GoogleLensPlugin()
        plugin.initialize(context).getOrThrow { AssertionError(it.message) }

        plugin.onSettingsChanged(GoogleLensSettings(apiKey = "   "))
            .getOrThrow { AssertionError(it.message) }

        assertFalse(context.secrets.contains("apiKey"))
        assertEquals("", plugin.getSettings().apiKey)
    }
}
