package com.github.ahatem.qtranslate.core.main.domain.model

import com.github.ahatem.qtranslate.core.shared.arch.ServiceType

// DTO for translation service metadata shown in UI.
data class ServiceInfo(
    val id: String,
    val name: String,
    val iconPath: String?,
    val type: ServiceType
)