package com.github.ahatem.qtranslate.api.core

/**
 * Defines the current version of the QTranslate Plugin API and provides
 * compatibility checking logic for plugins at load time.
 *
 * ### Versioning Contract (Semantic Versioning)
 * - **MAJOR**: Incremented on breaking, backwards-incompatible API changes.
 *   A plugin built against a different MAJOR version will be **rejected**.
 * - **MINOR**: Incremented when new features are added in a backwards-compatible manner.
 *   A plugin built against an older MINOR version is **accepted** (it simply won't
 *   know about the new features). A plugin built against a *newer* MINOR is **rejected**
 *   (it may depend on APIs that don't exist in this host).
 * - **PATCH**: Incremented for backwards-compatible bug fixes. Always **accepted**.
 *
 * ### Plugin manifest (`plugin.json`)
 * Every plugin JAR must include a `plugin.json` file at its resource root. The core reads
 * this file at load time to identify the plugin and verify compatibility before calling
 * any lifecycle methods. The manifest is parsed by the core module — plugin authors only
 * need to provide the JSON file.
 *
 * **Required fields:**
 * ```json
 * {
 *   "id":            "com.example.my-plugin",
 *   "name":          "My Plugin",
 *   "version":       "1.0.0",
 *   "author":        "Your Name",
 *   "description":   "A short description of what this plugin provides.",
 *   "minApiVersion": "1.0.0"
 * }
 * ```
 *
 * **Optional fields:**
 * ```json
 * {
 *   "repositoryUrl": "https://github.com/example/my-plugin",
 *   "icon":          "assets/icon.svg"
 * }
 * ```
 *
 * **Field rules:**
 * - `id` — reverse-domain style, lowercase, unique across all plugins. Used as a stable key
 *   for storing settings and preferences. Must not change between versions.
 * - `version` — the plugin's own version (independent of [VERSION]). Displayed in the UI.
 * - `minApiVersion` — the minimum API version this plugin requires, passed to [isCompatible].
 *   Set this to the value of [VERSION] that was current when you compiled the plugin.
 * - `icon` — path to an SVG icon relative to the plugin JAR's resource root. Falls back to
 *   a generated placeholder if omitted.
 *
 * The core application calls [isCompatible] with the `minApiVersion` value and rejects the
 * plugin with a user-visible error if the result is [CompatibilityResult.Incompatible].
 */
object ApiVersion {
    /** The major version. Incrementing this signals a breaking API change. */
    const val MAJOR = 1

    /** The minor version. Incrementing this signals new backwards-compatible features. */
    const val MINOR = 0

    /** The patch version. Incrementing this signals backwards-compatible bug fixes. */
    const val PATCH = 0

    /** The full version string in `MAJOR.MINOR.PATCH` format. */
    const val VERSION = "$MAJOR.$MINOR.$PATCH"

    /**
     * Determines whether a plugin compiled against [pluginApiVersion] is compatible
     * with this host's API version.
     *
     * **Compatibility rules:**
     * - MAJOR versions must match exactly.
     * - The plugin's MINOR version must be less than or equal to the host's MINOR version.
     * - PATCH version is ignored for compatibility purposes.
     *
     * @param pluginApiVersion The version string declared by the plugin (e.g. `"1.0.0"`).
     * @return A [CompatibilityResult] describing whether the plugin is compatible and why not if rejected.
     */
    fun isCompatible(pluginApiVersion: String): CompatibilityResult {
        val parts = pluginApiVersion.trim().split(".")
        if (parts.size != 3) {
            return CompatibilityResult.Incompatible(
                pluginVersion = pluginApiVersion,
                reason = "Malformed API version string '$pluginApiVersion'. Expected format: MAJOR.MINOR.PATCH."
            )
        }

        val (pluginMajor, pluginMinor, _) = try {
            Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        } catch (e: NumberFormatException) {
            return CompatibilityResult.Incompatible(
                pluginVersion = pluginApiVersion,
                reason = "API version string '$pluginApiVersion' contains non-integer components."
            )
        }

        return when {
            pluginMajor != MAJOR -> CompatibilityResult.Incompatible(
                pluginVersion = pluginApiVersion,
                reason = "Major version mismatch. Host API is v$MAJOR.x.x, plugin requires v$pluginMajor.x.x. " +
                        "This indicates a breaking API change."
            )
            pluginMinor > MINOR -> CompatibilityResult.Incompatible(
                pluginVersion = pluginApiVersion,
                reason = "Plugin requires API v$pluginApiVersion which is newer than the host's v$VERSION. " +
                        "Please update the host application."
            )
            else -> CompatibilityResult.Compatible(pluginVersion = pluginApiVersion)
        }
    }

    /**
     * Represents the outcome of an API compatibility check.
     */
    sealed class CompatibilityResult {
        abstract val pluginVersion: String

        /**
         * The plugin is compatible with this host's API version.
         * @param pluginVersion The version string declared by the plugin.
         */
        data class Compatible(override val pluginVersion: String) : CompatibilityResult()

        /**
         * The plugin is incompatible with this host's API version and must be rejected.
         * @param pluginVersion The version string declared by the plugin.
         * @param reason A human-readable explanation of why the plugin was rejected,
         *               suitable for display in the UI and logs.
         */
        data class Incompatible(
            override val pluginVersion: String,
            val reason: String
        ) : CompatibilityResult()
    }
}