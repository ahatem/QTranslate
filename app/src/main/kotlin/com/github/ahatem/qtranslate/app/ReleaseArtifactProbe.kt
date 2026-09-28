package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File

private object ProbeLocation

/** Readiness check launched from an extracted release distribution in CI. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 1) { "Expected extracted QTranslate directory" }
    val distribution = File(args[0]).canonicalFile
    val expectedIds = File(distribution, "portable-plugin-ids.txt").readLines().filter(String::isNotBlank).toSet()
    require(expectedIds.isNotEmpty()) { "Bundled plugin inventory is empty" }
    val portableJar = File(distribution, "QTranslate.jar")
    val packagedJar = File(distribution, "app/QTranslate.jar")
    val appJar = when {
        portableJar.isFile -> portableJar
        packagedJar.isFile -> packagedJar
        else -> error("QTranslate.jar is missing from the release distribution")
    }.canonicalFile
    val runningJar = File(ProbeLocation::class.java.protectionDomain.codeSource.location.toURI()).canonicalFile
    check(appJar == runningJar) { "Readiness check must run from the extracted QTranslate.jar" }
    check(File(distribution, "languages").isDirectory) { "Bundled languages directory is missing" }
    check(File(distribution, "themes").isDirectory) { "Bundled themes directory is missing" }
    val pluginJars = File(distribution, "plugins").listFiles { file -> file.isFile && file.extension == "jar" }
        ?.toList().orEmpty()
    check(pluginJars.size == expectedIds.size) {
        "Expected ${expectedIds.size} bundled plugin JARs, found ${pluginJars.size}"
    }

    System.setProperty("appData", distribution.absolutePath)
    check(AppDataDirectory.resolve().canonicalFile == distribution)
    val loggerFactory = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.INFO)
    val settings = SettingsRepository(
        distribution,
        Json { ignoreUnknownKeys = true; isLenient = true },
        loggerFactory.getLogger("ReleaseArtifactProbe")
    )
    val probeConfig = Configuration.DEFAULT.copy(autoCheckForUpdates = false)
    check(settings.updateConfiguration(probeConfig).isOk) { "Could not initialize isolated readiness settings" }
    val dependencies = buildDependencies(
        distribution, loggerFactory, settings, probeConfig
    )
    try {
        withTimeout(30_000) { dependencies.pluginManager.loadAndProcessPlugins() }
        val plugins = dependencies.pluginManager.plugins.value
        check(plugins.map { it.id }.toSet() == expectedIds && plugins.size == expectedIds.size) {
            "Packaged plugins differ from release inventory: expected $expectedIds, found ${plugins.map { it.id }}"
        }
        check(plugins.all { it.status == PluginStatus.ENABLED }) {
            "Plugin initialization failed: ${plugins.filter { it.status != PluginStatus.ENABLED }.map { "${it.id}: ${it.lastError}" }}"
        }
        Class.forName("javazoom.jl.player.Player")
        Class.forName("javazoom.jl.decoder.Bitstream")
        println("Release readiness passed: ${plugins.size} plugins and JLayer loaded on Java ${Runtime.version().feature()}")
    } finally {
        try {
            dependencies.pluginManager.shutdown()
        } finally {
            dependencies.mainStore.onShutdown()
            dependencies.httpClient.close()
            dependencies.appScope.cancel()
        }
    }
}
