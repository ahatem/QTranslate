package com.github.ahatem.qtranslate.core.plugin.settings

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages plugin settings at runtime: schema retrieval and settings application.
 *
 * Responsibilities:
 * - Delegating schema construction to [PluginSettingsSchemaBuilder] (read-only, no side effects)
 * - Applying a raw `Map<String, String>` of new values to a plugin's settings instance
 *   and persisting them on success
 *
 * The schema cache is invalidated when settings are successfully applied, so the next
 * call to [getSettingsModel] reflects the new current values.
 */
internal class PluginSettingsManager(
    private val pluginKeyValueStore: PluginKeyValueStore,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("PluginSettingsManager")
    private val schemaBuilder = PluginSettingsSchemaBuilder(pluginKeyValueStore, loggerFactory)

    private val cache = mutableMapOf<String, PluginSettingsModel?>()
    private val cacheMutex = Mutex()

    /**
     * Returns the [PluginSettingsModel] for [plugin], building and caching it on first access.
     * Returns `null` if the plugin uses [PluginSettings.None].
     */
    suspend fun getSettingsModel(pluginId: String, plugin: Plugin<*>): PluginSettingsModel? =
        cacheMutex.withLock {
            cache.getOrPut(pluginId) {
                schemaBuilder.build(pluginId, plugin)
            }
        }

    /**
     * Applies [settingsMap] to [plugin] and persists the values on success.
     *
     * Steps:
     * 1. Gets the plugin's current settings instance via `plugin.getSettings()`.
     * 2. Applies each value from [settingsMap] to the corresponding field using
     *    Java reflection (matching the `@field:Setting` annotation target).
     * 3. Validates each value before setting it. Invalid values are skipped.
     * 4. Calls `plugin.onSettingsChanged(newSettings)`.
     * 5. If the plugin accepts the settings, persists all applied values to storage
     *    and invalidates the schema cache so the UI reflects the new state.
     *
     * @return `Ok(Unit)` if the plugin accepted the settings, or an `Err` if the plugin
     *         rejected them or an unexpected exception occurred.
     */
    suspend fun applySettings(
        pluginId: String,
        plugin: Plugin<*>,
        settingsMap: Map<String, String>
    ): Result<Unit, ServiceError> {
        val currentSettings = plugin.getSettings()

        // Only Configurable plugins have settings to apply.
        if (currentSettings !is PluginSettings.Configurable) {
            return Err(ServiceError.InvalidInputError(
                "Plugin '$pluginId' has no configurable settings."
            ))
        }

        return try {
            val settingsClass = currentSettings::class.java
            // Create a fresh instance to populate — we don't mutate the live settings object.
            val newInstance = settingsClass.getDeclaredConstructor().newInstance()
                as PluginSettings.Configurable

            val applied = applyMapToInstance(settingsClass, newInstance, settingsMap)

            @Suppress("UNCHECKED_CAST")
            val result = (plugin as Plugin<PluginSettings.Configurable>)
                .onSettingsChanged(newInstance)

            if (result.isOk) {
                pluginKeyValueStore.storeValues(pluginId, applied)
                // Invalidate cache — next getSettingsModel call rebuilds with new values
                cacheMutex.withLock { cache.remove(pluginId) }
            }

            result
        } catch (e: Exception) {
            logger.error("Unexpected exception applying settings for plugin '$pluginId'", e)
            Err(ServiceError.UnknownError(e.message ?: "Unknown error applying settings", e))
        }
    }

    /**
     * Applies raw string values from [map] to the fields of [instance] using Java reflection.
     * Returns a map of only the fields that were successfully validated and applied,
     * so only those values are persisted.
     */
    private fun applyMapToInstance(
        settingsClass: Class<*>,
        instance: Any,
        map: Map<String, String>
    ): Map<String, String> {
        val applied = mutableMapOf<String, String>()

        settingsClass.declaredFields.forEach { field ->
            field.isAccessible = true
            val annotation = field.getAnnotation(Setting::class.java) ?: return@forEach
            val raw = map[field.name]
                ?: annotation.defaultValue.takeIf { it.isNotBlank() }
                ?: return@forEach

            if (!schemaBuilder.validate(raw, annotation)) {
                logger.warn("Validation failed for field '${field.name}' in plugin '$settingsClass.simpleName'")
                return@forEach
            }

            val converted = schemaBuilder.convertValue(raw, field) ?: return@forEach
            field.set(instance, converted)
            applied[field.name] = raw
        }

        return applied
    }
}

/**
 * The UI-facing model for a plugin's settings panel.
 * Contains the class reference (for applying new settings) and the ordered schema list
 * (for rendering the panel).
 */
data class PluginSettingsModel(
    val settingsClass: Class<*>,
    val schema: List<SettingSchema>
) {
    fun getSetting(propertyName: String): SettingSchema? =
        schema.find { it.propertyName == propertyName }
}
