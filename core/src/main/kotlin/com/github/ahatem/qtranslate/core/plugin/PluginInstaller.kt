package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PluginInstaller(
    private val lifecycleManager: PluginLifecycleManager,
    private val pluginsDir: File,
    private val settingsRepository: SettingsRepository,
    private val pluginLoaderFactory: (Logger) -> PluginLoader,
    private val logger: Logger
) {
    private val stateMutex = Mutex()

    suspend fun installPlugin(sourceJar: File): Result<Unit, String> = stateMutex.withLock {
        val loader = pluginLoaderFactory(logger)
        val manifest = loader.getManifestFromJar(sourceJar) ?: return Err("Invalid plugin file")
        val pluginId = manifest.id

        val existing = lifecycleManager.plugins.value.find { it.manifest.id == pluginId }
        if (existing != null) return Err("Plugin '$pluginId' already installed")

        val destJar = File(pluginsDir, sourceJar.name)
        Files.copy(sourceJar.toPath(), destJar.toPath(), StandardCopyOption.REPLACE_EXISTING)

        loader.loadPluginFromFile(destJar) ?: return Err("Failed to load plugin after install")
        lifecycleManager.loadAllPlugins() // triggers initialization
        Ok(Unit)
    }

    suspend fun uninstallPlugin(pluginId: String): Result<Unit, String> = stateMutex.withLock {
        val pluginState = lifecycleManager.plugins.value.find { it.manifest.id == pluginId }
            ?: return Err("Plugin '$pluginId' not found")

        lifecycleManager.disablePlugin(pluginId)
        val jarFile = File(pluginsDir, pluginState.manifest.id + ".jar")
        if (jarFile.exists()) jarFile.delete()
        Ok(Unit)
    }
}
