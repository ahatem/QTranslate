package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.arch.UiState

data class MainState(
    val isLoading: Boolean = false,
    val inputText: String = "",
    val translatedText: String = "",
    val extraOutputText: String = "",
    val sourceLanguage: LanguageCode = LanguageCode.AUTO,
    val targetLanguage: LanguageCode = LanguageCode.ARABIC,

    val availableServices: List<ServiceInfo> = emptyList(),
    val selectedServices: Map<ServiceType, String?> = emptyMap(),

    val history: List<HistorySnapshot> = emptyList(),
    val historyIndex: Int = -1,
    val spellCheckCorrections: List<Correction> = emptyList(),
    val availableLanguages: Set<LanguageCode> = emptySet(),

    val isQuickTranslateDialogVisible: Boolean = false,
    val isQuickTranslateDialogPinned: Boolean = false

) : UiState {
    val canUndo: Boolean get() = historyIndex > 0
    val canRedo: Boolean get() = historyIndex < history.lastIndex

    fun getAvailableServicesFor(type: ServiceType): List<ServiceInfo> {
        return availableServices.filter { it.type == type }
    }

    fun getSelectedServiceFor(type: ServiceType): ServiceInfo? {
        val selectedId = selectedServices[type] ?: return null
        return availableServices.find { it.id == selectedId }
    }

    fun getSelectedTranslator(): ServiceInfo? {
        return getSelectedServiceFor(ServiceType.TRANSLATOR)
    }
}