package com.github.ahatem.qtranslate.core.main.domain.model

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType

data class ServiceSelectionState(
    val availableServices: List<ServiceInfo>,
    val selectedServices: Map<ServiceType, String?>,
    val availableLanguages: Set<LanguageCode>
)