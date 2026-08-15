package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.hasType
import kotlinx.coroutines.flow.StateFlow

/**
 * A resolved service together with the id it is registered under.
 *
 * Services no longer carry their own runtime id — the host composes it from the plugin, the
 * instance and the service's key, and it is the key of the registry map. Anything that needs to
 * record *which* service did something (history, presets) needs the id alongside the instance.
 */
data class ActiveService<out T : Service>(val id: String, val service: T)

class ActiveServiceManager(
    private val activeServices: StateFlow<Map<String, Service>>,
    private val configuration: StateFlow<Configuration>
) {
    /**
     * The service currently selected for [type], with its id.
     *
     * Preference order is the active preset's choice, then any enabled service that declares the
     * capability. A service is only considered if it actually declares [type] — the preset can
     * name a service that has since stopped offering that capability, or been replaced by a
     * different plugin under the same id.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Service> getActive(type: ServiceType): ActiveService<T>? {
        val config = configuration.value
        val services = activeServices.value

        if (!config.isServiceTypeEnabled(type)) return null

        val preferredId = config.getActivePreset()?.selectedServices?.get(type)
        val preferred = preferredId
            ?.let { id -> services[id]?.let { ActiveService(id, it) } }
            ?.takeIf { it.service.hasType(type) && !config.isServiceDisabled(it.id, type) }

        val resolved = preferred
            ?: services.entries
                .firstOrNull { (id, service) ->
                    service.hasType(type) && !config.isServiceDisabled(id, type)
                }
                ?.let { ActiveService(it.key, it.value) }

        // Unchecked because T is erased. The capability check above is the real guard: a service
        // is only returned for a capability it declares, and registration refuses to register a
        // service whose declared capabilities it does not implement.
        return resolved as? ActiveService<T>
    }

    /** As [getActive], for the callers that only need the service itself. */
    fun <T : Service> getActiveService(type: ServiceType): T? = getActive<T>(type)?.service
}
