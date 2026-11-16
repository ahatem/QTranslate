package com.github.ahatem.qtranslate.core.plugin.settings

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.storage.ScopedSecretStore
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages plugin settings at runtime: schema retrieval and settings application.
 *
 * Responsibilities:
 * - Delegating schema construction to [PluginSettingsSchemaBuilder]
 * - Applying a raw `Map<String, String>` of new values to a plugin's settings instance
 *   and persisting them on success
 *
 * [SettingType.PASSWORD] values are persisted to the plugin's [com.github.ahatem.qtranslate.api.plugin.SecretStore]
 * only — never to the normal settings store, and never logged. A blank password value in the
 * map keeps the current secret: there is no explicit remove action yet, so clearing the field
 * cannot clear the credential (a future remove action is the only way to clear a stored secret).
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
     * 5. If the plugin accepts the settings, persists applied values to storage —
     *    [SettingType.PASSWORD] values to the secret store, everything else to the
     *    normal settings store — and invalidates the schema cache so the UI reflects
     *    the new state. On failure nothing is persisted anywhere.
     *
     * A blank [SettingType.PASSWORD] value keeps the current secret: the existing value
     * is injected into the instance handed to the plugin, so the plugin sees the real
     * key rather than an empty string (which some plugins would then persist as an
     * empty secret, erasing the credential).
     *
     * @return `Ok(Unit)` if the plugin accepted the settings, or an `Err` if the plugin
     *         rejected them or an unexpected exception occurred.
     */
    /**
     * Applies [settingsMap] to [plugin] and persists the values on success.
     *
     * @param clearedSecrets [SettingType.PASSWORD] fields the user explicitly asked to remove. They
     *   are handed to the plugin as empty and deleted from the secret store on success. Without
     *   this, a stored credential could never be removed: a blank field means "keep the current
     *   secret", which is the only sane reading of a pre-filled password box.
     *
     * Everything else is unchanged: blank password values keep the current secret, and invalid
     * values are skipped rather than persisted.
     */
    suspend fun applySettings(
        pluginId: String,
        plugin: Plugin<*>,
        settingsMap: Map<String, String>,
        clearedSecrets: Set<String> = emptySet(),
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

            val applied = applyMapToInstance(
                settingsClass, newInstance, settingsMap,
                ScopedSecretStore(pluginId, ServiceId.DEFAULT_INSTANCE, pluginKeyValueStore),
                clearedSecrets,
            )

            @Suppress("UNCHECKED_CAST")
            val result = (plugin as Plugin<PluginSettings.Configurable>)
                .onSettingsChanged(newInstance)

            if (result.isOk) {
                pluginKeyValueStore.storeValues(pluginId, applied.normal)
                val secrets = ScopedSecretStore(pluginId, ServiceId.DEFAULT_INSTANCE, pluginKeyValueStore)
                applied.secrets.forEach { (key, value) -> secrets.put(key, value) }
                applied.secretRemovals.forEach { key -> secrets.remove(key) }
                // Invalidate cache — next getSettingsModel call rebuilds with new values
                cacheMutex.withLock { cache.remove(pluginId) }
            }

            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Provider code can throw with arbitrary text; the log and the returned error keep
            // only the failure class, never the exception message.
            logger.error("Unexpected exception applying settings for plugin '$pluginId' (${e::class.simpleName})")
            Err(ServiceError.UnknownError("Applying settings failed (${e::class.simpleName})", e))
        }
    }

    /** Values applied to a settings instance, split by the store they must be persisted to. */
    private data class AppliedValues(
        /** Non-secret values, persisted to the normal settings store. */
        val normal: Map<String, String>,
        /** [SettingType.PASSWORD] values, persisted to the secret store only. */
        val secrets: Map<String, String>,
        /** Password fields the user explicitly cleared, deleted from the secret store. */
        val secretRemovals: Set<String> = emptySet(),
    )

    /**
     * Applies raw string values from [map] to the fields of [instance] using Java reflection.
     * Returns the values that were successfully validated and applied, split by store, so only
     * those values are persisted.
     *
     * [SettingType.PASSWORD] fields never land in the normal map: a non-blank value goes to the
     * secret map (which the caller persists to the secret store), and a blank value keeps the
     * current secret — the stored value is injected into [instance] so the plugin sees the real
     * key, and nothing is scheduled for persistence. A field named in [clearedSecrets] is the
     * explicit exception: the plugin receives an empty value and the stored secret is removed.
     */
    private suspend fun applyMapToInstance(
        settingsClass: Class<*>,
        instance: Any,
        map: Map<String, String>,
        secretStore: ScopedSecretStore,
        clearedSecrets: Set<String>,
    ): AppliedValues {
        val applied = mutableMapOf<String, String>()
        val secretApplied = mutableMapOf<String, String>()
        val secretRemovals = mutableSetOf<String>()

        settingsClass.declaredFields.forEach { field ->
            field.isAccessible = true
            val annotation = field.getAnnotation(Setting::class.java) ?: return@forEach

            if (annotation.type == SettingType.PASSWORD) {
                // An explicit clear wins over everything: the user asked for the stored value to go
                // away, so the plugin is handed an empty one and the secret is deleted on success.
                if (field.name in clearedSecrets) {
                    if (field.type == String::class.java) field.set(instance, "")
                    secretRemovals += field.name
                    return@forEach
                }

                val raw = map[field.name]
                if (raw.isNullOrBlank()) {
                    // Blank edit keeps the current secret. The plugin receives the real key
                    // in the instance, never a blank that some plugins would persist,
                    // erasing the credential.
                    secretStore.get(field.name)?.let { field.set(instance, it) }
                    return@forEach
                }

                if (!schemaBuilder.validate(raw, annotation)) {
                    logger.warn("Validation failed for field '${field.name}' in plugin '$settingsClass.simpleName'")
                    return@forEach
                }

                val converted = schemaBuilder.convertValue(raw, field) ?: return@forEach
                field.set(instance, converted)
                secretApplied[field.name] = raw
                return@forEach
            }

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

        return AppliedValues(applied, secretApplied, secretRemovals)
    }
}

/**
 * The UI-facing model for a plugin's settings panel.
 *
 * [schema] is the flat ordered list of all settings fields — kept for backward compatibility
 * with any code that iterates all fields regardless of grouping.
 *
 * [groups] is the structured view used by the settings dialog to render named sections,
 * action button strips, and conditional visibility. Always contains at least one group;
 * plugins without `@SettingGroup` annotations get a single unnamed group that holds all fields.
 */
data class PluginSettingsModel(
    val settingsClass: Class<*>,
    val schema: List<SettingSchema>,
    val groups: List<PluginSettingsGroup> = emptyList()
)
