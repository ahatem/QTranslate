package com.github.ahatem.qtranslate.core.main.domain.model

import com.github.ahatem.qtranslate.api.plugin.ServiceRole

/**
 * UI-facing metadata for a single loaded service.
 * Produced by [com.github.ahatem.qtranslate.core.main.domain.usecase.SelectActiveServiceUseCase]
 * and consumed by service selection dropdowns and the services panel.
 */
data class ServiceInfo(
    val id: String,
    val name: String,
    val iconPath: String?,
    val type: ServiceRole
)