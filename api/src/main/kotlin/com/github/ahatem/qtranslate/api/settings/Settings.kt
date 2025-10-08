package com.github.ahatem.qtranslate.api.settings

/**
 * Annotate fields within a plain Kotlin class to have the core application
 * automatically generate a settings UI. The core will instantiate this class
 * and use reflection to read these annotations and build the panel.
 *
 * Example:
 * ```
 * class MyPluginSettings {
 *     @Setting(
 *         label = "API Key",
 *         description = "Your personal API key for the service.",
 *         type = SettingType.PASSWORD,
 *         order = 1
 *     )
 *     var apiKey: String = ""
 *
 *     @Setting(
 *         label = "Enable Advanced Mode",
 *         type = SettingType.BOOLEAN,
 *         defaultValue = "true",
 *         order = 2
 *     )
 *     var advancedMode: Boolean = true
 * }
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Setting(
    /** The human-readable label displayed next to the UI component. */
    val label: String,

    /** Optional help text displayed as a tooltip to the user. */
    val description: String = "",

    /** The type of UI component to generate for this setting. */
    val type: SettingType = SettingType.TEXT,

    /** The display order in the UI (lower numbers appear first). */
    val order: Int = 0,

    /** If true, the core may indicate that this field must be filled. */
    val isRequired: Boolean = false,

    /** For DROPDOWN type, provide a comma-separated list of options. */
    val options: String = "",

    /** Optional regex pattern for validation of TEXT or PASSWORD fields. */
    val validation: String = "",

    /** The default value for this field, represented as a string. */
    val defaultValue: String = ""
)

