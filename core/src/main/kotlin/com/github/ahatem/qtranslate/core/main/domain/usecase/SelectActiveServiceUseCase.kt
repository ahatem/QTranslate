package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceSelectionState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.mapServiceToType
import com.github.michaelbull.result.getOr
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine


class SelectActiveServiceUseCase(
    private val activeServices: StateFlow<Map<String, Service>>,
    private val settingsState: StateFlow<Configuration>
) {
    fun observe(): Flow<ServiceSelectionState> {
        return combine(
            activeServices,
            settingsState
        ) { services, config ->
            val allAvailableServices = services.values
                .filterNot { it.id in config.disabledServices }
                .mapNotNull { mapServiceToServiceInfo(it) }

            val activePreset = config.getActivePreset() ?: config.servicePresets.firstOrNull()
            val finalSelectedServices = mutableMapOf<ServiceType, String?>()

            for (type in ServiceType.entries) {
                val servicesForType = allAvailableServices.filter { it.type == type }
                if (servicesForType.isEmpty()) continue

                val preferredId = activePreset?.selectedServices?.get(type)

                val isPreferenceValid = servicesForType.any { it.id == preferredId }
                finalSelectedServices[type] = if (isPreferenceValid) {
                    preferredId
                } else {
                    servicesForType.first().id
                }
            }

            val selectedTranslatorId = finalSelectedServices[ServiceType.TRANSLATOR]
            val translator = services[selectedTranslatorId] as? Translator
            val languages = translator?.getSupportedLanguages()?.getOr(emptySet())?.toList() ?: emptyList()

            ServiceSelectionState(
                availableServices = allAvailableServices,
                selectedServices = finalSelectedServices.toMap(),
                availableLanguages = languages
            )
        }
    }

    suspend fun getLanguagesFor(serviceId: String?): List<LanguageCode> {
        if (serviceId == null) return emptyList()
        val translator = activeServices.value[serviceId] as? Translator
        return translator?.getSupportedLanguages()?.getOr(emptySet())?.toList() ?: emptyList()
    }

    private fun mapServiceToServiceInfo(service: Service): ServiceInfo? {
        val type = mapServiceToType(service) ?: return null
        return ServiceInfo(
            id = service.id,
            name = service.name,
            iconPath = service.iconPath,
            type = type
        )
    }
}