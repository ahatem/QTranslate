package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.util.hasRole
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
     * Preference order is the active preset's choice, then any enabled service holding the role.
     * The preset's choice is still checked against [type] rather than trusted, because a preset
     * can name a service that has since stopped offering the role, or been replaced by a
     * different plugin registered under the same id.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Service> getActive(type: ServiceRole): ActiveService<T>? {
        val config = configuration.value
        val services = activeServices.value

        if (!config.isServiceRoleEnabled(type)) return null

        val preferredId = config.getActivePreset()?.selectedServices?.get(type)
        val preferred = preferredId
            ?.let { id -> services[id]?.let { ActiveService(id, it) } }
            ?.takeIf { it.service.hasRole(type) && !config.isServiceDisabled(it.id, type) }

        val resolved = preferred
            ?: services.entries
                .firstOrNull { (id, service) ->
                    service.hasRole(type) && !config.isServiceDisabled(id, type)
                }
                ?.let { ActiveService(it.key, it.value) }

        // Unchecked because T is erased. The hasRole check above is the real guard, and it is now
        // an honest one: a role means the service implements that role's interface, so returning
        // it as T is exactly the cast the type system would have made.
        return resolved as? ActiveService<T>
    }

    /** As [getActive], for the callers that only need the service itself. */
    fun <T : Service> getActiveService(type: ServiceRole): T? = getActive<T>(type)?.service
}
