package com.github.ahatem.qtranslate.api.settings

/**
 * Annotates a property in a [com.github.ahatem.qtranslate.api.plugin.PluginSettings.Configurable]
 * subclass to have the core automatically generate a settings UI panel for that field.
 *
 * ### How the core reads this annotation
 * The core uses **Java reflection** (`Class.getDeclaredFields()`) to discover annotated fields
 * at runtime. Because of how Kotlin compiles properties, the annotation **must target the
 * backing field** (`AnnotationTarget.FIELD`) to be visible to Java reflection. Targeting only
 * `AnnotationTarget.PROPERTY` places the annotation on Kotlin's property metadata, which
 * Java reflection cannot see — resulting in a silently empty settings panel.
 *
 * Always use the `@field:Setting(...)` use-site target when annotating properties in a
 * `data class` or any class where Kotlin may not automatically propagate the annotation
 * to the backing field:
 *
 * ```kotlin
 * data class MyPluginSettings(
 *     @field:Setting(
 *         label = "API Key",
 *         description = "Your personal API key.",
 *         type = SettingType.PASSWORD,
 *         isRequired = true,
 *         order = 1
 *     )
 *     var apiKey: String = "",
 *
 *     @field:Setting(
 *         label = "Enable cache",
 *         type = SettingType.BOOLEAN,
 *         defaultValue = "true",
 *         order = 2
 *     )
 *     var cacheEnabled: Boolean = true
 * ) : PluginSettings.Configurable()
 * ```
 *
 * ### Field order
 * Fields are sorted by [order] (ascending) before rendering. Use gaps (e.g. 10, 20, 30)
 * to leave room for future fields without renumbering existing ones.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Setting(
    /** The human-readable label displayed next to the UI component. */
    val label: String,

    /** Optional help text displayed as a tooltip or sub-label to the user. */
    val description: String = "",

    /** The type of UI component to render for this field. */
    val type: SettingType = SettingType.TEXT,

    /**
     * The display order in the settings panel (ascending, lower numbers appear first).
     * Use gaps between values (e.g. 10, 20, 30) to leave room for future insertions.
     */
    val order: Int = 0,

    /**
     * If `true`, the core will visually mark this field as required and may prevent
     * saving if the field is empty or unchanged from its default.
     */
    val isRequired: Boolean = false,

    /**
     * For [SettingType.DROPDOWN] fields only — a comma-separated list of selectable options.
     * Example: `"Standard,Casual,Formal"`.
     * Ignored for all other [SettingType] values.
     */
    val options: String = "",

    /**
     * An optional regex pattern used to validate [SettingType.TEXT] or [SettingType.PASSWORD]
     * fields before saving. The core will reject the save and highlight the field if the
     * current value does not match.
     * Example: `"^[A-Za-z0-9_\\-]{10,64}$"` for an API key format check.
     */
    val validation: String = "",

    /**
     * The default value for this field, represented as a string.
     * Used by the core to detect whether the user has changed a field and to populate
     * the field on first render if no stored value exists.
     */
    val defaultValue: String = ""
)