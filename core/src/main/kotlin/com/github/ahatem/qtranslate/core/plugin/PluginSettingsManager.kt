package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

// -------------------- PluginSettingsModel --------------------

data class PluginSettingsModel(
    val settingsClass: Class<*>,
    val schema: List<SettingSchema>
) {
    val kotlinClass get() = settingsClass.kotlin

    fun getSetting(propertyName: String): SettingSchema? =
        schema.find { it.propertyName == propertyName }
}

// -------------------- PluginSettingsManager --------------------

class PluginSettingsManager(
    private val pluginKeyValueStore: PluginKeyValueStore,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("PluginSettingsManager")
    private val cache = mutableMapOf<String, PluginSettingsModel?>()
    private val cacheMutex = Mutex()

    suspend fun getSettingsModel(pluginId: String, plugin: Plugin<*>): PluginSettingsModel? =
        cacheMutex.withLock {
            cache.getOrPut(pluginId) {
                try {
                    val settingsClass = plugin.getSettingsClass()
                    if (settingsClass == NoSettings::class.java) return@getOrPut null

                    val instance = settingsClass.kotlin.createInstance()
                    val schema = buildSchema(settingsClass.kotlin, instance, pluginId)
                        .sortedBy { it.order }

                    PluginSettingsModel(settingsClass, schema)
                } catch (e: Exception) {
                    logger.error("Failed to build settings model for $pluginId: ${e.message}", e)
                    null
                }
            }
        }

    private suspend fun buildSchema(kClass: KClass<*>, instance: Any, pluginId: String): List<SettingSchema> =
        kClass.memberProperties
            .filter { it.findAnnotation<Setting>() != null }
            .filterIsInstance<KMutableProperty1<Any, Any>>()
            .mapNotNull { prop ->
                val annotation = prop.findAnnotation<Setting>()!!
                val rawValue = pluginKeyValueStore.getValue(pluginId, prop.name)
                    ?: annotation.defaultValue.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                try {
                    setProperty(prop, instance, rawValue, annotation)
                    createSchema(prop, annotation, rawValue)
                } catch (e: Exception) {
                    logger.error("Failed to set property ${prop.name}: ${e.message}")
                    null
                }
            }

    private fun setProperty(prop: KMutableProperty1<Any, Any>, instance: Any, rawValue: String, annotation: Setting) {
        if (!validate(rawValue, annotation, prop.name)) return
        convertValue(rawValue, prop.returnType)?.let { prop.set(instance, it) }
    }

    // -------------------- Refactored createSchema --------------------

    private fun createSchema(prop: KProperty<*>, annotation: Setting, value: String): SettingSchema {
        val baseArgs = SettingArgs(
            propertyName = prop.name,
            label = annotation.label,
            description = annotation.description,
            order = annotation.order,
            required = annotation.isRequired,
            currentValue = value,
            defaultValue = annotation.defaultValue,
            validation = annotation.validation,
            options = annotation.options
        )

        return when (annotation.type) {
            SettingType.TEXT -> TextSetting.from(baseArgs)
            SettingType.PASSWORD -> PasswordSetting.from(baseArgs)
            SettingType.TEXTAREA -> TextAreaSetting.from(baseArgs)
            SettingType.NUMBER -> buildNumberSchema(prop, baseArgs)
            SettingType.BOOLEAN -> BooleanSetting.from(baseArgs)
            SettingType.DROPDOWN -> DropdownSetting.from(baseArgs)
            SettingType.FILE_PATH -> FilePathSetting.from(baseArgs)
            SettingType.DIRECTORY_PATH -> DirectoryPathSetting.from(baseArgs)
        }
    }

    private fun buildNumberSchema(prop: KProperty<*>, args: SettingArgs): SettingSchema {
        return if (prop.returnType.classifier in setOf(Int::class, Long::class))
            IntegerSetting.from(args)
        else
            NumberSetting.from(args)
    }

    // -------------------- Validation and Conversion --------------------

    private fun validate(value: String, annotation: Setting, propName: String): Boolean {
        if (annotation.isRequired && value.isBlank()) return false
        if (annotation.validation.isNotBlank() && !Regex(annotation.validation).matches(value)) return false
        if (annotation.type == SettingType.DROPDOWN && annotation.options.isNotBlank()) {
            val options = annotation.options.split(',').map { it.trim() }
            if (value !in options) return false
        }
        return true
    }

    private fun convertValue(raw: String, type: KType): Any? = runCatching {
        when (type.classifier) {
            String::class -> raw
            Int::class -> raw.toIntOrNull()
            Boolean::class -> raw.toBooleanStrictOrNull()
            Double::class -> raw.toDoubleOrNull()
            Float::class -> raw.toFloatOrNull()
            Long::class -> raw.toLongOrNull()
            Path::class -> Paths.get(raw)
            else -> null.also { logger.warn("Unsupported type for conversion: $type") }
        }
    }.getOr(null)

    // -------------------- Apply Settings --------------------

    suspend fun applySettings(
        pluginId: String,
        plugin: Plugin<Any>,
        settingsMap: Map<String, String>
    ): Result<Unit, ServiceError> = runCatching {
        val kClass = plugin.getSettingsClass().kotlin
        val instance = kClass.createInstance()
        val applied = applyMapToInstance(kClass, instance, settingsMap)
        val result = plugin.onSettingsChanged(instance)
        if (result.isOk) pluginKeyValueStore.storeValues(pluginId, applied)
        result
    }.getOrElse { e ->
        Err(ServiceError.UnknownError(e.message ?: "Unknown error", e))
    }

    private fun applyMapToInstance(kClass: KClass<*>, instance: Any, map: Map<String, String>): Map<String, String> {
        val applied = mutableMapOf<String, String>()
        kClass.memberProperties.filterIsInstance<KMutableProperty1<Any, Any>>().forEach { prop ->
            val annotation = prop.findAnnotation<Setting>() ?: return@forEach
            val raw = map[prop.name] ?: annotation.defaultValue.takeIf { it.isNotBlank() } ?: return@forEach
            if (!validate(raw, annotation, prop.name)) return@forEach
            convertValue(raw, prop.returnType)?.let { prop.set(instance, it); applied[prop.name] = raw }
        }
        return applied
    }
}

// -------------------- Internal Helper for createSchema --------------------

private data class SettingArgs(
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

// Each Setting type has a static "from" builder for readability and reuse.
private fun TextSetting.Companion.from(a: SettingArgs) = TextSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.validation
)

private fun PasswordSetting.Companion.from(a: SettingArgs) = PasswordSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.validation
)

private fun TextAreaSetting.Companion.from(a: SettingArgs) = TextAreaSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue, a.validation
)

private fun IntegerSetting.Companion.from(a: SettingArgs) = IntegerSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue
)

private fun NumberSetting.Companion.from(a: SettingArgs) = NumberSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue
)

private fun BooleanSetting.Companion.from(a: SettingArgs) = BooleanSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue
)

private fun DropdownSetting.Companion.from(a: SettingArgs) = DropdownSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue,
    a.options.split(',').map { it.trim() }.filter { it.isNotBlank() }
)

private fun FilePathSetting.Companion.from(a: SettingArgs) = FilePathSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue
)

private fun DirectoryPathSetting.Companion.from(a: SettingArgs) = DirectoryPathSetting(
    a.propertyName, a.label, a.description, a.order, a.required,
    a.currentValue, a.defaultValue
)
