package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle

data class TranslatorSelectorState(
    val availableTranslators: List<ServiceInfo>,
    val selectedTranslatorId: String?,
    val isLoading: Boolean,
    val availableServices: List<ServiceInfo> = availableTranslators,
    val selectedServices: Map<ServiceRole, String?> = selectedTranslatorId?.let {
        mapOf(ServiceRole.TRANSLATOR to it)
    }.orEmpty(),
    val style: ServiceSelectorStyle = ServiceSelectorStyle.CLASSIC,
    val appearance: ServiceSelectorAppearance = ServiceSelectorAppearance.ICONS_AND_TEXT
) : UiState
