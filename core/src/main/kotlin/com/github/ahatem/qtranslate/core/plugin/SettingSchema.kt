package com.github.ahatem.qtranslate.core.plugin

sealed class SettingSchema {
    abstract val propertyName: String
    abstract val label: String
    abstract val description: String
    abstract val order: Int
    abstract val isRequired: Boolean
    abstract val currentValue: String
    abstract val defaultValue: String
}

// ---- Text-based settings ----

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
) : SettingSchema() {
    companion object
}

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
) : SettingSchema() {
    companion object
}

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
) : SettingSchema() {
    companion object
}

// ---- Numeric settings ----

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
) : SettingSchema() {
    companion object
}

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
) : SettingSchema() {
    companion object
}

// ---- Boolean setting ----

data class BooleanSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String
) : SettingSchema() {
    companion object
}

// ---- Selection-based settings ----

data class DropdownSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String,
    val options: List<String>
) : SettingSchema() {
    companion object
}

// ---- Path-based settings ----

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
) : SettingSchema() {
    companion object
}

data class DirectoryPathSetting(
    override val propertyName: String,
    override val label: String,
    override val description: String,
    override val order: Int,
    override val isRequired: Boolean,
    override val currentValue: String,
    override val defaultValue: String
) : SettingSchema() {
    companion object
}
