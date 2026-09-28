package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size

internal fun buildDictionaryDialogState(
    mainState: MainState,
    config: Configuration,
    localizer: LocalizationManager,
    onLookup: (String) -> Unit,
    onListen: (String) -> Unit,
    onStopListening: () -> Unit,
    onDictionarySelected: (String) -> Unit
): DictionaryDialogState {
    val availableDicts = mainState.getAvailableServicesFor(ServiceRole.DICTIONARY)
    val selectedDictId = config.getActivePreset()
        ?.selectedServices?.get(ServiceRole.DICTIONARY)

    return DictionaryDialogState(
        title                 = localizer.getString("dictionary_dialog.title"),
        lookupButtonLabel     = localizer.getString("dictionary_dialog.lookup_button"),
        closeLabel            = localizer.getString("common.close"),
        hintMessage           = localizer.getString("dictionary_dialog.hint_message"),
        notFoundMessage       = localizer.getString("dictionary_dialog.not_found_message", mainState.dictionaryWord),
        loadingMessage        = localizer.getString("dictionary_dialog.loading_message"),
        errorMessage          = localizer.getString("dictionary_dialog.error_message"),
        synonymsLabel         = localizer.getString("dictionary_dialog.synonyms_label"),
        listenTooltip         = localizer.getString("common.listen"),
        stopListeningTooltip  = localizer.getString("common.stop"),
        isLoading             = mainState.isDictionaryLoading,
        isTtsPlaying          = mainState.isTtsPlaying,
        entries               = mainState.dictionaryEntries,
        lookedUpWord          = mainState.dictionaryWord,
        hasFailed             = mainState.dictionaryFailed,
        availableDictionaries = availableDicts,
        selectedDictionaryId  = selectedDictId,
        onLookup = onLookup,
        onListen = onListen,
        onStopListening = onStopListening,
        onDictionarySelected = onDictionarySelected
    )
}

internal fun buildQuickDictionaryDialogState(
    mainState: MainState,
    config: Configuration,
    localizer: LocalizationManager,
    onLookup: (String) -> Unit,
    onListen: (String) -> Unit,
    onStopListening: () -> Unit,
    onDictionarySelected: (String) -> Unit,
    onAutoSourceChanged: (DictionaryAutoSource) -> Unit,
    onPinToggled: () -> Unit,
    onClose: () -> Unit,
    onSavePosition: (Position) -> Unit,
    onSaveSize: (Size) -> Unit
): QuickDictionaryDialogState {
    val availableDicts = mainState.getAvailableServicesFor(ServiceRole.DICTIONARY)
    val selectedDictId = config.getActivePreset()
        ?.selectedServices?.get(ServiceRole.DICTIONARY)

    return QuickDictionaryDialogState(
        isVisible            = mainState.isQuickDictionaryVisible,
        isLoading            = mainState.isDictionaryLoading,
        entries              = mainState.dictionaryEntries,
        lookedUpWord         = mainState.dictionaryWord,
        hasFailed            = mainState.dictionaryFailed,
        isPinned             = mainState.isQuickDictionaryPinned,
        triggerCount         = mainState.quickDictionaryTriggerCount,
        availableDictionaries = availableDicts,
        selectedDictionaryId  = selectedDictId,
        autoSource               = config.dictionaryAutoSource,
        autoSourceOffLabel       = localizer.getString("dictionary_dialog.auto_source_off"),
        autoSourceTranslatedLabel = localizer.getString("dictionary_dialog.auto_source_translated"),
        autoSourceSourceLabel    = localizer.getString("dictionary_dialog.auto_source_source"),
        config = QuickDictionaryConfig(
            autoPositionEnabled  = config.isQuickDictionaryAutoPositionEnabled,
            lastKnownSize        = config.quickDictionaryLastKnownSize,
            lastKnownPosition    = config.quickDictionaryLastKnownPosition,
            // The main window now always docks its own lookups, so this popup is only ever
            // opened from the global hotkey, which fires with the pointer over the selection.
            positionNearMouse    = true,
            idleTimeoutSeconds   = config.quickDictionaryIdleTimeoutSeconds,
            closeOnClickOutside  = config.closePopupsOnClickOutside,
            transparencyPercentage = config.quickDictionaryTransparencyPercentage
        ),
        strings = QuickDictionaryStrings(
            title            = localizer.getString("dictionary_dialog.title"),
            hintMessage      = localizer.getString("dictionary_dialog.hint_message"),
            loadingMessage   = localizer.getString("dictionary_dialog.loading_message"),
            notFoundMessage  = localizer.getString("dictionary_dialog.not_found_message", mainState.dictionaryWord),
            errorMessage     = localizer.getString("dictionary_dialog.error_message"),
            lookupButtonLabel = localizer.getString("dictionary_dialog.lookup_button"),
            synonymsLabel    = localizer.getString("dictionary_dialog.synonyms_label"),
            pinTooltip       = localizer.getString("common.pin"),
            unpinTooltip     = localizer.getString("common.unpin"),
            closeTooltip     = localizer.getString("common.close"),
            listenTooltip    = localizer.getString("common.listen"),
            stopListeningTooltip = localizer.getString("common.stop")
        ),
        isTtsPlaying = mainState.isTtsPlaying,
        onLookup = onLookup,
        onListen = onListen,
        onStopListening = onStopListening,
        onDictionarySelected = onDictionarySelected,
        onAutoSourceChanged = onAutoSourceChanged,
        onPinToggled = onPinToggled,
        onClose = onClose,
        onSavePosition = onSavePosition,
        onSaveSize = onSaveSize
    )
}
