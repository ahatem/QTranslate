package com.github.ahatem.qtranslate.plugins.systemocr

import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemOcrPluginTest {

    @Test
    fun `offers no services before it is enabled`() {
        assertEquals(emptyList(), SystemOcrPlugin().getServices())
    }

    @Test
    fun `exposes exactly one OCR service once enabled`() = runBlocking {
        val plugin = SystemOcrPlugin()
        plugin.initialize(FakePluginContext())

        assertEquals(Unit, plugin.onEnable().unwrap())

        val services = plugin.getServices()
        assertEquals(1, services.size)
        assertTrue(services.single() is OCR)
        assertEquals("system-ocr", services.single().key)
        assertTrue(ServiceRole.OCR in ServiceRole.of(services.single()))
    }

    @Test
    fun `disabling clears the services`() = runBlocking {
        val plugin = SystemOcrPlugin()
        plugin.initialize(FakePluginContext())
        plugin.onEnable()

        plugin.onDisable()

        assertEquals(emptyList(), plugin.getServices())
    }

    @Test
    fun `declares no settings`() {
        assertEquals(PluginSettings.None, SystemOcrPlugin().getSettings())
    }

    @Test
    fun `the service requires no credentials or configuration`() = runBlocking {
        val plugin = SystemOcrPlugin()
        plugin.initialize(FakePluginContext())
        plugin.onEnable()

        val service = plugin.getServices().single()
        assertEquals(false, service.metadata.requiresConfiguration)
        assertTrue(service.metadata.isFree == true)
    }
}
