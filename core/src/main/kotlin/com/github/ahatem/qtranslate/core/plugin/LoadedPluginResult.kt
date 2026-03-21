package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.plugin.Plugin
import java.io.File

/**
 * The raw result of successfully loading a plugin JAR from disk.
 *
 * Produced by [PluginLoader] and consumed by [com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry]
 * and [com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginLifecycleHandler].
 * At this stage the plugin has been instantiated and its manifest parsed, but
 * [Plugin.initialize] has not yet been called — the plugin is not yet active.
 *
 * @property plugin      The [Plugin] instance loaded via [java.util.ServiceLoader].
 * @property manifest    Metadata parsed from `plugin.json` inside the JAR.
 * @property jarFile     The JAR file on disk this plugin was loaded from.
 * @property jarHash     SHA-256 hash of [jarFile], used for integrity tracking across runs.
 * @property classLoader The [ClassLoader] that loaded this plugin's classes.
 *                       Retained so the core can resolve plugin-specific resources
 *                       (icons, assets) after the plugin is registered.
 */
data class LoadedPluginResult(
    val plugin: Plugin<*>,
    val manifest: PluginManifest,
    val jarFile: File,
    val jarHash: String,
    val classLoader: ClassLoader
)