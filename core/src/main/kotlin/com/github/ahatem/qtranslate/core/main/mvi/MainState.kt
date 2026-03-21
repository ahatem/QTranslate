package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.arch.UiState

/**
 * Complete UI state for the main translation screen.
 *
 * This is an immutable snapshot — all mutations produce a new copy via [copy].
 * The [MainStore] is the sole owner; the UI only reads from this.
 *
 * @property isLoading Whether a translation or OCR operation is in progress.
 * @property inputText The text currently in the source input field.
 * @property translatedText The most recent translation result.
 * @property extraOutputText Secondary output (backward translation, summary, rewrite).
 * @property sourceLanguage The currently selected source language. May be [LanguageCode.AUTO].
 * @property detectedSourceLanguage The language auto-detected from the last translation.
 *   Only populated when [sourceLanguage] is [LanguageCode.AUTO] and the translator
 *   reports a detected language.
 * @property targetLanguage The currently selected target language.
 * @property availableServices All services currently loaded from plugins and not disabled.
 * @property availableLanguages Languages supported by the active translator,
 *   sorted with AUTO first then alphabetically.
 * @property history Ordered list of past translation snapshots.
 * @property historyIndex The current position in [history]. Points one past the last
 *   visible entry — `history[historyIndex - 1]` is the current snapshot.
 * @property spellCheckCorrections Spelling/grammar suggestions for [inputText].
 * @property isQuickTranslateDialogVisible Whether the quick translate popup is open.
 * @property isQuickTranslateDialogPinned Whether the popup stays open after losing focus.
 */
data class MainState(
    val isLoading: Boolean = false,
    val inputText: String = "",
    val translatedText: String = "",
    val extraOutputText: String = "",
    val sourceLanguage: LanguageCode = LanguageCode.AUTO,
    val detectedSourceLanguage: LanguageCode? = null,
    val targetLanguage: LanguageCode = LanguageCode.ARABIC,
    val availableServices: List<ServiceInfo> = emptyList(),
    val availableLanguages: List<LanguageCode> = emptyList(),
    val history: List<HistorySnapshot> = emptyList(),
    val historyIndex: Int = 0,
    val spellCheckCorrections: List<Correction> = emptyList(),
    val isQuickTranslateDialogVisible: Boolean = false,
    val isQuickTranslateDialogPinned: Boolean = false
) : UiState {

    /** `true` when [sourceLanguage] is [LanguageCode.AUTO]. */
    val isAutoDetectingSourceLanguage: Boolean
        get() = sourceLanguage == LanguageCode.AUTO

    /** `true` when there is a previous history entry to restore. */
    val canUndo: Boolean
        get() = historyIndex > 0

    /**
     * `true` when there is a more recent history entry to move forward to,
     * including the implicit "blank" state past the last snapshot.
     */
    val canRedo: Boolean
        get() = historyIndex < history.size

    /**
     * Returns all available services of a specific [type].
     * These are services that are loaded and not disabled — not necessarily the
     * *selected* service. To determine the selected service, read
     * [com.github.ahatem.qtranslate.core.settings.data.Configuration.servicePresets].
     */
    fun getAvailableServicesFor(type: ServiceType): List<ServiceInfo> =
        availableServices.filter { it.type == type }
}