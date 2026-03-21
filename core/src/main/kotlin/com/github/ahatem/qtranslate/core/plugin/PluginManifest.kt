package com.github.ahatem.qtranslate.core.plugin

import kotlinx.serialization.Serializable

/**
 * Metadata for a plugin, loaded from `resources/plugin.json` inside the plugin JAR.
 *
 * Every plugin JAR must include this file at its resource root. The core reads it
 * at load time to identify the plugin, verify compatibility, and display information
 * in the plugin management UI — before any plugin code is executed.
 *
 * See [com.github.ahatem.qtranslate.api.core.ApiVersion] for the full `plugin.json`
 * field contract and versioning rules.
 */
@Serializable
data class PluginManifest(
    /** Stable reverse-domain identifier. Must never change between versions. */
    val id: String,

    /** Human-readable name shown in the plugin management UI. */
    val name: String,

    /** The plugin's own version string (e.g. `"1.2.0"`). Independent of the API version. */
    val version: String,

    /** The plugin author's name or organisation. */
    val author: String,

    /** A short description of what the plugin provides. */
    val description: String,

    /**
     * The minimum API version this plugin requires (e.g. `"1.0.0"`).
     * Passed to [com.github.ahatem.qtranslate.api.core.ApiVersion.isCompatible] at load time.
     */
    val minApiVersion: String,

    /** Optional URL to the plugin's source repository or homepage. */
    val repositoryUrl: String? = null,

    /** Optional path to an SVG icon within the plugin JAR's resource root. */
    val icon: String? = null
)
