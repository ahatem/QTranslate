package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.shared.arch.UiState

data class TranslatorSelectorState(
    val availableTranslators: List<ServiceInfo>,
    val selectedTranslatorId: String?,
    val isLoading: Boolean
) : UiState