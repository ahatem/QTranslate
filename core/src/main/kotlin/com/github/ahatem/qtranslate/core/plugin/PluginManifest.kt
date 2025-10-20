package com.github.ahatem.qtranslate.core.plugin

import kotlinx.serialization.Serializable

/**
 * Represents the metadata for a plugin, loaded from `resources/plugin.json`.
 * This file must exist in every plugin's resources.
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val minApiVersion: String,
    val repositoryUrl: String? = null,
    val icon: String? = null
)