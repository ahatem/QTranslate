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
    val detectedSourceLanguage: LanguageCode? = null,
    val targetLanguage: LanguageCode = LanguageCode.ARABIC,

    val availableServices: List<ServiceInfo> = emptyList(),
    val selectedServices: Map<ServiceType, String?> = emptyMap(),

    val history: List<HistorySnapshot> = emptyList(),
    val historyIndex: Int = 0,
    val spellCheckCorrections: List<Correction> = emptyList(),
    val availableLanguages: List<LanguageCode> = emptyList(),

    val isQuickTranslateDialogVisible: Boolean = false,
    val isQuickTranslateDialogPinned: Boolean = false

) : UiState {
    val isAutoDetectingSourceLanguage: Boolean get() = sourceLanguage == LanguageCode.AUTO

    val canUndo: Boolean get() = historyIndex > 0
    val canRedo: Boolean get() = historyIndex < history.size

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