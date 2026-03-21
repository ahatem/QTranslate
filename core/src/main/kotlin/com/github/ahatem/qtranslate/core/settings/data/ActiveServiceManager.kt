package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.mapServiceToType
import kotlinx.coroutines.flow.StateFlow

/**
 * Single point of truth for "which service should I use right now?"
 *
 * Combines the live service registry from [PluginManager][com.github.ahatem.qtranslate.core.plugin.PluginManager]
 * and the current [Configuration] to resolve a concrete [Service] instance on demand.
 *
 * ### Resolution order
 * 1. Look up the active preset's stored selection for the requested [ServiceType].
 * 2. If the stored ID exists and is currently loaded, return that service.
 * 3. Otherwise, fall back to the first loaded service of the correct type.
 * 4. Return `null` if no service of that type is currently available.
 *
 * The fallback in step 3 means the app gracefully degrades when a previously
 * selected plugin is disabled or uninstalled — it automatically uses the next
 * available service rather than breaking.
 *
 * @property activeServices Live map of all currently loaded and enabled services,
 *   keyed by service ID. Sourced from [com.github.ahatem.qtranslate.core.plugin.PluginManager.activeServices].
 * @property configuration Live [Configuration] state, sourced from
 *   [com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore].
 */
class ActiveServiceManager(
    private val activeServices: StateFlow<Map<String, Service>>,
    private val configuration: StateFlow<Configuration>
) {
    /**
     * Returns the active service for [type], cast to [T].
     *
     * Returns `null` if no service of [type] is currently loaded, or if the
     * loaded service does not implement [T] (which should not happen in practice
     * since [mapServiceToType] maps by interface).
     *
     * This is a synchronous, non-blocking call — it reads the current snapshot
     * of both StateFlows.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Service> getActiveService(type: ServiceType): T? {
        val config = configuration.value
        val services = activeServices.value

        val preferredId = config.getActivePreset()?.selectedServices?.get(type)

        val resolved = preferredId?.let { services[it] }
            ?: services.values.firstOrNull { mapServiceToType(it) == type }

        return resolved as? T
    }
}