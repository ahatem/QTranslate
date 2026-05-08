package com.github.ahatem.qtranslate.core.plugin.settings

/**
 * Rule used for conditional field visibility.
 *
 * A field that carries this rule is hidden unless the field identified by
 * [propertyName] currently has exactly [requiredValue] as its string value.
 */
data class ShowIfRule(
    /** Java field name of the controlling field. */
    val propertyName: String,
    /** The string value the controlling field must have for this field to be visible. */
    val requiredValue: String
)

// =============================================================================
// Schema for a standalone action button declared with @PluginAction
// =============================================================================

data class PluginActionSchema(
    val methodName: String,
    val label: String,
    val tooltip: String,
    val order: Int,
    val group: String
)

// =============================================================================
// Group — a named section that contains a subset of fields and actions
// =============================================================================

/**
 * Represents a named section in the plugin settings panel.
 * Built by [PluginSettingsSchemaBuilder] from [@SettingGroup][com.github.ahatem.qtranslate.api.settings.SettingGroup]
 * annotations on the settings class.
 *
 * The model always contains at least one group. When no `@SettingGroup` annotations are
 * present, a single unnamed group (key `""`, title `""`) holds all fields — the UI
 * renders it without a section header, preserving the flat appearance of simple plugins.
 */
data class PluginSettingsGroup(
    /** Matches the `key` of the `@SettingGroup` annotation. Empty = ungrouped fields. */
    val key: String,
    /** Section title rendered as a bold header. Empty = no header rendered. */
    val title: String,
    val description: String = "",
    val order: Int = 0,
    val collapsible: Boolean = false,
    val defaultCollapsed: Boolean = false,
    /** Fields belonging to this group, sorted by [SettingSchema.order]. */
    val fields: List<SettingSchema>,
    /** Standalone action buttons for this group, sorted by [PluginActionSchema.order]. */
    val actions: List<PluginActionSchema> = emptyList()
)

// =============================================================================
// Sealed field schema hierarchy
// =============================================================================

/**
 * Sealed hierarchy describing a single configurable field in a plugin's settings panel.
 *
 * Each subclass corresponds to a [com.github.ahatem.qtranslate.api.settings.SettingType]
 * and carries the additional metadata specific to that UI control type.
 *
 * Instances are produced by [PluginSettingsSchemaBuilder] and consumed by the UI layer
 * to render the settings panel — the UI does a `when` match on the subclass to decide
 * which Swing component to create.
 *
 * [currentValue] always holds the persisted value as a raw string, regardless of the
 * underlying Kotlin type. The UI is responsible for round-tripping values through strings.
 */
sealed class SettingSchema {
    abstract val propertyName: String
    abstract val label: String
    abstract val description: String
    abstract val order: Int
    abstract val isRequired: Boolean
    abstract val currentValue: String
    abstract val defaultValue: String

    /**
     * Key of the [@SettingGroup][com.github.ahatem.qtranslate.api.settings.SettingGroup]
     * this field belongs to. Empty string = ungrouped.
     */
    abstract val group: String

    /**
     * Conditional visibility rule. `null` means always visible.
     * When set, the field is hidden unless its controlling field has the required value.
     */
    abstract val showIf: ShowIfRule?
}

// ---- Text-based ----

data class TextSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null,
    val validation: String = "",
    val maxLength: Int = 0
) : SettingSchema()

data class PasswordSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null,
    val validation: String = "",
    val maxLength: Int = 0
) : SettingSchema()

data class TextAreaSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null,
    val validation: String = "",
    val rows: Int = 3,
    val maxLength: Int = 0
) : SettingSchema()

// ---- Numeric ----

data class NumberSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val step: Double? = null
) : SettingSchema()

data class IntegerSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null,
    val minValue: Int? = null,
    val maxValue: Int? = null,
    val step: Int? = 1
) : SettingSchema()

/**
 * A bounded horizontal slider for float values.
 *
 * [minValue] and [maxValue] are always present — the builder validates this and falls
 * back to [NumberSetting] if either is missing.
 */
data class SliderSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null,
    val minValue: Double,
    val maxValue: Double,
    val step: Double = 0.01
) : SettingSchema()

// ---- Boolean ----

data class BooleanSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null
) : SettingSchema()

// ---- Selection ----

data class DropdownSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null,
    val options: List<String>
) : SettingSchema()

// ---- Path-based ----

data class FilePathSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null,
    val fileExtensions: List<String> = emptyList(),
    val allowMultiple: Boolean = false
) : SettingSchema()

data class DirectoryPathSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null
) : SettingSchema()

/**
 * The plugin provides a full `JComponent` via a no-arg factory method.
 * [factoryMethod] is the method name to invoke on the live settings instance.
 */
data class CustomPanelSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    override val group: String = "",
    override val showIf: ShowIfRule? = null,
    val factoryMethod: String
) : SettingSchema()

// =============================================================================
// Internal factory helpers — used only by PluginSettingsSchemaBuilder
// =============================================================================

internal data class SettingArgs(
    val propertyName: String,
    val label: String,
    val description: String,
    val order: Int,
    val required: Boolean,
    val currentValue: String,
    val defaultValue: String,
    val validation: String,
    val options: String,
    // New
    val group: String = "",
    val showIf: ShowIfRule? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val step: Double? = null,
    val maxLength: Int = 0,
    val rows: Int = 3,
    val fileExtensions: List<String> = emptyList(),
    val allowMultiple: Boolean = false,
    val actionMethod: String = "",
    val actionLabel: String = ""
)

internal fun buildTextSetting(a: SettingArgs) = TextSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.group, a.showIf,
    a.validation, a.maxLength
)

internal fun buildPasswordSetting(a: SettingArgs) = PasswordSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.group, a.showIf,
    a.validation, a.maxLength
)

internal fun buildTextAreaSetting(a: SettingArgs) = TextAreaSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.group, a.showIf,
    a.validation, a.rows, a.maxLength
)

internal fun buildIntegerSetting(a: SettingArgs) = IntegerSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.group, a.showIf,
    minValue = a.minValue?.toInt(), maxValue = a.maxValue?.toInt(),
    step = a.step?.toInt() ?: 1
)

internal fun buildNumberSetting(a: SettingArgs) = NumberSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.group, a.showIf,
    a.minValue, a.maxValue, a.step
)

internal fun buildSliderSetting(a: SettingArgs): SettingSchema {
    val min = a.minValue
    val max = a.maxValue
    return if (min == null || max == null) {
        // Bounds are required — fall back to a plain number spinner
        buildNumberSetting(a)
    } else {
        SliderSetting(
            a.propertyName, a.label, a.description, a.order, a.required,
            a.currentValue, a.defaultValue, a.group, a.showIf,
            minValue = min, maxValue = max,
            step = if (a.step != null && !a.step.isNaN()) a.step else 0.01
        )
    }
}

internal fun buildBooleanSetting(a: SettingArgs) = BooleanSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.group, a.showIf
)

internal fun buildDropdownSetting(a: SettingArgs) = DropdownSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.group, a.showIf,
    a.options.split(',').map { it.trim() }.filter { it.isNotBlank() }
)

internal fun buildFilePathSetting(a: SettingArgs) = FilePathSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.group, a.showIf,
    a.fileExtensions, a.allowMultiple
)

internal fun buildDirectoryPathSetting(a: SettingArgs) = DirectoryPathSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.group, a.showIf
)

internal fun buildCustomPanelSetting(a: SettingArgs) = CustomPanelSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.group, a.showIf,
    factoryMethod = a.actionMethod
)
