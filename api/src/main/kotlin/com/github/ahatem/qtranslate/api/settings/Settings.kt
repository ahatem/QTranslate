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
 * @SettingGroups(
 *     SettingGroup(key = "auth", title = "Authentication", order = 10),
 *     SettingGroup(key = "advanced", title = "Advanced", order = 20, collapsible = true)
 * )
 * data class MyPluginSettings(
 *     @field:Setting(label = "API Key", type = SettingType.PASSWORD, isRequired = true,
 *                    group = "auth", order = 10)
 *     var apiKey: String = "",
 *
 *     @field:Setting(label = "Temperature", type = SettingType.SLIDER,
 *                    group = "advanced", order = 10,
 *                    minValue = 0.0, maxValue = 2.0, step = 0.05, defaultValue = "0.3")
 *     var temperature: Double = 0.3,
 *
 *     @field:Setting(label = "Enable cache", type = SettingType.BOOLEAN,
 *                    group = "advanced", order = 20, defaultValue = "true")
 *     var cacheEnabled: Boolean = true
 * ) : PluginSettings.Configurable()
 * ```
 *
 * ### Field order
 * Fields are sorted by [order] (ascending) before rendering. Use gaps (e.g. 10, 20, 30)
 * to leave room for future fields without renumbering existing ones.
 *
 * ### Backward compatibility
 * All parameters added after the initial release have default values — existing `@Setting`
 * usages without those parameters compile unchanged.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Setting(
    /** The human-readable label displayed next to the UI component. */
    val label: String,

    /** Optional help text displayed as a sub-label below the component. */
    val description: String = "",

    /** The type of UI component to render for this field. */
    val type: SettingType = SettingType.TEXT,

    /**
     * The display order in the settings panel (ascending, lower numbers appear first).
     * Use gaps between values (e.g. 10, 20, 30) to leave room for future insertions.
     */
    val order: Int = 0,

    /**
     * If `true`, the core will visually mark this field as required and prevent saving
     * if the field is empty or unchanged from its default.
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
    val defaultValue: String = "",

    // -------------------------------------------------------------------------
    // Grouping
    // -------------------------------------------------------------------------

    /**
     * The key of the [@SettingGroup][SettingGroup] this field belongs to.
     * Empty string means the field is ungrouped and rendered at the top of the form.
     */
    val group: String = "",

    // -------------------------------------------------------------------------
    // Conditional visibility
    // -------------------------------------------------------------------------

    /**
     * Makes this field visible only when another field has a specific value.
     * Format: `"propertyName=value"` — the field is shown only when the referenced
     * property's current value equals the given string.
     *
     * Example: `showIf = "useCustomEndpoint=true"` hides the field unless
     * `useCustomEndpoint` is set to `"true"`.
     *
     * Empty string means the field is always visible.
     */
    val showIf: String = "",

    // -------------------------------------------------------------------------
    // Numeric constraints  (NUMBER and SLIDER types)
    // -------------------------------------------------------------------------

    /**
     * Minimum allowed value for [SettingType.NUMBER] and [SettingType.SLIDER].
     * [Double.NaN] means no minimum is enforced.
     */
    val minValue: Double = Double.NaN,

    /**
     * Maximum allowed value for [SettingType.NUMBER] and [SettingType.SLIDER].
     * [Double.NaN] means no maximum is enforced.
     * **Required** for [SettingType.SLIDER] — a slider without bounds falls back to NUMBER.
     */
    val maxValue: Double = Double.NaN,

    /**
     * Step increment for [SettingType.NUMBER] and [SettingType.SLIDER].
     * [Double.NaN] means the default step is used (1.0 for NUMBER, 0.01 for SLIDER).
     */
    val step: Double = Double.NaN,

    // -------------------------------------------------------------------------
    // Text constraints  (TEXT, PASSWORD, TEXTAREA types)
    // -------------------------------------------------------------------------

    /**
     * Maximum number of characters allowed in [SettingType.TEXT], [SettingType.PASSWORD],
     * or [SettingType.TEXTAREA] fields.
     *
     * `0` means no limit. When set, a character counter `"n / max"` is shown below the
     * field and the counter turns red when the limit is reached.
     */
    val maxLength: Int = 0,

    // -------------------------------------------------------------------------
    // Textarea-specific
    // -------------------------------------------------------------------------

    /**
     * Preferred visible row count for [SettingType.TEXTAREA] fields.
     * Defaults to 3.
     */
    val rows: Int = 3,

    // -------------------------------------------------------------------------
    // File path  (FILE_PATH type)
    // -------------------------------------------------------------------------

    /**
     * Comma-separated file extensions (without dots) used to filter the file chooser for
     * [SettingType.FILE_PATH] fields. Example: `"json,yaml,toml"`.
     * Empty string means all files are shown.
     */
    val fileExtensions: String = "",

    /**
     * When `true`, the file chooser allows selecting multiple files for
     * [SettingType.FILE_PATH] fields. The selected paths are stored as a
     * semicolon-separated string.
     */
    val allowMultiple: Boolean = false,

    // -------------------------------------------------------------------------
    // Inline action button
    // -------------------------------------------------------------------------

    /**
     * Name of a no-argument suspend method on the [com.github.ahatem.qtranslate.api.plugin.PluginSettings.Configurable]
     * class to invoke when an action button is clicked next to this field.
     *
     * Method signature must be: `suspend fun methodName(): String?`
     * A non-null return value is shown as an informational message to the user.
     * Empty string means no action button is shown.
     */
    val actionMethod: String = "",

    /**
     * Label for the action button shown next to this field.
     * Defaults to `"Run"` when [actionMethod] is non-empty and this is blank.
     */
    val actionLabel: String = ""
)

// =============================================================================
// Group annotations — organize fields into named sections
// =============================================================================

/**
 * Declares a named section in the plugin settings panel.
 *
 * Apply multiple groups to a [com.github.ahatem.qtranslate.api.plugin.PluginSettings.Configurable]
 * class using the [@SettingGroups][SettingGroups] container, then reference [key] in each
 * [@Setting][Setting]'s `group` parameter:
 *
 * ```kotlin
 * @SettingGroups(
 *     SettingGroup(key = "connection", title = "Connection",   order = 10),
 *     SettingGroup(key = "advanced",   title = "Advanced",     order = 20,
 *                  collapsible = true, defaultCollapsed = true)
 * )
 * data class MySettings(...) : PluginSettings.Configurable()
 * ```
 *
 * Fields whose `group` key does not match any [@SettingGroup][SettingGroup] are rendered
 * at the top of the form without a section header.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class SettingGroup(
    /** Unique key referenced by [@Setting.group][Setting.group]. */
    val key: String,
    /** Human-readable section title rendered as a header above the group's fields. */
    val title: String,
    /** Optional subtitle shown beneath the section title. */
    val description: String = "",
    /** Display order relative to other groups (ascending). */
    val order: Int = 0,
    /** When `true`, the group can be collapsed by clicking its header. */
    val collapsible: Boolean = false,
    /** When `true`, the group starts collapsed on first render (requires [collapsible]). */
    val defaultCollapsed: Boolean = false
)

/**
 * Container for multiple [@SettingGroup][SettingGroup] annotations on a single class.
 *
 * ```kotlin
 * @SettingGroups(
 *     SettingGroup(key = "auth",     title = "Authentication", order = 10),
 *     SettingGroup(key = "advanced", title = "Advanced",       order = 20)
 * )
 * data class MySettings(...) : PluginSettings.Configurable()
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class SettingGroups(vararg val groups: SettingGroup)

// =============================================================================
// Plugin action — standalone buttons that invoke a method on the settings class
// =============================================================================

/**
 * Marks a method on a [com.github.ahatem.qtranslate.api.plugin.PluginSettings.Configurable]
 * class as an invokable action button in the settings panel.
 *
 * The annotated method must have the signature: `suspend fun methodName(): String?`
 * A non-null return value is shown as an informational message to the user after execution.
 * The method is called on the **live settings instance** (the values currently persisted —
 * not the in-progress form values). Use this for actions like "Test Connection" or
 * "Verify API Key" that operate on the saved configuration.
 *
 * ```kotlin
 * @PluginAction(label = "Test Connection", group = "connection", tooltip = "Verifies the API key.")
 * suspend fun testConnection(): String? {
 *     // Use stored apiKey / baseUrl to make a test request
 *     return "Connected successfully."
 * }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class PluginAction(
    /** Button label shown in the settings panel. */
    val label: String,
    /** Key of the [@SettingGroup][SettingGroup] this action appears in. Empty = top of form. */
    val group: String = "",
    /** Display order of this action button within its group's action strip. */
    val order: Int = 0,
    /** Tooltip shown on the button. */
    val tooltip: String = ""
)
