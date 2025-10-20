package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.mapServiceToType
import kotlinx.coroutines.flow.StateFlow


class ActiveServiceManager(
    private val activeServices: StateFlow<Map<String, Service>>,
    private val settingsState: StateFlow<Configuration>
) {
    fun <T : Service> getActiveService(type: ServiceType, state: MainState? = null): T? {
        val config = settingsState.value
        val services = activeServices.value

        val activePreset = config.getActivePreset() ?: config.servicePresets.firstOrNull()
        val preferredId = activePreset?.selectedServices?.get(type)

        var serviceInstance = if (preferredId != null) {
            services[preferredId]
        } else {
            null
        }

        if (serviceInstance == null) {
            serviceInstance = services.values.firstOrNull { mapServiceToType(it) == type }
        }


        @Suppress("UNCHECKED_CAST")
        return serviceInstance as? T
    }
}