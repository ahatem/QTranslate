package com.github.ahatem.qtranslate.plugins.wikimedia

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files

internal class TestPluginContext : PluginContext {
    private val dataDirectory: File = Files.createTempDirectory("wikimedia-reference-test").toFile()
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val logger: Logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    override suspend fun getValue(key: String): String? = null
    override suspend fun storeValue(key: String, value: String) = Unit
    override suspend fun deleteValue(key: String) = Unit
    override suspend fun notify(title: String, body: String, type: NotificationType) = Unit
    override fun getPluginDataDirectory(): File = dataDirectory
}
