package com.github.ahatem.qtranslate.core.plugin.settings

/**
 * Sealed hierarchy describing a single configurable field in a plugin's settings panel.
 *
 * Each subclass corresponds to a [com.github.ahatem.qtranslate.api.settings.SettingType]
 * and carries the additional metadata specific to that UI control type (e.g. `maxLength`
 * for text fields, `options` for dropdowns, `minValue`/`maxValue` for numbers).
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
    val validation: String = "",
    val maxLength: Int? = null
) : SettingSchema()

data class PasswordSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    val validation: String = "",
    val maxLength: Int? = null
) : SettingSchema()

data class TextAreaSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    val validation: String = "",
    val rows: Int = 3,
    val maxLength: Int? = null
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
    val minValue: Int? = null,
    val maxValue: Int? = null,
    val step: Int? = 1
) : SettingSchema()

// ---- Boolean ----

data class BooleanSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String
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
    override val defaultValue: String
) : SettingSchema()

// -------------------------------------------------------------------------
// Internal factory helpers — used only by PluginSettingsSchemaBuilder
// -------------------------------------------------------------------------

internal data class SettingArgs(
    val propertyName: String,
    val label: String,
    val description: String,
    val order: Int,
    val required: Boolean,
    val currentValue: String,
    val defaultValue: String,
    val validation: String,
    val options: String
)

internal fun buildTextSetting(a: SettingArgs) = TextSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.validation
)

internal fun buildPasswordSetting(a: SettingArgs) = PasswordSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.validation
)

internal fun buildTextAreaSetting(a: SettingArgs) = TextAreaSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.validation
)

internal fun buildIntegerSetting(a: SettingArgs) = IntegerSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue
)

internal fun buildNumberSetting(a: SettingArgs) = NumberSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue
)

internal fun buildBooleanSetting(a: SettingArgs) = BooleanSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue
)

internal fun buildDropdownSetting(a: SettingArgs) = DropdownSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue,
    a.options.split(',').map { it.trim() }.filter { it.isNotBlank() }
)

internal fun buildFilePathSetting(a: SettingArgs) = FilePathSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue
)

internal fun buildDirectoryPathSetting(a: SettingArgs) = DirectoryPathSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue
)
