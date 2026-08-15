package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo

data class DictionaryDialogState(
    val title: String,
    val lookupButtonLabel: String,
    val closeLabel: String,
    val hintMessage: String,
    val notFoundMessage: String,
    val loadingMessage: String,
    val errorMessage: String,
    val synonymsLabel: String,
    val listenTooltip: String = "",
    val stopListeningTooltip: String = "",
    val isLoading: Boolean,
    /** Whether speech is playing right now; the headword's Listen control becomes a stop button. */
    val isTtsPlaying: Boolean = false,
    val entries: List<DictionaryEntry>,
    val lookedUpWord: String,
    val hasFailed: Boolean,
    val availableDictionaries: List<ServiceInfo> = emptyList(),
    val selectedDictionaryId: String? = null,
    val onLookup: (word: String) -> Unit,
    val onListen: (word: String) -> Unit = {},
    val onStopListening: () -> Unit = {},
    val onDictionarySelected: (serviceId: String) -> Unit = {},
)
