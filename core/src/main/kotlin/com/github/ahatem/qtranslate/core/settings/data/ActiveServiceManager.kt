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

/**
 * The single rule for which service is active for [role]: the active preset's choice when it is
 * usable, otherwise the first usable service holding the role, otherwise none.
 *
 * [roleServiceIds] are the ids of the loaded services that hold [role], in registry order. A
 * service is usable when it is not disabled for the role, and nothing is active while the role
 * itself is switched off. [ActiveServiceManager.getActive] and every "how many translators do I
 * really have" question (Comparison eligibility) resolve through here, so they cannot disagree.
 */
fun Configuration.resolveActiveServiceId(role: ServiceRole, roleServiceIds: List<String>): String? {
    if (!isServiceRoleEnabled(role)) return null
    val usable = roleServiceIds.filterNot { isServiceDisabled(it, role) }
    val preferredId = getActivePreset()?.selectedServices?.get(role)
    return preferredId?.takeIf { it in usable } ?: usable.firstOrNull()
}

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

        val roleServiceIds = services.entries.filter { (_, service) -> service.hasRole(type) }.map { it.key }
        val resolved = config.resolveActiveServiceId(type, roleServiceIds)
            ?.let { id -> services[id]?.let { ActiveService(id, it) } }

        // Unchecked because T is erased. The hasRole check above is the real guard, and it is now
        // an honest one: a role means the service implements that role's interface, so returning
        // it as T is exactly the cast the type system would have made.
        return resolved as? ActiveService<T>
    }

    /** As [getActive], for the callers that only need the service itself. */
    fun <T : Service> getActiveService(type: ServiceRole): T? = getActive<T>(type)?.service

    /**
     * Resolves exactly [serviceId] for [role]. Unlike [getActive], this never falls back to a
     * different service when the requested id is unavailable or invalid for the role.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Service> resolve(serviceId: String, role: ServiceRole): ActiveService<T>? {
        val config = configuration.value
        if (!config.isServiceRoleEnabled(role)) return null

        val service = activeServices.value[serviceId] ?: return null
        if (!service.hasRole(role) || config.isServiceDisabled(serviceId, role)) return null

        return (service as? T)?.let { ActiveService(serviceId, it) }
    }
}
