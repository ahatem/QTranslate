package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceOption
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.ahatem.qtranslate.core.document.DocumentTranslationProgress
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
 * @property isExtraOutputLoading Whether the secondary output is still being produced.
 *   The primary translation is published as soon as it arrives, so this stays true for a
 *   short while after [isLoading] has already returned to false.
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
    val isExtraOutputLoading: Boolean = false,
    val sourceLanguage: LanguageCode = LanguageCode.AUTO,
    val detectedSourceLanguage: LanguageCode? = null,
    val targetLanguage: LanguageCode = LanguageCode.ARABIC,
    val availableServices: List<ServiceInfo> = emptyList(),
    val availableLanguages: List<LanguageCode> = emptyList(),
    /**
     * Options declared by the active service for each capability, for the pickers that offer
     * them. Empty for a capability with no active service, which the UI renders as no choices
     * rather than as the host's own guess at what the choices should be.
     */
    val serviceOptions: Map<ServiceType, List<ServiceOption>> = emptyMap(),
    val history: List<HistorySnapshot> = emptyList(),
    val historyIndex: Int = 0,
    val dictionaryEntries: List<DictionaryEntry> = emptyList(),
    val isDictionaryLoading: Boolean = false,
    val dictionaryWord: String = "",
    val dictionaryFailed: Boolean = false,
    val isDictionaryPanelVisible: Boolean = false,
    val spellCheckCorrections: List<Correction> = emptyList(),
    val isQuickTranslateDialogVisible: Boolean = false,
    val isQuickTranslateDialogPinned: Boolean = false,
    val isQuickDictionaryVisible: Boolean = false,
    val isQuickDictionaryPinned: Boolean = false,
    /**
     * Incremented every time the user asks for a popup that is already open.
     *
     * A dialog cannot otherwise tell "the user pressed the hotkey again" from any of the dozens
     * of unrelated state changes it is re-rendered for, and the two call for different things:
     * one should restart the auto-hide countdown, the rest should not.
     */
    val quickTranslateTriggerCount: Int = 0,
    val quickDictionaryTriggerCount: Int = 0,
    val imageSearchTriggerCount: Int = 0,
    /**
     * A short definition shown beneath a single-word translation, or empty.
     *
     * Kept apart from [dictionaryEntries], which belongs to the dictionary the user opened. This
     * is a secondary detail attached to a translation, and conflating the two would let a glance
     * overwrite what someone was reading in the dictionary panel.
     */
    val inlineDefinition: String = "",
    val imageResults: List<ImageResult> = emptyList(),
    val isImageSearchLoading: Boolean = false,
    val imageSearchTerm: String = "",
    val imageSearchFailed: Boolean = false,
    val isImageSearchVisible: Boolean = false,
    val isImageSearchPinned: Boolean = false,
    /** True while a silent background translation for inline replace is running. */
    val isReplacingSelection: Boolean = false,
    /** True while the [com.github.ahatem.qtranslate.core.audio.AudioPlayer] is actively playing TTS audio. */
    val isTtsPlaying: Boolean = false,
    /** Progress for an active document translation, or null when idle. */
    val documentTranslationProgress: DocumentTranslationProgress? = null
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
