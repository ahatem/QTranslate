package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NoSettings
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
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

/**
 * A container passed to the UI layer for dynamically building a settings panel for a plugin
 */
data class PluginSettingsModel(
    val settingsClass: Class<*>,
    val schema: List<SettingSchema>
) {
    fun getSetting(propertyName: String): SettingSchema? {
        return schema.find { it.propertyName == propertyName }
    }
}

class PluginSettingsManager(
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val logger: Logger
) {
    private val cache = mutableMapOf<String, PluginSettingsModel?>()
    private val cacheMutex = Mutex()

    suspend fun getSettingsModel(pluginId: String, plugin: Plugin<*>): PluginSettingsModel? =
        cacheMutex.withLock {
            cache.getOrPut(pluginId) {
                try {
                    val settingsClass = plugin.getSettingsClass()
                    if (settingsClass == NoSettings::class.java) return@getOrPut null

                    val settingsObject = settingsClass.kotlin.createInstance()
                    val schema = buildSchema(settingsClass.kotlin, settingsObject, pluginId)
                    PluginSettingsModel(settingsClass, schema.sortedBy { it.order })
                } catch (e: Exception) {
                    logger.error("Failed to build settings model for $pluginId: ${e.message}", e)
                    null
                }
            }
        }

    private suspend fun buildSchema(kClass: KClass<*>, settingsObject: Any, pluginId: String): List<SettingSchema> {
        return kClass.memberProperties
            .filter { it.findAnnotation<Setting>() != null }
            .filterIsInstance<KMutableProperty1<Any, Any>>()
            .mapNotNull { prop ->
                val annotation = prop.findAnnotation<Setting>()!!
                val savedValue = pluginKeyValueStore.getValue(pluginId, prop.name)
                val valueToSet =
                    savedValue ?: annotation.defaultValue.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

                runCatching { setProperty(prop, settingsObject, valueToSet, annotation) }.onFailure {
                    logger.error("Failed to set ${prop.name}: ${it.message}")
                    return@mapNotNull null
                }

                createSchema(prop, annotation, valueToSet)
            }
    }

    private fun setProperty(prop: KMutableProperty1<Any, Any>, instance: Any, value: String, annotation: Setting) {
        if (!validate(value, annotation, prop.name)) return
        convertValue(value, prop.returnType)?.let { prop.set(instance, it) }
    }

    private fun createSchema(prop: KProperty<*>, annotation: Setting, currentValue: String): SettingSchema {
        return when (annotation.type) {
            SettingType.TEXT -> TextSetting(
                prop.name,
                annotation.label,
                annotation.description,
                annotation.order,
                annotation.isRequired,
                currentValue,
                annotation.defaultValue,
                annotation.validation
            )

            SettingType.PASSWORD -> PasswordSetting(
                prop.name,
                annotation.label,
                annotation.description,
                annotation.order,
                annotation.isRequired,
                currentValue,
                annotation.defaultValue,
                annotation.validation
            )

            SettingType.TEXTAREA -> TextAreaSetting(
                prop.name,
                annotation.label,
                annotation.description,
                annotation.order,
                annotation.isRequired,
                currentValue,
                annotation.defaultValue,
                annotation.validation
            )

            SettingType.NUMBER -> if (prop.returnType.classifier in setOf(
                    Int::class,
                    Long::class
                )
            ) IntegerSetting(
                prop.name,
                annotation.label,
                annotation.description,
                annotation.order,
                annotation.isRequired,
                currentValue,
                annotation.defaultValue
            ) else NumberSetting(
                prop.name,
                annotation.label,
                annotation.description,
                annotation.order,
                annotation.isRequired,
                currentValue,
                annotation.defaultValue
            )

            SettingType.BOOLEAN -> BooleanSetting(
                prop.name,
                annotation.label,
                annotation.description,
                annotation.order,
                annotation.isRequired,
                currentValue,
                annotation.defaultValue
            )

            SettingType.DROPDOWN -> DropdownSetting(
                prop.name,
                annotation.label,
                annotation.description,
                annotation.order,
                annotation.isRequired,
                currentValue,
                annotation.defaultValue,
                annotation.options.split(',').map { it.trim() }.filter { it.isNotBlank() })

            SettingType.FILE_PATH -> FilePathSetting(
                prop.name,
                annotation.label,
                annotation.description,
                annotation.order,
                annotation.isRequired,
                currentValue,
                annotation.defaultValue
            )

            SettingType.DIRECTORY_PATH -> DirectoryPathSetting(
                prop.name,
                annotation.label,
                annotation.description,
                annotation.order,
                annotation.isRequired,
                currentValue,
                annotation.defaultValue
            )
        }
    }

    private fun validate(value: String, annotation: Setting, propName: String): Boolean {
        if (annotation.isRequired && value.isBlank()) return false
        if (annotation.validation.isNotBlank() && !Regex(annotation.validation).matches(value)) return false
        if (annotation.type == SettingType.DROPDOWN && annotation.options.isNotBlank() && value !in annotation.options.split(
                ','
            ).map { it.trim() }
        ) return false
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
            else -> null
        }
    }.getOr(null)

    suspend fun applySettings(
        pluginId: String,
        plugin: Plugin<Any>,
        settingsMap: Map<String, String>
    ): Result<Unit, ServiceError> =
        runCatching {
            val settingsClass = plugin.getSettingsClass().kotlin
            val instance = settingsClass.createInstance()
            val validated = applyMapToInstance(settingsClass, instance, settingsMap)
            val result = plugin.onSettingsChanged(instance)
            if (result.isOk) pluginKeyValueStore.storeValues(pluginId, validated)
            result
        }.getOrElse { e -> Err(ServiceError.UnknownError(e.message ?: "Unknown error", e)) }

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
