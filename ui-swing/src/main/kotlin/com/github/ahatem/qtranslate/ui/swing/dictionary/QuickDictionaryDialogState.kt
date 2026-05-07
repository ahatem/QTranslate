package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.core.shared.arch.UiState

/**
 * An immutable snapshot of all data required to render the QuickDictionaryDialog.
 *
 * Callbacks are placed on the state (not the constructor) so that they can
 * close over fresh config/state on every render — same pattern as ExtraOutputPanel.
 */
data class QuickDictionaryDialogState(
    val isVisible: Boolean,
    val isLoading: Boolean,
    val entries: List<DictionaryEntry>,
    val lookedUpWord: String,
    val hasFailed: Boolean,
    val isPinned: Boolean,
    val availableDictionaries: List<ServiceInfo>,
    val selectedDictionaryId: String?,
    val config: QuickDictionaryConfig,
    val strings: QuickDictionaryStrings,
    // callbacks
    val onLookup: (word: String) -> Unit,
    val onDictionarySelected: (serviceId: String) -> Unit,
    val onPinToggled: () -> Unit,
    val onClose: () -> Unit,
    val onSavePosition: (Position) -> Unit,
    val onSaveSize: (Size) -> Unit
) : UiState

data class QuickDictionaryConfig(
    val autoPositionEnabled: Boolean,
    val lastKnownSize: Size,
    val lastKnownPosition: Position
)

data class QuickDictionaryStrings(
    val title: String,
    val hintMessage: String,
    val loadingMessage: String,
    val notFoundMessage: String,
    val errorMessage: String,
    val lookupButtonLabel: String,
    val synonymsLabel: String,
    val pinTooltip: String,
    val unpinTooltip: String,
    val closeTooltip: String,
    val servicePickerLabel: String
)
