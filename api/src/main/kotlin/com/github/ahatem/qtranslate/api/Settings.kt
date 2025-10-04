package com.github.ahatem.qtranslate.api

/**
 * Annotate fields in your settings class to have the core auto-generate UI.
 *
 * Example:
 * ```
 * class MyPluginSettings {
 *     @Setting(
 *         label = "API Key",
 *         description = "Your service API key",
 *         type = SettingType.PASSWORD,
 *         required = true,
 *         order = 1
 *     )
 *     var apiKey: String = ""
 * }
 * ```
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Setting(
    /** Display label */
    val label: String,

    /** Help text shown to user */
    val description: String = "",

    /** UI control type */
    val type: SettingType = SettingType.TEXT,

    /** Display order (lower = first) */
    val order: Int = 0,

    /** Is this field required? */
    val required: Boolean = false,

    /** For DROPDOWN type: comma-separated options */
    val options: String = "",

    /** Validation regex pattern */
    val validation: String = "",

    /** Default value (as string) */
    val defaultValue: String = ""
)

enum class SettingType {
    /** Single-line text input */
    TEXT,

    /** Password field (masked) */
    PASSWORD,

    /** Multi-line text area */
    TEXTAREA,

    /** Numeric input */
    NUMBER,

    /** Checkbox */
    BOOLEAN,

    /** Dropdown selection */
    DROPDOWN,

    /** File path selector */
    FILE_PATH,

    /** Directory path selector */
    DIRECTORY_PATH
}