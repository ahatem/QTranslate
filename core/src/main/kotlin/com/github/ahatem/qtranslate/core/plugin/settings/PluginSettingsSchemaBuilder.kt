package com.github.ahatem.qtranslate.core.plugin.settings

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import java.lang.reflect.Field
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Builds a [PluginSettingsModel] by introspecting a plugin's settings class.
 *
 * This is a pure read operation — it reads persisted values from [PluginKeyValueStore]
 * and annotation metadata from the settings class, but never writes anything.
 *
 * ### Why Java reflection instead of Kotlin reflection
 * The [@Setting][com.github.ahatem.qtranslate.api.settings.Setting] annotation targets
 * `AnnotationTarget.FIELD` so that Java reflection can find it via `getDeclaredFields()`.
 * Kotlin reflection's `memberProperties` operates on Kotlin's property metadata layer,
 * which does not see `FIELD`-targeted annotations — using it here would silently return
 * no annotated properties and produce an empty settings panel.
 */
internal class PluginSettingsSchemaBuilder(
    private val pluginKeyValueStore: PluginKeyValueStore,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("PluginSettingsSchemaBuilder")

    /**
     * Builds a [PluginSettingsModel] for [plugin] if it has configurable settings,
     * or returns `null` if the plugin uses [PluginSettings.None].
     *
     * Persisted values from storage are used as `currentValue` where available;
     * the annotation's `defaultValue` is used as the fallback.
     */
    suspend fun build(pluginId: String, plugin: Plugin<*>): PluginSettingsModel? {
        return when (plugin.getSettings()) {
            is PluginSettings.None -> null
            is PluginSettings.Configurable -> buildModel(pluginId, plugin)
        }
    }

    private suspend fun buildModel(pluginId: String, plugin: Plugin<*>): PluginSettingsModel? {
        val settingsInstance = plugin.getSettings() as? PluginSettings.Configurable ?: return null
        val settingsClass = settingsInstance::class.java

        return try {
            val schema = buildSchema(settingsClass, pluginId).sortedBy { it.order }
            PluginSettingsModel(settingsClass, schema)
        } catch (e: Exception) {
            logger.error("Failed to build settings schema for plugin '$pluginId': ${e.message}", e)
            null
        }
    }

    private suspend fun buildSchema(settingsClass: Class<*>, pluginId: String): List<SettingSchema> {
        // getDeclaredFields() sees @field:Setting annotations — memberProperties does not.
        return settingsClass.declaredFields
            .mapNotNull { field ->
                field.isAccessible = true
                val annotation = field.getAnnotation(Setting::class.java) ?: return@mapNotNull null
                buildFieldSchema(field, annotation, pluginId)
            }
    }

    private suspend fun buildFieldSchema(
        field: Field,
        annotation: Setting,
        pluginId: String
    ): SettingSchema? {
        val currentValue = pluginKeyValueStore.getValue(pluginId, field.name)
            ?: annotation.defaultValue.takeIf { it.isNotBlank() }
            ?: ""   // Empty string — field will show blank, user must fill if isRequired

        val args = SettingArgs(
            propertyName = field.name,
            label = annotation.label,
            description = annotation.description,
            order = annotation.order,
            required = annotation.isRequired,
            currentValue = currentValue,
            defaultValue = annotation.defaultValue,
            validation = annotation.validation,
            options = annotation.options
        )

        return try {
            when (annotation.type) {
                SettingType.TEXT           -> buildTextSetting(args)
                SettingType.PASSWORD       -> buildPasswordSetting(args)
                SettingType.TEXTAREA       -> buildTextAreaSetting(args)
                SettingType.NUMBER         -> buildNumberSchemaForField(field, args)
                SettingType.BOOLEAN        -> buildBooleanSetting(args)
                SettingType.DROPDOWN       -> buildDropdownSetting(args)
                SettingType.FILE_PATH      -> buildFilePathSetting(args)
                SettingType.DIRECTORY_PATH -> buildDirectoryPathSetting(args)
            }
        } catch (e: Exception) {
            logger.error("Failed to create schema for field '${field.name}': ${e.message}")
            null
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun buildNumberSchemaForField(field: Field, args: SettingArgs): SettingSchema =
        when (field.type) {
            Int::class.java, Long::class.java,
            java.lang.Integer::class.java, java.lang.Long::class.java -> buildIntegerSetting(args)
            else -> buildNumberSetting(args)
        }

    // -------------------------------------------------------------------------
    // Value conversion — used by PluginSettingsManager when applying settings
    // -------------------------------------------------------------------------

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    internal fun convertValue(raw: String, field: Field): Any? = runCatching {
        when (field.type) {
            String::class.java                              -> raw
            Int::class.java, java.lang.Integer::class.java -> raw.toIntOrNull()
            Boolean::class.java, java.lang.Boolean::class.java -> raw.toBooleanStrictOrNull()
            Double::class.java, java.lang.Double::class.java   -> raw.toDoubleOrNull()
            Float::class.java, java.lang.Float::class.java     -> raw.toFloatOrNull()
            Long::class.java, java.lang.Long::class.java       -> raw.toLongOrNull()
            Path::class.java                                -> Paths.get(raw)
            else -> null.also { logger.warn("Unsupported field type for conversion: ${field.type}") }
        }
    }.getOrElse {
        logger.warn("Failed to convert value '$raw' for type ${field.type}: ${it.message}")
        null
    }

    internal fun validate(value: String, annotation: Setting): Boolean {
        if (annotation.isRequired && value.isBlank()) return false
        if (annotation.validation.isNotBlank() && !Regex(annotation.validation).matches(value)) return false
        if (annotation.type == SettingType.DROPDOWN && annotation.options.isNotBlank()) {
            val options = annotation.options.split(',').map { it.trim() }
            if (value !in options) return false
        }
        return true
    }
}
