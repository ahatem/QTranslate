package com.github.ahatem.qtranslate.core.plugin.settings

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.settings.PluginAction
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingGroup
import com.github.ahatem.qtranslate.api.settings.SettingGroups
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
            val schema  = buildSchema(settingsClass, pluginId).sortedBy { it.order }
            val actions = readActions(settingsClass)
            val groups  = buildGroups(settingsClass, schema, actions)
            PluginSettingsModel(settingsClass, schema, groups)
        } catch (e: Exception) {
            logger.error("Failed to build settings schema for plugin '$pluginId': ${e.message}", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Field schema construction
    // -------------------------------------------------------------------------

    private suspend fun buildSchema(settingsClass: Class<*>, pluginId: String): List<SettingSchema> =
        settingsClass.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            val annotation = field.getAnnotation(Setting::class.java) ?: return@mapNotNull null
            buildFieldSchema(field, annotation, pluginId)
        }

    private suspend fun buildFieldSchema(
        field: Field,
        annotation: Setting,
        pluginId: String
    ): SettingSchema? {
        val currentValue = pluginKeyValueStore.getValue(pluginId, field.name)
            ?: annotation.defaultValue.takeIf { it.isNotBlank() }
            ?: ""

        val showIf = if (annotation.showIf.isBlank()) null else {
            val parts = annotation.showIf.split("=", limit = 2)
            if (parts.size == 2) ShowIfRule(parts[0].trim(), parts[1].trim())
            else {
                logger.warn("Invalid showIf format on field '${field.name}': '${annotation.showIf}'. Expected 'propertyName=value'.")
                null
            }
        }

        val fileExtensions = if (annotation.fileExtensions.isBlank()) emptyList()
            else annotation.fileExtensions.split(',').map { it.trim() }.filter { it.isNotBlank() }

        val args = SettingArgs(
            propertyName   = field.name,
            label          = annotation.label,
            description    = annotation.description,
            order          = annotation.order,
            required       = annotation.isRequired,
            currentValue   = currentValue,
            defaultValue   = annotation.defaultValue,
            validation     = annotation.validation,
            options        = annotation.options,
            group          = annotation.group,
            showIf         = showIf,
            minValue       = annotation.minValue.takeUnless { it.isNaN() },
            maxValue       = annotation.maxValue.takeUnless { it.isNaN() },
            step           = annotation.step.takeUnless { it.isNaN() },
            maxLength      = annotation.maxLength,
            rows           = annotation.rows,
            fileExtensions = fileExtensions,
            allowMultiple  = annotation.allowMultiple,
            actionMethod   = annotation.actionMethod,
            actionLabel    = annotation.actionLabel
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
                SettingType.SLIDER         -> buildSliderSetting(args).also {
                    if (it is NumberSetting)
                        logger.warn("Field '${field.name}' uses SLIDER but is missing minValue/maxValue — falling back to NUMBER spinner.")
                }
                SettingType.CUSTOM_PANEL   -> buildCustomPanelSetting(args)
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
    // @PluginAction method scanning
    // -------------------------------------------------------------------------

    private fun readActions(settingsClass: Class<*>): List<PluginActionSchema> =
        settingsClass.declaredMethods.mapNotNull { method ->
            val ann = method.getAnnotation(PluginAction::class.java) ?: return@mapNotNull null
            PluginActionSchema(
                methodName = method.name,
                label      = ann.label,
                tooltip    = ann.tooltip,
                order      = ann.order,
                group      = ann.group
            )
        }

    // -------------------------------------------------------------------------
    // Group construction from @SettingGroup / @SettingGroups class annotations
    // -------------------------------------------------------------------------

    private fun readGroupAnnotations(settingsClass: Class<*>): List<SettingGroup> {
        // @SettingGroups container (multiple groups)
        val container = settingsClass.getAnnotation(SettingGroups::class.java)
        if (container != null) return container.groups.toList()

        // Single @SettingGroup
        val single = settingsClass.getAnnotation(SettingGroup::class.java)
        if (single != null) return listOf(single)

        return emptyList()
    }

    private fun buildGroups(
        settingsClass: Class<*>,
        schema: List<SettingSchema>,
        actions: List<PluginActionSchema>
    ): List<PluginSettingsGroup> {
        val groupDefs = readGroupAnnotations(settingsClass)

        // Fields / actions with no group key (or orphaned keys) → ungrouped section
        val knownKeys      = groupDefs.map { it.key }.toSet()
        val ungroupedFields  = schema.filter { it.group.isEmpty() || it.group !in knownKeys }
            .sortedBy { it.order }
        val ungroupedActions = actions.filter { it.group.isEmpty() || it.group !in knownKeys }
            .sortedBy { it.order }

        val result = mutableListOf<PluginSettingsGroup>()

        // Ungrouped fields always come first, with no header
        if (ungroupedFields.isNotEmpty() || ungroupedActions.isNotEmpty()) {
            result += PluginSettingsGroup(
                key = "", title = "",
                fields = ungroupedFields, actions = ungroupedActions
            )
        }

        // Named groups in declared order
        groupDefs.sortedBy { it.order }.forEach { def ->
            val fields  = schema.filter { it.group == def.key }.sortedBy { it.order }
            val grpActs = actions.filter { it.group == def.key }.sortedBy { it.order }
            result += PluginSettingsGroup(
                key              = def.key,
                title            = def.title,
                description      = def.description,
                order            = def.order,
                collapsible      = def.collapsible,
                defaultCollapsed = def.defaultCollapsed,
                fields           = fields,
                actions          = grpActs
            )
        }

        // If there are no groups at all (no ungrouped + no named), return a single catch-all
        if (result.isEmpty()) {
            result += PluginSettingsGroup(key = "", title = "", fields = schema, actions = actions)
        }

        return result
    }

    // -------------------------------------------------------------------------
    // Value conversion — used by PluginSettingsManager when applying settings
    // -------------------------------------------------------------------------

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    internal fun convertValue(raw: String, field: Field): Any? = runCatching {
        when (field.type) {
            String::class.java                                          -> raw
            Int::class.java, java.lang.Integer::class.java             -> raw.toIntOrNull()
            Boolean::class.java, java.lang.Boolean::class.java         -> raw.toBooleanStrictOrNull()
            Double::class.java, java.lang.Double::class.java           -> raw.toDoubleOrNull()
            Float::class.java, java.lang.Float::class.java             -> raw.toFloatOrNull()
            Long::class.java, java.lang.Long::class.java               -> raw.toLongOrNull()
            Path::class.java                                           -> Paths.get(raw)
            else -> null.also { logger.warn("Unsupported field type for conversion: ${field.type}") }
        }
    }.getOrElse {
        logger.warn("Failed to convert value '$raw' for type ${field.type}: ${it.message}")
        null
    }

    internal fun validate(value: String, annotation: Setting): Boolean {
        if (annotation.isRequired && value.isBlank()) return false
        if (annotation.maxLength > 0 && value.length > annotation.maxLength) return false
        if (annotation.validation.isNotBlank() && !Regex(annotation.validation).matches(value)) return false
        if (annotation.type == SettingType.DROPDOWN && annotation.options.isNotBlank()) {
            val options = annotation.options.split(',').map { it.trim() }
            if (value !in options) return false
        }
        return true
    }
}
